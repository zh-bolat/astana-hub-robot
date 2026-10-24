package kz.astanahub.robot.audio

/** Сколько ждать докачки потока (и байтов в MediaDataSource.readAt), прежде чем считать его оборванным. */
const val STREAM_WAIT_MILLIS = 10_000L

/** Подготовленный к старту звук (MediaPlayer после prepare). Освобождается [ClipPlayer.play] или [ClipPlayer.release]. */
interface AudioClip

/**
 * Проигрыватель с заранее подготовленными клипами: следующий кусок готовится, пока звучит текущий,
 * и стартует без ожидания (см. SpeechPipeline). Реализация — [Mp3Player]; в тестах — заглушка.
 */
interface ClipPlayer {
    /** Подготовить звук из памяти; null — не удалось (битый MP3, ошибка плеера). */
    suspend fun prepare(bytes: ByteArray): AudioClip?

    /**
     * Подготовить звук, который ещё докачивается (потоковый `/v1/tts`): плеер стартует с первых байт.
     * По умолчанию — дождаться всех байт и [prepare] (заглушки, тесты).
     */
    suspend fun prepareStream(audio: GrowingAudio): AudioClip? {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            audio.awaitAll(STREAM_WAIT_MILLIS)
        } ?: return null
        if (bytes.isEmpty()) return null
        return prepare(bytes)
    }

    /** Сыграть подготовленный клип до конца: true — доиграл; false — [stop], отмена или ошибка. Клип освобождается. */
    suspend fun play(clip: AudioClip): Boolean

    /** Освободить не сыгранный клип. Повторный вызов безопасен. */
    fun release(clip: AudioClip)

    /** Остановить текущее проигрывание (из любого потока). */
    fun stop()
}
