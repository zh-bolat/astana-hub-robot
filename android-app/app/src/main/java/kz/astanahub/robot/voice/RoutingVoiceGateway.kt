package kz.astanahub.robot.voice

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
import timber.log.Timber

/**
 * Голос по [VoiceConfig]: распознавание — встроенное или серверное; синтез — по языку.
 * Серверный TTS недоступен (503 speech_unavailable / `X-Speech-Provider: fake`) → оставшиеся фразы — встроенным.
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
) : VoiceGateway {

    /** Экран диагностики открыт: главный экран не получает фраз, автопрослушивание сервера выключено. */
    private val diagnostics = MutableStateFlow(false)

    /** Очередь озвучки: одновременно звучит только одна фраза. */
    private val queue = Mutex()

    /** Номер «Стопа»: озвучка, вставшая в очередь до него, уже не звучит. */
    private val stopGeneration = AtomicLong(0)

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

    private fun effectiveAsr(config: VoiceConfig, sttOffline: Boolean): VoiceSource =
        if (config.asr == VoiceSource.SERVER && sttOffline && builtinAsrAvailable) VoiceSource.BUILTIN else config.asr

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
        val generation = stopGeneration.get()
        speakingCount.update { it + 1 }
        try {
            return queue.withLock {
                if (stopGeneration.get() != generation) {
                    Timber.d("queued speech dropped after stop (%d phrases)", phrases.size)
                    false
                } else {
                    speakNow(phrases, lang, generation)
                }
            }
        } finally {
            speakingCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun speakNow(phrases: List<String>, lang: Lang, generation: Long): Boolean {
        if (settings.config.value.ttsFor(lang) == VoiceSource.BUILTIN) return builtinTts.speak(phrases, lang)
        return when (val r = server.speakDetailed(phrases, lang)) {
            ServerSpeakResult.Done -> true
            ServerSpeakResult.Interrupted -> false
            is ServerSpeakResult.Unavailable -> {
                if (stopGeneration.get() != generation) return false
                Timber.w("server TTS unavailable (%s: %s) → builtin", r.error.kind, r.error.message)
                builtinTts.speak(r.remaining, lang)
            }
        }
    }

    /** «Стоп»: замолчать сейчас и выбросить всё, что ждёт в очереди. */
    override fun stopSpeaking() {
        stopGeneration.incrementAndGet()
        builtinTts.stopSpeaking()
        server.stopSpeaking()
        if (builtinAsr !== builtinTts) builtinAsr.stopSpeaking()
    }

    override fun setLanguage(lang: Lang) {
        builtinAsr.setLanguage(lang)
        if (builtinTts !== builtinAsr) builtinTts.setLanguage(lang)
        // Серверному STT язык не навязываем: автоопределение ru/kk/en (подсказка — экран диагностики).
    }

    override fun setSessionId(id: String) {
        server.setSessionId(id)
    }

    companion object {
        /** Хвост тишины после озвучки: эхо в комнате и задержка аудиотракта. */
        const val SPEECH_TAIL_MS = 300L
        const val HEALTH_PROBE_MS = 30_000L
    }
}
