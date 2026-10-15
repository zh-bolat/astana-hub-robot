package kz.astanahub.robot.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.core.Lang

/** Результат распознавания речи. Промежуточные результаты — для субтитров, финальные — вопрос. */
/** [lang] — язык, определённый распознавателем (серверный STT), если известен. */
data class Transcript(val text: String, val isFinal: Boolean, val lang: Lang? = null)

/** Что сейчас делает голосовой канал (для индикатора на экране). */
enum class VoiceActivity { IDLE, LISTENING, SPEAKING }

/**
 * Голос робота: ASR (входящий) и TTS (исходящий).
 * Робот — AgentOS SDK; эмулятор — заглушка, вопрос вводится текстом на экране.
 */
interface VoiceGateway {
    /** Поток распознанных фраз посетителя. */
    val transcripts: Flow<Transcript>

    val activity: StateFlow<VoiceActivity>

    /**
     * Озвучивает фразы по очереди. Каждая — ≤ 300 символов и без ссылок (см. PhraseSplitter).
     * Возвращает false, если озвучка прервана (посетитель заговорил, stopSpeaking, ошибка TTS).
     */
    suspend fun speak(phrases: List<String>, lang: Lang): Boolean

    fun stopSpeaking()

    /** Язык ASR/TTS. На роботе API для этого в доке AgentOS SDK v0.4.12 нет — см. реализацию. */
    fun setLanguage(lang: Lang)
}
