package kz.astanahub.robot.agent

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.OnAgentStatusChangedListener
import com.ainirobot.agent.OnTranscribeListener
import com.ainirobot.agent.base.Transcription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import timber.log.Timber

/**
 * Голос через AgentOS: ASR — OnTranscribeListener (раздел 4.3), статус — OnAgentStatusChangedListener (4.4),
 * TTS — AgentCore.ttsSync (4.6). Вся реализация — ПРОВЕРИТЬ НА РОБОТЕ.
 */
class AgentVoiceGateway : VoiceGateway {

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 16)
    override val transcripts: Flow<Transcript> = _transcripts

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    /** Колбэки SDK приходят в рабочем потоке; tryEmit потокобезопасен. */
    val transcribeListener = object : OnTranscribeListener {
        override fun onASRResult(transcription: Transcription): Boolean {
            val text = transcription.text.orEmpty()
            if (text.isNotBlank()) _transcripts.tryEmit(Transcript(text, transcription.final))
            // false — не перехватываем, системные субтитры остаются (решить в шаге 1.6/7.5).
            return false
        }

        override fun onTTSResult(transcription: Transcription): Boolean = false
    }

    val statusListener = object : OnAgentStatusChangedListener {
        override fun onStatusChanged(status: String, message: String?): Boolean {
            if (_activity.value != VoiceActivity.SPEAKING) {
                _activity.value = when (status) {
                    "listening" -> VoiceActivity.LISTENING
                    else -> VoiceActivity.IDLE
                }
            }
            return false
        }
    }

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        _activity.value = VoiceActivity.SPEAKING
        try {
            for (phrase in phrases) {
                // Таймаут ttsSync — на всё воспроизведение фразы, с запасом по длине.
                val result = AgentCore.ttsSync(phrase, TTS_BASE_TIMEOUT_MS + phrase.length * TTS_MS_PER_CHAR)
                if (!result.isSuccess) {
                    Timber.i("TTS interrupted/failed: status=%d", result.status)
                    return false
                }
            }
            return true
        } finally {
            _activity.value = VoiceActivity.IDLE
        }
    }

    override fun stopSpeaking() {
        AgentCore.stopTTS()
    }

    override fun setLanguage(lang: Lang) {
        // В AgentOS SDK v0.4.12 нет API выбора языка ASR/TTS (см. docs/vendor, раздел 4).
        // Ждём ответа OrionStar про RU/KZ; решение — шаг 2.9.
        Timber.i("setLanguage(%s): no AgentOS API, ignored", lang.code)
    }

    private companion object {
        const val TTS_BASE_TIMEOUT_MS = 10_000L
        const val TTS_MS_PER_CHAR = 150L
    }
}
