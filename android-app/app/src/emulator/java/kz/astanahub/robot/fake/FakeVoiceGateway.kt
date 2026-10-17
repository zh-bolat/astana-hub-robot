package kz.astanahub.robot.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.core.LogSafe
import timber.log.Timber

/**
 * Голос-заглушка: распознавания нет (вопрос вводится текстом на экране),
 * «озвучка» — пауза пропорционально длине фразы, чтобы был виден индикатор «говорю».
 */
class FakeVoiceGateway(private val millisPerChar: Long = 35) : VoiceGateway {

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
    override val transcripts: Flow<Transcript> = _transcripts

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    @Volatile private var stopRequested = false

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        stopRequested = false
        _activity.value = VoiceActivity.SPEAKING
        try {
            for (phrase in phrases) {
                Timber.i("FAKE TTS [%s]: %s", lang.code, LogSafe.redact(phrase))
                var left = (phrase.length * millisPerChar).coerceIn(300, 6_000)
                while (left > 0) {
                    if (stopRequested) return false
                    delay(100)
                    left -= 100
                }
            }
            return true
        } finally {
            _activity.value = VoiceActivity.IDLE
        }
    }

    override fun stopSpeaking() {
        stopRequested = true
    }

    override fun setLanguage(lang: Lang) {
        Timber.i("FAKE voice language: %s", lang.code)
    }

    /** Для отладки: имитировать распознанную фразу. */
    fun emulateSpeech(text: String) {
        _transcripts.tryEmit(Transcript(text, isFinal = true))
    }
}
