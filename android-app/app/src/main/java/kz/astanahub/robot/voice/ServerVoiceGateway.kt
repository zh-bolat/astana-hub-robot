package kz.astanahub.robot.voice

import android.os.SystemClock
import java.util.UUID
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runInterruptible
import kz.astanahub.robot.audio.GrowingAudio
import kz.astanahub.robot.audio.LiveEndpointer
import kz.astanahub.robot.backend.TtsStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.audio.AudioClip
import kz.astanahub.robot.audio.ClipPlayer
import kz.astanahub.robot.audio.VadConfig
import kz.astanahub.robot.audio.WavEncoder
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.LiveSttEvent
import kz.astanahub.robot.backend.LiveSttSession
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.LogSafe
import timber.log.Timber

/** Событие серверного STT — для лога на экране диагностики. */
data class SttEvent(
    val text: String,
    val lang: Lang?,
    val confidence: Double?,
    val audioMillis: Long,
    val latencyMillis: Long,
    val error: BackendError? = null,
)

/** Итог серверной озвучки. */
sealed interface ServerSpeakResult {
    data object Done : ServerSpeakResult
    data object Interrupted : ServerSpeakResult
    /** TTS недоступен (503 speech_unavailable, сеть…): [remaining] — неозвученные фразы. */
    data class Unavailable(val remaining: List<String>, val error: BackendError) : ServerSpeakResult
}

/**
 * Голос через backend (вариант B, docs/api-contract.md «Голос через сервер»):
 * микрофон → WS /v1/stt/stream (live: текст по ходу речи, конец фразы находит сервер);
 * если live недоступен — микрофон → VAD → WAV → /v1/stt; фразы → куски ≤ 300 символов → /v1/tts (следующий синтезируется, пока играет текущий) → MediaPlayer без пауз.
 * Работает на эмуляторе (микрофон хоста) и на роботе.
 */
