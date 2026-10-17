package kz.astanahub.robot.voice

import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kz.astanahub.robot.audio.Mp3Player
import kz.astanahub.robot.audio.VadConfig
import kz.astanahub.robot.audio.WavEncoder
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
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
 * микрофон → VAD → WAV → /v1/stt; фразы → /v1/tts (следующая грузится, пока играет текущая) → MediaPlayer.
 * Работает на эмуляторе (микрофон хоста) и на роботе.
 */
class ServerVoiceGateway(
    private val backend: BackendApi,
    private val recorder: MicRecorder,
    private val player: Mp3Player,
    private val vadConfig: VadConfig = VadConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoiceGateway {

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 16)
    override val transcripts: Flow<Transcript> = _transcripts

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    private val _sttEvents = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    val sttEvents: SharedFlow<SttEvent> = _sttEvents

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    /** session_id для /v1/stt: тот же, что у экрана (RoutingVoiceGateway.setSessionId). */
    @Volatile var sttSessionId: String = UUID.randomUUID().toString()

    /** Подсказка языка для STT; null — автоопределение на сервере. */
    @Volatile var sttLangHint: Lang? = null

    /** Своя озвучка (speakDetailed). */
    private val ownSpeaking = MutableStateFlow(false)
    /** Любая озвучка робота + хвост тишины — от RoutingVoiceGateway. */
    private val externalSpeech = MutableStateFlow(false)
    private val muted = combine(ownSpeaking, externalSpeech) { own, ext -> own || ext }
    private fun isMuted() = ownSpeaking.value || externalSpeech.value
    @Volatile private var stopRequested = false

    private var listenJob: Job? = null
    @Volatile private var continuous = false

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
        _activity.value = VoiceActivity.LISTENING
        val result = try {
            cancelOnSpeech { recorder.recordUtterance(vadConfig) { _micLevel.value = it } }
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
                val audioMillis = result.pcm.size * 1000L / vadConfig.sampleRate
                _transcripts.tryEmit(Transcript("…", isFinal = false))
                val started = SystemClock.elapsedRealtime()
                val stt = backend.stt(WavEncoder.encode(result.pcm, vadConfig.sampleRate), sttLangHint, sttSessionId)
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

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean =
        speakDetailed(phrases, lang) == ServerSpeakResult.Done

    /** Озвучка с предзагрузкой следующей фразы. */
    suspend fun speakDetailed(phrases: List<String>, lang: Lang): ServerSpeakResult {
        if (phrases.isEmpty()) return ServerSpeakResult.Done
        stopRequested = false
        ownSpeaking.value = true
        _activity.value = VoiceActivity.SPEAKING
        var next: Deferred<ApiResult<ByteArray>> = scope.async { backend.tts(phrases[0], lang) }
        try {
            for (i in phrases.indices) {
                val audio = next.await()
                if (i + 1 < phrases.size) next = scope.async { backend.tts(phrases[i + 1], lang) }
                if (stopRequested) return ServerSpeakResult.Interrupted
                when (audio) {
                    is ApiResult.Failure -> {
                        Timber.w("TTS failed: %s", audio.error)
                        return ServerSpeakResult.Unavailable(phrases.drop(i), audio.error)
                    }
                    is ApiResult.Success -> if (!player.play(audio.value) || stopRequested) {
                        return ServerSpeakResult.Interrupted
                    }
                }
            }
            return ServerSpeakResult.Done
        } finally {
            next.cancel()
            ownSpeaking.value = false
            _activity.value = VoiceActivity.IDLE
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
        const val PTT_WAIT_FOR_SILENCE_MS = 2_000L
    }
}
