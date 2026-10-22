package kz.astanahub.robot.ui

/**
 * Эмодзи настроения, которое добавляется в конец субтитров, когда робот договорил ответ.
 * Правила — как в HTML-прототипе: первое совпадение по ключевым словам (ru/kk/en), иначе 🙂.
 */
object MoodEmoji {

    private val rules: List<Pair<Regex, String>> = listOf(
        "извин|не расслыш|не понял|кешір|sorry|didn.t catch|повторит" to "🤔",
        "привет|здравств|сәлем|hello|welcome|добро пожал" to "👋",
        "кофе|coffee|кафе|cafe|еда|food|обед" to "☕",
        "wi-?fi|парол|password|интернет" to "📶",
        "туалет|wc|restroom|дәретхана" to "🚻",
        "этаж|налево|направо|прямо|left|right|floor|қабат|покажу|провожу|follow" to "🧭",
        "мероприят|событи|event|ивент|митап|хакатон" to "🎉",
        "стартап|startup|резидент|resident|инвест" to "🚀",
        "хороший вопрос|good question|жақсы сұрақ" to "😊",
    ).map { (pattern, emoji) -> Regex(pattern) to emoji }

    const val DEFAULT = "🙂"

    fun forText(text: String?): String {
        val lower = text.orEmpty().lowercase()
        return rules.firstOrNull { (re, _) -> re.containsMatchIn(lower) }?.second ?: DEFAULT
    }
}