class ServerVoiceGateway(
    private val backend: BackendApi,
    private val recorder: MicRecorder,
    private val player: ClipPlayer,
    /** VAD приложения; читается на каждую фразу (порог тишины меняется в диагностике). */
    private val vadConfig: () -> VadConfig = { VadConfig() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Кеш фраз голосом ElevenLabs: такие куски играются из файла, без сети. */
    private val cache: PhraseAudioCache? = null,
    /** `/v1/tts?stream=true` — звук с первых байт; читается на каждый кусок (флаг в диагностике). */
    private val streaming: () -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Сколько повторять неудачный синтез куска (1 повтор). */
    private val ttsRetries: Int = 1,
    private val firstByteTimeoutMillis: Long = FIRST_BYTE_TIMEOUT_MS,
) : VoiceGateway {

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 16)
    override val transcripts: Flow<Transcript> = _transcripts

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    private val _sttEvents = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    val sttEvents: SharedFlow<SttEvent> = _sttEvents

    private val _utteranceEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val utteranceEnded: Flow<Unit> = _utteranceEnded

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    /** session_id для /v1/stt: тот же, что у экрана (RoutingVoiceGateway.setSessionId). */
    @Volatile var sttSessionId: String = UUID.randomUUID().toString()

    /** Подсказка языка для STT; null — автоопределение на сервере. */
    @Volatile var sttLangHint: Lang? = null

    /** Своя озвучка (speakQueue). */
    private val ownSpeaking = MutableStateFlow(false)
    /** Любая озвучка робота + хвост тишины — от RoutingVoiceGateway. */
    private val externalSpeech = MutableStateFlow(false)
    private val muted = combine(ownSpeaking, externalSpeech) { own, ext -> own || ext }
    private fun isMuted() = ownSpeaking.value || externalSpeech.value
    @Volatile private var stopRequested = false

    private var listenJob: Job? = null
    @Volatile private var continuous = false
    @Volatile private var liveDisabledUntil = 0L

    fun hasMicPermission() = recorder.hasPermission()

    /**
     * Робот говорит (любой голос) или только что закончил: не пишем, текущую запись отменяем —
     * иначе распознаем собственную речь и перебьём сами себя.
     */
    fun setSpeechActive(active: Boolean) {
        externalSpeech.value = active
    }

    /** Одна фраза по кнопке микрофона (эмулятор, диагностика). */
    fun listenOnce() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            // Кнопку нажали сразу после «Стоп»: дождаться тишины (хвост ~300 мс), но не вечно.
            withTimeoutOrNull(PTT_WAIT_FOR_SILENCE_MS) { muted.first { !it } }
            listenUtterance()
        }
    }

    /** Постоянное прослушивание (робот в режиме server/hybrid): пауза на время озвучки — без эха. */
    fun setContinuous(enabled: Boolean) {
        if (continuous == enabled) return
        continuous = enabled
        listenJob?.cancel()
        listenJob = if (enabled) scope.launch {
            while (isActive && continuous) {
                muted.first { !it }
                if (!listenUtterance()) delay(ERROR_BACKOFF_MS)
            }
        } else null
        Timber.i("server ASR continuous=%s", enabled)
    }

    fun stopListening() {
        continuous = false
        listenJob?.cancel()
        listenJob = null
        _micLevel.value = 0f
        if (_activity.value == VoiceActivity.LISTENING) _activity.value = VoiceActivity.IDLE
    }

    /** @return false при ошибке записи/сети (для паузы перед повтором). */
    private suspend fun listenUtterance(): Boolean {
        if (!recorder.hasPermission()) {
            Timber.w("RECORD_AUDIO not granted")
            _sttEvents.tryEmit(SttEvent("", null, null, 0, 0, BackendError(ErrorKind.UNKNOWN, message = "нет разрешения RECORD_AUDIO")))
            return false
        }
        if (SystemClock.elapsedRealtime() >= liveDisabledUntil) {
            val session = backend.openLiveStt(sttLangHint, sttSessionId)
            if (session != null) listenLive(session)?.let { return it }
        }
        _activity.value = VoiceActivity.LISTENING
        val vad = vadConfig()
        val result = try {
            cancelOnSpeech { recorder.recordUtterance(vad) { _micLevel.value = it } }
        } finally {
            _micLevel.value = 0f
            if (_activity.value == VoiceActivity.LISTENING) _activity.value = VoiceActivity.IDLE
        }
        if (result == null) {
            Timber.d("recording cancelled: robot is speaking")
            return true
        }
        return when (result) {
            is MicRecorder.Result.NoSpeech -> true
            is MicRecorder.Result.Error -> {
                Timber.w("recorder: %s", result.message)
                _sttEvents.tryEmit(SttEvent("", null, null, 0, 0, BackendError(ErrorKind.UNKNOWN, message = result.message)))
                false
            }
            is MicRecorder.Result.Speech -> {
                val audioMillis = result.pcm.size * 1000L / vad.sampleRate
                _utteranceEnded.tryEmit(Unit)
                _transcripts.tryEmit(Transcript("…", isFinal = false))
                val started = SystemClock.elapsedRealtime()
                val stt = backend.stt(WavEncoder.encode(result.pcm, vad.sampleRate), sttLangHint, sttSessionId)
                val latency = SystemClock.elapsedRealtime() - started
                when (stt) {
                    is ApiResult.Success -> {
                        val r = stt.value
                        Timber.i("STT %dms audio=%dms [%s %.2f]: %s", latency, audioMillis, r.lang?.code, r.confidence ?: -1.0, LogSafe.redact(r.text))
                        _sttEvents.tryEmit(SttEvent(r.text, r.lang, r.confidence, audioMillis, latency))
                        _transcripts.tryEmit(Transcript(r.text, isFinal = true, lang = r.lang))
                        true
                    }
                    is ApiResult.Failure -> {
                        Timber.w("STT failed: %s", stt.error)
                        _sttEvents.tryEmit(SttEvent("", null, null, audioMillis, latency, stt.error))
                        _transcripts.tryEmit(Transcript("", isFinal = false))
                        false
                    }
                }
            }
        }
    }

    /** [block] отменяется, как только робот заговорит; null — отменено (или уже говорит). */
    private suspend fun <T> cancelOnSpeech(block: suspend () -> T): T? = coroutineScope {
        if (isMuted()) return@coroutineScope null
        val work = async { block() }
        val watcher = launch { muted.first { it }; work.cancel() }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive() // отменили нас самих — пробрасываем
            null
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Live STT: звук уходит на сервер, пока посетитель говорит; промежуточный текст — на экран.
     * @return null — live недоступен (старый сервер, нет сети до ответа `ready`): записать фразу по-старому.
     */
    private suspend fun listenLive(session: LiveSttSession): Boolean? = coroutineScope {
        _activity.value = VoiceActivity.LISTENING
        val opened = SystemClock.elapsedRealtime()
        var audioBytes = 0L
        var ready = false
        val vad = vadConfig()
        // Свой VAD по тем же кускам: тишина vad.endSilenceMillis после речи -> `end`, не ждём серверный (~0,8 с).
        // ПРОВЕРИТЬ НА РОБОТЕ: не обрывает ли 0,5 с паузы посреди фразы (тогда поднять порог в диагностике).
        val endpointer = LiveEndpointer(vad)
        // робот заговорил — запись прекращаем, иначе распознаем собственную речь
        val muteWatcher = launch { muted.first { it }; session.cancel() }
        val mic = launch {
            val error = recorder.stream(vad.sampleRate, onLevel = { _micLevel.value = it }) { chunk ->
                audioBytes += chunk.size
                session.sendPcm(chunk)
                if (endpointer.accept(chunk)) {
                    session.end()
                    _utteranceEnded.tryEmit(Unit)
                }
            }
            if (error != null) {
                Timber.w("recorder: %s", error)
                session.cancel()
            }
        }
        try {
            for (event in session.events) {
                when (event) {
                    is LiveSttEvent.Ready -> {
                        ready = true
                        Timber.i("live STT ready in %dms via %s", SystemClock.elapsedRealtime() - opened, event.provider)
                    }
                    is LiveSttEvent.Partial -> _transcripts.tryEmit(Transcript(event.text, isFinal = false))
                    is LiveSttEvent.Final -> {
                        mic.cancel()
                        val r = event.result
                        val audioMillis = audioBytes * 1000 / (vad.sampleRate * 2)
                        val latency = SystemClock.elapsedRealtime() - opened
                        Timber.i("live STT final audio=%dms [%s]: %s", audioMillis, r.lang?.code, LogSafe.redact(r.text))
                        _sttEvents.tryEmit(SttEvent(r.text, r.lang, r.confidence, audioMillis, latency))
                        _transcripts.tryEmit(Transcript(r.text, isFinal = true, lang = r.lang))
                        if (r.text.isBlank()) _transcripts.tryEmit(Transcript("", isFinal = false))
                        return@coroutineScope true
                    }
                    is LiveSttEvent.Failed -> {
                        mic.cancel()
                        Timber.w("live STT failed (ready=%s): %s", ready, event.error)
                        _transcripts.tryEmit(Transcript("", isFinal = false))
                        if (!ready) {
                            liveDisabledUntil = SystemClock.elapsedRealtime() + LIVE_RETRY_AFTER_MS
                            return@coroutineScope null
                        }
                        _sttEvents.tryEmit(SttEvent("", null, null, 0, 0, event.error))
                        return@coroutineScope false
                    }
                }
            }
            when {
                isMuted() -> true // отменили, потому что робот заговорил
                ready -> false
                else -> null // соединение закрылось без ответа
            }
        } finally {
            muteWatcher.cancel()
            mic.cancel()
            session.cancel()
            _micLevel.value = 0f
            if (_activity.value == VoiceActivity.LISTENING) _activity.value = VoiceActivity.IDLE
        }
    }

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean =
        speakDetailed(phrases, lang) == ServerSpeakResult.Done

    /** Озвучка готового списка (экран диагностики): склейка и предзагрузка — как у [speakQueue]. */
    suspend fun speakDetailed(phrases: List<String>, lang: Lang): ServerSpeakResult {
        if (phrases.isEmpty()) return ServerSpeakResult.Done
        val queue = PhraseQueue()
        phrases.forEach(queue::add)
        queue.close()
        return speakQueue(queue, lang)
    }

    /**
     * Озвучка очереди фраз через /v1/tts без пауз ([SpeechPipeline.lookahead]): пока играет кусок, следующий
     * синтезируется и готовится в MediaPlayer. Ошибка синтеза → [ServerSpeakResult.Unavailable], неозвученные фразы
     * остаются в [queue] (их доскажет встроенный голос). Микрофон серверного ASR глушится на всё время вызова.
     */
    suspend fun speakQueue(
        queue: PhraseQueue,
        lang: Lang,
        listener: SpeechListener = SpeechListener.NONE,
    ): ServerSpeakResult {
        stopRequested = false
        ownSpeaking.value = true
        _activity.value = VoiceActivity.SPEAKING
        try {
            val result = SpeechPipeline.lookahead(
                queue = queue,
                prepare = { chunk -> synthesize(chunk, lang, listener) },
                play = ::playPrepared,
                release = { it.release() },
                listener = listener,
                isStopped = { stopRequested },
            )
            return when (result) {
                PipelineResult.Done -> ServerSpeakResult.Done
                PipelineResult.Interrupted -> ServerSpeakResult.Interrupted
                is PipelineResult.Failed -> ServerSpeakResult.Unavailable(queue.pending(), result.error)
            }
        } finally {
            ownSpeaking.value = false
            _activity.value = VoiceActivity.IDLE
        }
    }

    /** Подготовленный кусок: один клип (синтез / кеш) или несколько (каждая фраза куска — из кеша). */
    private inner class PreparedAudio(val clips: List<AudioClip>, private val onRelease: () -> Unit = {}) {
        private val done = AtomicBoolean(false)

        /** Не сыгран: освободить клипы и остановить докачку. Идемпотентно. */
        fun release() {
            finish() // сначала остановить докачку (readAt плеера проснётся), потом освободить плееры
            clips.forEach(player::release)
        }

        fun finish() {
            if (done.compareAndSet(false, true)) onRelease()
        }
    }

    private suspend fun playPrepared(audio: PreparedAudio): Boolean {
        var ok = true
        try {
            for (clip in audio.clips) {
                if (!ok || stopRequested) {
                    ok = false
                    player.release(clip)
                    continue
                }
                ok = player.play(clip)
            }
            return ok
        } finally {
            audio.clips.forEach(player::release) // повторное освобождение безопасно
            audio.finish()
        }
    }

    /** Фраза есть в кеше голоса. */
    fun hasCached(text: String, lang: Lang): Boolean = cache?.has(text, lang) == true

    /** Сыграть фразу из кеша (без сети и субтитров). null — фразы нет в кеше; true/false — доиграна / прервана. */
    suspend fun playCached(text: String, lang: Lang): Boolean? {
        val bytes = cache?.get(text, lang) ?: return null
        stopRequested = false
        ownSpeaking.value = true
        _activity.value = VoiceActivity.SPEAKING
        try {
            val clip = player.prepare(bytes) ?: return null
            if (stopRequested) {
                player.release(clip)
                return false
            }
            return player.play(clip)
        } finally {
            ownSpeaking.value = false
            _activity.value = VoiceActivity.IDLE
        }
    }

    /**
     * Кусок -> звук: сначала кеш (весь кусок или каждая его фраза), иначе `/v1/tts` (потоково, если включено).
     * Неудача (ошибка, таймаут, 503, `X-Speech-Provider: fake`) — ещё [ttsRetries] попыток, потом [Prepared.Failed].
     */
    private suspend fun synthesize(chunk: SpeechChunk, lang: Lang, listener: SpeechListener): Prepared<PreparedAudio> {
        cachedAudio(chunk, lang)?.let { clips ->
            Timber.i("TTS %s: %d chars from cache", lang.code, chunk.text.length)
            listener.onTtsFirstByte(chunk)
            return Prepared.Ready(PreparedAudio(clips))
        }
        var lastError = BackendError(ErrorKind.UNKNOWN, message = "tts not attempted")
        for (attempt in 0..ttsRetries) {
            if (attempt > 0) {
                if (stopRequested) break
                Timber.i("TTS retry %d after %s", attempt, lastError.kind)
            }
            val started = SystemClock.elapsedRealtime()
            val stream = streaming()
            val result = if (stream) synthesizeStreamed(chunk, lang, listener) else synthesizeWhole(chunk, lang, listener)
            when (result) {
                is Prepared.Ready -> {
                    Timber.i(
                        "TTS %s%s: %d chars (%d phrases), ready in %d ms",
                        lang.code, if (stream) " stream" else "", chunk.text.length, chunk.phrases.size,
                        SystemClock.elapsedRealtime() - started,
                    )
                    return result
                }
                is Prepared.Failed -> {
                    Timber.w("TTS failed: %s", result.error)
                    lastError = result.error
                }
            }
        }
        return Prepared.Failed(lastError)
    }

    private suspend fun cachedAudio(chunk: SpeechChunk, lang: Lang): List<AudioClip>? {
        val c = cache ?: return null
        val whole = c.get(chunk.text, lang)
        val parts: List<ByteArray> = when {
            whole != null -> listOf(whole)
            chunk.phrases.size > 1 -> chunk.phrases.map { c.get(it, lang) ?: return null }
            else -> return null
        }
        val clips = mutableListOf<AudioClip>()
        for (bytes in parts) {
            val clip = player.prepare(bytes)
            if (clip == null) {
                clips.forEach(player::release)
                return null
            }
            clips += clip
        }
        return clips
    }

    private suspend fun synthesizeWhole(chunk: SpeechChunk, lang: Lang, listener: SpeechListener): Prepared<PreparedAudio> =
        when (val audio = backend.tts(chunk.text, lang)) {
            is ApiResult.Failure -> Prepared.Failed(audio.error)
            is ApiResult.Success -> {
                listener.onTtsFirstByte(chunk)
                val clip = player.prepare(audio.value)
                if (clip != null) Prepared.Ready(PreparedAudio(listOf(clip)))
                else Prepared.Failed(BackendError(ErrorKind.UNKNOWN, message = "MP3 не воспроизводится"))
            }
        }

    /**
     * Потоковый синтез: байты качаются в [GrowingAudio] фоном, плеер готовится с первых байт.
     * Обрыв посреди потока — фраза дозапрашивается без stream, недостающий хвост дописывается ([pump]).
     */
    private suspend fun synthesizeStreamed(chunk: SpeechChunk, lang: Lang, listener: SpeechListener): Prepared<PreparedAudio> {
        val stream = when (val r = backend.ttsStream(chunk.text, lang)) {
            is ApiResult.Failure -> return Prepared.Failed(r.error)
            is ApiResult.Success -> r.value
        }
        val audio = GrowingAudio()
        val pumpJob = scope.launch(ioDispatcher) { pump(stream, audio, chunk.text, lang) }
        val cleanup: () -> Unit = {
            pumpJob.cancel()
            runCatching { stream.close() }
        }
        try {
            val gotBytes = runInterruptible(ioDispatcher) { audio.awaitFirstByte(firstByteTimeoutMillis) }
            if (!gotBytes) {
                cleanup()
                return Prepared.Failed(
                    if (audio.isFinished) BackendError(ErrorKind.NETWORK, message = "tts stream: no audio")
                    else BackendError(ErrorKind.TIMEOUT, message = "tts stream: no first byte in ${firstByteTimeoutMillis}ms"),
                )
            }
            listener.onTtsFirstByte(chunk)
            val clip = player.prepareStream(audio)
            if (clip == null) {
                cleanup()
                return Prepared.Failed(BackendError(ErrorKind.UNKNOWN, message = "MP3 stream не воспроизводится"))
            }
            return Prepared.Ready(PreparedAudio(listOf(clip), onRelease = cleanup))
        } catch (e: CancellationException) {
            cleanup()
            throw e
        }
    }

    /** Качает поток в [audio]; обрыв — дозапрос фразы целиком без stream и дописывание недостающего хвоста. */
    private suspend fun pump(stream: TtsStream, audio: GrowingAudio, text: String, lang: Lang) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        try {
            while (true) {
                val n = runInterruptible { stream.read(buffer) }
                if (n < 0) break
                audio.append(buffer, n)
            }
            audio.complete()
        } catch (e: CancellationException) {
            audio.fail()
            throw e
        } catch (e: IOException) {
            val have = audio.size
            Timber.w("TTS stream broken after %d bytes -> refetch without stream", have)
            when (val full = backend.tts(text, lang)) {
                is ApiResult.Success -> {
                    audio.appendFrom(full.value, have)
                    audio.complete()
                }
                is ApiResult.Failure -> {
                    Timber.w("TTS refetch failed: %s", full.error.kind)
                    audio.fail()
                }
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    override fun stopSpeaking() {
        stopRequested = true
        player.stop()
    }

    override fun setLanguage(lang: Lang) {
        sttLangHint = lang
    }

    override fun setSessionId(id: String) {
        sttSessionId = id
    }

    private companion object {
        const val ERROR_BACKOFF_MS = 2_000L
        /** Потоковый /v1/tts: столько ждём первый байт (контракт: таймаут /tts — 5 с). */
        const val FIRST_BYTE_TIMEOUT_MS = 5_000L
        const val PUMP_BUFFER_BYTES = 8 * 1024
        const val PTT_WAIT_FOR_SILENCE_MS = 2_000L
        /** Live не открылся — минуту пишем фразы по-старому (/v1/stt), потом пробуем снова. */
        const val LIVE_RETRY_AFTER_MS = 60_000L
    }
}
