package kz.astanahub.robot.audio

/** Подготовленный к старту звук (MediaPlayer после prepare). Освобождается [ClipPlayer.play] или [ClipPlayer.release]. */
interface AudioClip

/**
 * Проигрыватель с заранее подготовленными клипами: следующий кусок готовится, пока звучит текущий,
 * и стартует без ожидания (см. SpeechPipeline). Реализация — [Mp3Player]; в тестах — заглушка.
 */
interface ClipPlayer {
    /** Подготовить звук из памяти; null — не удалось (битый MP3, ошибка плеера). */
    suspend fun prepare(bytes: ByteArray): AudioClip?

    /** Сыграть подготовленный клип до конца: true — доиграл; false — [stop], отмена или ошибка. Клип освобождается. */
    suspend fun play(clip: AudioClip): Boolean

    /** Освободить не сыгранный клип. Повторный вызов безопасен. */
    fun release(clip: AudioClip)

    /** Остановить текущее проигрывание (из любого потока). */
    fun stop()
}
