package kz.astanahub.robot.ui

/**
 * Субтитры под лицом робота: текст ответа режется на короткие куски (до [MAX_WORDS] слов, конец предложения —
 * всегда конец куска), слова «загораются» по очереди со скоростью речи.
 *
 * Голосовой канал не сообщает, какое слово звучит сейчас, поэтому темп — оценка по длине слов
 * ([millisPerChar]); паузу после точки тоже учитываем. Чистая логика без Android — покрыта тестом.
 */
class SubtitlePacer(private val millisPerChar: Long = MILLIS_PER_CHAR) {

    data class Word(val text: String, val chunk: Int, val startMillis: Long)

    /** Что показать в облачке: слова текущего куска и сколько из них уже произнесено. */
    data class Frame(val words: List<String>, val spoken: Int)

    fun plan(text: String): List<Word> {
        val tokens = text.split(WHITESPACE).filter { it.isNotBlank() }
        val out = ArrayList<Word>(tokens.size)
        var chunk = 0
        var inChunk = 0
        var at = 0L
        for (token in tokens) {
            out += Word(token, chunk, at)
            at += (token.length + 1) * millisPerChar
            inChunk++
            val sentenceEnd = token.last() in SENTENCE_END
            if (sentenceEnd) at += SENTENCE_PAUSE_MILLIS
            if (inChunk >= MAX_WORDS || sentenceEnd) {
                chunk++
                inChunk = 0
            }
        }
        return out
    }

    /** Кадр на момент [elapsedMillis] от начала речи; null — текста нет. */
    fun frameAt(plan: List<Word>, elapsedMillis: Long): Frame? {
        if (plan.isEmpty()) return null
        val current = plan.indexOfLast { it.startMillis <= elapsedMillis }.coerceAtLeast(0)
        val chunk = plan[current].chunk
        val words = plan.filter { it.chunk == chunk }
        val firstIndex = plan.indexOfFirst { it.chunk == chunk }
        return Frame(words.map { it.text }, current - firstIndex + 1)
    }

    /** Когда закончится последнее слово (для остановки таймера). */
    fun durationMillis(plan: List<Word>): Long =
        plan.lastOrNull()?.let { it.startMillis + (it.text.length + 1) * millisPerChar } ?: 0L

    companion object {
        const val MAX_WORDS = 7
        /** ~14 знаков в секунду: средний темп синтеза речи робота. */
        const val MILLIS_PER_CHAR = 70L
        const val SENTENCE_PAUSE_MILLIS = 350L
        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_END = charArrayOf('.', '!', '?', '…', ':', ';')
    }
}
