package kz.astanahub.robot.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kz.astanahub.robot.core.Lang
import timber.log.Timber

/**
 * Голос по [VoiceConfig]: распознавание — встроенное или серверное; синтез — по языку.
 * Серверный TTS недоступен (503 speech_unavailable / `X-Speech-Provider: fake`) → оставшиеся фразы — встроенным.
 */
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
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoiceGateway {

    /** Экран диагностики открыт: главный экран не получает фраз, автопрослушивание сервера выключено. */
    private val diagnostics = MutableStateFlow(false)

    override val transcripts: Flow<Transcript> = merge(
        builtinAsr.transcripts.filter { settings.config.value.asr == VoiceSource.BUILTIN },
        // Серверный STT по кнопке микрофона принимаем всегда.
        server.transcripts,
    ).filter { !diagnostics.value }

    override val activity: StateFlow<VoiceActivity> =
        combine(builtinAsr.activity, builtinTts.activity, server.activity) { a, t, s ->
            when {
                VoiceActivity.SPEAKING in listOf(a, t, s) -> VoiceActivity.SPEAKING
                VoiceActivity.LISTENING in listOf(a, t, s) -> VoiceActivity.LISTENING
                else -> VoiceActivity.IDLE
            }
        }.stateIn(scope, SharingStarted.Eagerly, VoiceActivity.IDLE)

    init {
        scope.launch {
            combine(settings.config, diagnostics) { c, d -> c to d }.collect { (c, d) -> applyAsr(c, d) }
        }
    }

    private fun applyAsr(config: VoiceConfig, diagnosticsOpen: Boolean) {
        if (diagnosticsOpen) {
            server.setContinuous(false) // микрофоном управляет экран диагностики
            return
        }
        val useServer = config.asr == VoiceSource.SERVER
        Timber.i("voice: %s", config.describe())
        // TODO ПРОВЕРИТЬ НА РОБОТЕ: отпускает ли встроенный ASR микрофон для нашего AudioRecord.
        micControl.setBuiltinListening(!useServer)
        server.setContinuous(useServer && continuousServerAsr)
    }

    fun setDiagnosticsOpen(open: Boolean) {
        diagnostics.value = open
    }

    /** Кнопка микрофона: одна фраза через серверный STT. */
    fun pushToTalk() = server.listenOnce()

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        if (settings.config.value.ttsFor(lang) == VoiceSource.BUILTIN) return builtinTts.speak(phrases, lang)
        return when (val r = server.speakDetailed(phrases, lang)) {
            ServerSpeakResult.Done -> true
            ServerSpeakResult.Interrupted -> false
            is ServerSpeakResult.Unavailable -> {
                Timber.w("server TTS unavailable (%s: %s) → builtin", r.error.kind, r.error.message)
                builtinTts.speak(r.remaining, lang)
            }
        }
    }

    override fun stopSpeaking() {
        builtinTts.stopSpeaking()
        server.stopSpeaking()
        if (builtinAsr !== builtinTts) builtinAsr.stopSpeaking()
    }

    override fun setLanguage(lang: Lang) {
        builtinAsr.setLanguage(lang)
        if (builtinTts !== builtinAsr) builtinTts.setLanguage(lang)
        // Серверному STT язык не навязываем: автоопределение ru/kk/en (подсказка — экран диагностики).
    }
}
