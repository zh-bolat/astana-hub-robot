package kz.astanahub.robot.voice

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * Озвучивает фразы по мере прихода (ход агента: SSE `phrase` по одной). Возвращается, когда [phrases]
     * закрыт и всё доиграно, либо озвучка прервана (false). [listener] — какой кусок звучит (субтитры).
     * По умолчанию — по одной фразе; [RoutingVoiceGateway] склеивает их в куски ≤ 300 символов и
     * предзагружает следующий кусок, чтобы не было пауз.
     */
    suspend fun speakStream(
        phrases: ReceiveChannel<String>,
        lang: Lang,
        listener: SpeechListener = SpeechListener.NONE,
    ): Boolean {
        for (p in phrases) {
            val chunk = SpeechChunk(listOf(p))
            listener.onChunkStart(chunk)
            val ok = speak(chunk.phrases, lang)
            listener.onChunkDone(chunk, ok)
            if (!ok) return false
        }
        return true
    }

    fun stopSpeaking()

    /** Язык ASR/TTS. На роботе API для этого в доке AgentOS SDK v0.4.12 нет — см. реализацию. */
    fun setLanguage(lang: Lang)

    /** Сессия разговора (session_id для /v1/stt = session_id экрана). */
    fun setSessionId(id: String) = Unit

    /**
     * Связь с сервером распознавания: false — /v1/stt не прошёл из-за сети/таймаута,
     * true — связь восстановилась (проверка /v1/health). По умолчанию событий нет.
     */
    val connection: Flow<Boolean> get() = emptyFlow()
}
