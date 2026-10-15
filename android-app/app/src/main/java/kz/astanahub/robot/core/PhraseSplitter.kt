package kz.astanahub.robot.core

/**
 * Готовит текст для TTS робота: убирает ссылки и режет на фразы не длиннее [MAX_PHRASE_LENGTH]
 * (ограничение AgentOS TTS). Сервер уже присылает `phrases`, это страховка для локальных фраз
 * и на случай, если сервер нарушит лимит.
 */
object PhraseSplitter {
    const val MAX_PHRASE_LENGTH = 300

    private val urlRegex = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")
    private val spaces = Regex("""\s+""")

    fun stripLinks(text: String): String =
        urlRegex.replace(text, "").replace(spaces, " ").trim()

    fun split(text: String, max: Int = MAX_PHRASE_LENGTH): List<String> {
        val clean = stripLinks(text)
        if (clean.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in clean.split(sentenceEnd)) {
            for (piece in hardWrap(sentence, max)) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > max) {
                    result += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** Нормализует готовый список фраз (например, от сервера): без ссылок, каждая ≤ max. */
    fun normalize(phrases: List<String>, max: Int = MAX_PHRASE_LENGTH): List<String> =
        phrases.flatMap { split(it, max) }

    /** Режет слишком длинное предложение по пробелам, в крайнем случае — посимвольно. */
    private fun hardWrap(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val out = mutableListOf<String>()
        val line = StringBuilder()
        for (word in sentence.split(' ')) {
            var w = word
            while (w.length > max) {
                if (line.isNotEmpty()) { out += line.toString(); line.clear() }
                out += w.take(max)
                w = w.drop(max)
            }
            if (line.isNotEmpty() && line.length + 1 + w.length > max) {
                out += line.toString(); line.clear()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(w)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }
}
