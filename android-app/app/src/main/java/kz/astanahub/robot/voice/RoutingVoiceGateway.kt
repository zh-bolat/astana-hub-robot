package kz.astanahub.robot.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.Phrases
import timber.log.Timber

/**
 * Голос по [VoiceConfig]: распознавание — встроенное или серверное; синтез — по языку (по умолчанию — ElevenLabs
 * через сервер на всех языках). Серверный TTS не ответил и после повтора (ошибка, таймаут, 503, `X-Speech-Provider: fake`):
 * - [VoiceConfig.builtinFallback] = false (по умолчанию): встроенный голос НЕ звучит; оставшиеся фразы — субтитрами
 *   без звука (фразы из кеша голоса — из файла), и не чаще раза в [cantSpeakIntervalMillis] звучит закешированное
 *   «извините, не могу сейчас говорить»;
 * - true: оставшиеся фразы — встроенным голосом (как раньше).
 * Локальные фразы робота лежат в [PhraseAudioCache] и играются из файла ([PhraseAudioCache.segment] выделяет их
 * отдельными фразами).
 *
 * Надёжность (волна 3):
 * - одна очередь озвучки: [speak] сериализован, [stopSpeaking] глушит и то, что играет, и то, что ждёт;
 * - общий признак «говорю» ([speechGuard]: любая озвучка + хвост тишины) — серверный ASR в это время
 *   не пишет, результаты встроенного ASR отбрасываются (не перебиваем сами себя);
 * - /v1/stt недоступен (сеть/таймаут) → откат на встроенный ASR (если он есть на flavor) до тех пор,
 *   пока /v1/health не ответит (проверка раз в [probeIntervalMillis]); наружу — событие [connection].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingVoiceGateway(
    /** Встроенное распознавание: AgentOS / SkillApi; на эмуляторе — заглушка. */
    val builtinAsr: VoiceGateway,
    /** Встроенный синтез: AgentOS / SkillApi.playText; на эмуляторе — Android TTS. */
    val builtinTts: VoiceGateway,
    val server: ServerVoiceGateway,
    private val settings: VoiceSettings,
    private val micControl: BuiltinMicControl,
    /** На роботе серверный ASR слушает постоянно; иначе — только по кнопке микрофона. */
    private val continuousServerAsr: Boolean,
    /** Есть ли встроенное распознавание (robot/robotos) — на него откатываемся без связи. */
    private val builtinAsrAvailable: Boolean = false,
    /** GET /v1/health: true — сервер снова доступен. */
    private val healthCheck: suspend () -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val tailMillis: Long = SPEECH_TAIL_MS,
    private val probeIntervalMillis: Long = HEALTH_PROBE_MS,
    /** Кеш голоса: закешированные предложения идут отдельными фразами. */
    private val cache: PhraseAudioCache? = null,
    /** Текст «не могу сейчас говорить» по языку (фраза из кеша). */
    private val phrases: Phrases? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val cantSpeakIntervalMillis: Long = CANT_SPEAK_INTERVAL_MS,
) : VoiceGateway {

    private val _speechEvents = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
    override val speechEvents: SharedFlow<SpeechEvent> = _speechEvents

    /** Когда последний раз звучало «не могу сейчас говорить». */
    @Volatile private var lastCantSpeak = Long.MIN_VALUE / 2

    /** Экран диагностики открыт: главный экран не получает фраз, автопрослушивание сервера выключено. */
    private val diagnostics = MutableStateFlow(false)

    /** Очередь озвучки: одновременно звучит только одна озвучка (speak/speakStream). */
    private val queue = Mutex()

    /** Номер «Стопа»: озвучка, вставшая в очередь до него, уже не звучит; текущая обрывается. */
    private val stopGeneration = MutableStateFlow(0L)

    /** Сколько вызовов [speak] сейчас играет или ждёт очереди. */
    private val speakingCount = MutableStateFlow(0)

    /** /v1/stt не отвечает из-за связи — пока не пройдёт /v1/health. */
    private val _sttOffline = MutableStateFlow(false)
    val sttOffline: StateFlow<Boolean> = _sttOffline
    private var probeJob: Job? = null

    private val _connection = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    override val connection: SharedFlow<Boolean> = _connection

    /** Кто-то говорит: наша очередь или любой голос сам по себе (системный ответ, диагностика). */
    private val rawSpeaking: Flow<Boolean> =
        combine(speakingCount, builtinAsr.activity, builtinTts.activity, server.activity) { count, a, t, s ->
            count > 0 || a == VoiceActivity.SPEAKING || t == VoiceActivity.SPEAKING || s == VoiceActivity.SPEAKING
        }.distinctUntilChanged()

    /** «Говорю» + [tailMillis] тишины после: в это время микрофон робота слышит сам себя. */
    val speechGuard: StateFlow<Boolean> = rawSpeaking
        .transformLatest { speaking ->
            if (speaking) emit(true) else {
                delay(tailMillis)
                emit(false)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val activity: StateFlow<VoiceActivity> =
        combine(rawSpeaking, builtinAsr.activity, builtinTts.activity, server.activity) { speaking, a, t, s ->
            when {
                speaking -> VoiceActivity.SPEAKING
                VoiceActivity.LISTENING in listOf(a, t, s) -> VoiceActivity.LISTENING
                else -> VoiceActivity.IDLE
            }
        }.stateIn(scope, SharingStarted.Eagerly, VoiceActivity.IDLE)

    override val transcripts: Flow<Transcript> = merge(
        builtinAsr.transcripts.filter { t ->
            if (effectiveAsr() != VoiceSource.BUILTIN) return@filter false
            if (speechGuard.value) {
                Timber.d("builtin ASR result ignored while speaking (final=%s, len=%d)", t.isFinal, t.text.length)
                return@filter false
            }
            true
        },
        // Серверный STT: запись во время озвучки не идёт (setSpeechActive), результаты принимаем.
        server.transcripts,
    ).filter { !diagnostics.value }

    override val utteranceEnded: Flow<Unit> = server.utteranceEnded.filter { !diagnostics.value }

    init {
        scope.launch { speechGuard.collect { server.setSpeechActive(it) } }
        scope.launch {
            combine(settings.config, diagnostics, _sttOffline) { c, d, o -> Triple(c, d, o) }
                .collect { (c, d, o) -> applyAsr(c, d, o) }
        }
        scope.launch {
            server.sttEvents.collect { e ->
                when (e.error?.kind) {
                    null -> if (e.text.isNotEmpty() || e.audioMillis > 0) onSttReachable()
                    ErrorKind.NETWORK, ErrorKind.TIMEOUT -> onSttConnectionLost()
                    else -> Unit
                }
            }
        }
    }

    /** Источник распознавания с учётом отката: без связи серверный ASR заменяется встроенным. */
    fun effectiveAsr(): VoiceSource = effectiveAsr(settings.config.value, _sttOffline.value)

    /** Откат на встроенный ASR без связи — только при [VoiceConfig.builtinFallback]: иначе встроенный ассистент молчит. */
    private fun effectiveAsr(config: VoiceConfig, sttOffline: Boolean): VoiceSource =
        if (config.asr == VoiceSource.SERVER && sttOffline && builtinAsrAvailable && config.builtinFallback) VoiceSource.BUILTIN
        else config.asr

    private fun applyAsr(config: VoiceConfig, diagnosticsOpen: Boolean, sttOffline: Boolean) {
        if (diagnosticsOpen) {
            server.setContinuous(false) // микрофоном управляет экран диагностики
            return
        }
        val effective = effectiveAsr(config, sttOffline)
        val useServer = effective == VoiceSource.SERVER
        Timber.i("voice: %s%s", config.describe(), if (effective != config.asr) " (без связи: ASR=builtin)" else "")
        // TODO ПРОВЕРИТЬ НА РОБОТЕ: отпускает ли встроенный ASR микрофон для нашего AudioRecord.
        micControl.setBuiltinListening(!useServer)
        server.setContinuous(useServer && continuousServerAsr)
    }

    private fun onSttConnectionLost() {
        if (diagnostics.value) return
        Timber.w("server STT unreachable → %s", if (builtinAsrAvailable) "builtin ASR until /health" else "waiting for /health")
        _connection.tryEmit(false)
        if (!_sttOffline.value) _sttOffline.value = true
        if (probeJob?.isActive != true) {
            probeJob = scope.launch {
                while (isActive && _sttOffline.value) {
                    delay(probeIntervalMillis)
                    if (healthCheck()) {
                        Timber.i("server reachable again (/health)")
                        onSttReachable()
                        break
                    }
                }
            }
        }
    }

    private fun onSttReachable() {
        if (!_sttOffline.value) return
        probeJob?.cancel()
        probeJob = null
        _sttOffline.value = false
        _connection.tryEmit(true)
    }

    fun setDiagnosticsOpen(open: Boolean) {
        diagnostics.value = open
    }

    /** Кнопка микрофона: одна фраза через серверный STT. */
    fun pushToTalk() = server.listenOnce()

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        if (phrases.isEmpty()) return true
        val channel = Channel<String>(Channel.UNLIMITED)
        phrases.forEach { channel.trySend(it) }
        channel.close()
        return speakStream(channel, lang)
    }

    /**
     * Одна озвучка в очереди: фразы из [phrases] склеиваются в куски ≤ 300 символов, следующий кусок серверного TTS
     * синтезируется, пока звучит текущий ([SpeechPipeline]). [speechGuard] поднят на всё время вызова — и в промежутках
     * между кусками; хвост тишины — только после последнего.
     */
    override suspend fun speakStream(phrases: ReceiveChannel<String>, lang: Lang, listener: SpeechListener): Boolean {
        val generation = stopGeneration.value
        speakingCount.update { it + 1 }
        try {
            return queue.withLock {
                if (stopGeneration.value != generation) {
                    Timber.d("queued speech dropped after stop")
                    false
                } else {
                    stoppable(generation) { speakNow(phrases, lang, listener, generation) }
                }
            }
        } finally {
            speakingCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /** «Стоп» обрывает сразу всё: проигрывание, синтез следующего куска, ожидание новых фраз. */
    private suspend fun stoppable(generation: Long, block: suspend () -> Boolean): Boolean = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            stopGeneration.first { it != generation }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive() // отменили нас самих — пробрасываем
            false
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun speakNow(
        phrases: ReceiveChannel<String>,
        lang: Lang,
        listener: SpeechListener,
        generation: Long,
    ): Boolean = coroutineScope {
        val pending = PhraseQueue()
        val feeder = launch {
            try {
                for (p in phrases) (cache?.segment(p, lang) ?: listOf(p)).forEach(pending::add)
            } finally {
                pending.close()
            }
        }
        val tracked = object : SpeechListener {
            override fun onChunkStart(chunk: SpeechChunk) {
                _speechEvents.tryEmit(SpeechEvent.AUDIO_START)
                listener.onChunkStart(chunk)
            }

            override fun onChunkDone(chunk: SpeechChunk, completed: Boolean) = listener.onChunkDone(chunk, completed)

            override fun onTtsFirstByte(chunk: SpeechChunk) {
                _speechEvents.tryEmit(SpeechEvent.TTS_FIRST_BYTE)
                listener.onTtsFirstByte(chunk)
            }
        }
        try {
            if (settings.config.value.ttsFor(lang) == VoiceSource.BUILTIN) {
                return@coroutineScope speakBuiltin(pending, lang, tracked, generation)
            }
            when (val r = server.speakQueue(pending, lang, tracked)) {
                ServerSpeakResult.Done -> true
                ServerSpeakResult.Interrupted -> false
                is ServerSpeakResult.Unavailable -> {
                    if (stopGeneration.value != generation) return@coroutineScope false
                    if (settings.config.value.builtinFallback) {
                        Timber.w("server TTS unavailable (%s: %s) -> builtin", r.error.kind, r.error.message)
                        speakBuiltin(pending, lang, tracked, generation)
                    } else {
                        Timber.w("server TTS unavailable (%s: %s) -> subtitles without sound", r.error.kind, r.error.message)
                        speakSilent(pending, lang, listener, generation)
                    }
                }
            }
        } finally {
            feeder.cancel()
        }
    }

    /** Встроенный голос: накопившиеся фразы — одним вызовом (кусок ≤ 300 символов), без пауз между вызовами. */
    private suspend fun speakBuiltin(queue: PhraseQueue, lang: Lang, listener: SpeechListener, generation: Long): Boolean =
        SpeechPipeline.sequential(
            queue = queue,
            speak = { chunk -> builtinTts.speak(listOf(chunk.text), lang) },
            listener = listener,
            isStopped = { stopGeneration.value != generation },
        ) == PipelineResult.Done

    /**
     * Без встроенного голоса: фразы из кеша — из файла, остальные — только субтитрами (держим их на экране время
     * чтения). На первой беззвучной фразе — «не могу сейчас говорить» из кеша (не чаще раза в [cantSpeakIntervalMillis]).
     */
    private suspend fun speakSilent(queue: PhraseQueue, lang: Lang, listener: SpeechListener, generation: Long): Boolean {
        var first = true
        return SpeechPipeline.sequential(
            queue = queue,
            speak = { chunk ->
                val cached = server.playCached(chunk.text, lang)
                if (cached != null) {
                    cached
                } else {
                    val started = now()
                    if (first) {
                        first = false
                        sayCantSpeak(lang)
                    }
                    val hold = silentHoldMillis(chunk.text) - (now() - started)
                    if (hold > 0) delay(hold)
                    true
                }
            },
            listener = listener,
            isStopped = { stopGeneration.value != generation },
        ) == PipelineResult.Done
    }

    private suspend fun sayCantSpeak(lang: Lang) {
        val text = phrases?.get(PhraseKey.CANT_SPEAK, lang) ?: return
        val t = now()
        if (t - lastCantSpeak < cantSpeakIntervalMillis) return
        lastCantSpeak = t
        if (server.playCached(text, lang) == null) Timber.i("no cached 'cant speak' phrase for %s", lang.code)
    }

    /** Короткая фраза из кеша (без субтитров, без сети) — в общей очереди: speechGuard и «Стоп» как у любой озвучки. */
    override suspend fun speakCached(text: String, lang: Lang): Boolean {
        if (settings.config.value.ttsFor(lang) == VoiceSource.BUILTIN || !server.hasCached(text, lang)) return false
        val generation = stopGeneration.value
        speakingCount.update { it + 1 }
        try {
            return queue.withLock {
                if (stopGeneration.value != generation) false
                else stoppable(generation) { server.playCached(text, lang) == true }
            }
        } finally {
            speakingCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /** «Стоп»: замолчать сейчас и выбросить всё, что ждёт в очереди. */
    override fun stopSpeaking() {
        stopGeneration.update { it + 1 }
        builtinTts.stopSpeaking()
        server.stopSpeaking()
        if (builtinAsr !== builtinTts) builtinAsr.stopSpeaking()
    }

    override fun setLanguage(lang: Lang) {
        builtinAsr.setLanguage(lang)
        if (builtinTts !== builtinAsr) builtinTts.setLanguage(lang)
        // Серверному STT — язык экрана: без подсказки live-распознавание путает казахский с турецким.
        server.setLanguage(lang)
    }

    override fun setSessionId(id: String) {
        server.setSessionId(id)
    }

    companion object {
        /** Хвост тишины после озвучки: эхо в комнате и задержка аудиотракта. */
        const val SPEECH_TAIL_MS = 300L
        const val HEALTH_PROBE_MS = 30_000L
        /** «Не могу сейчас говорить» — не чаще раза в столько. */
        const val CANT_SPEAK_INTERVAL_MS = 60_000L

        /** Сколько держать беззвучную фразу на экране: время чтения, 1,2–6 с. */
        fun silentHoldMillis(text: String): Long = (text.length * 45L).coerceIn(1_200L, 6_000L)
    }
}
