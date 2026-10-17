package kz.astanahub.robot.core

import kz.astanahub.robot.places.TextNormalizer

/**
 * Фильтр посторонних реплик, пока робот думает / говорит / ведёт:
 * принимаем только явную команду «стоп» или новую содержательную фразу (≥ [MIN_WORDS] слов без междометий).
 * Слова сравниваются после [TextNormalizer] (нижний регистр, казахские буквы свёрнуты: «тоқта» → «токта»).
 */
object UtteranceFilter {

    const val MIN_WORDS = 3

    private val stopWords = setOf(
        // ru
        "стоп", "стой", "стойте", "хватит", "достаточно", "остановись", "остановитесь", "остановиться",
        "замолчи", "замолчите", "перестань", "перестаньте", "прекрати", "прекратите", "отмена", "тихо",
        // kk (после свёртки букв)
        "токта", "токтаныз", "токтат", "токтатыныз", "жетеди", "болды", "тыныш",
        // en
        "stop", "enough", "cancel", "quiet", "halt",
    )

    private val stopPhrases = setOf("be quiet", "shut up", "stop it", "that s enough", "that is enough")

    /** Вежливые и обращения: «Робот, стоп, пожалуйста» — всё ещё команда. */
    private val politeWords = setOf(
        "пожалуйста", "робот", "эй", "ну", "все", "please", "robot", "hey", "now", "отинемин", "отинем", "ал",
    )

    private val interjections = setOf(
        "угу", "ага", "да", "нет", "мм", "ммм", "мхм", "хм", "ну", "э", "ээ", "эээ", "а", "аа", "ааа", "о", "ой",
        "ок", "окей", "ладно", "так", "вот", "иа", "ия", "жок", "иә", "ok", "okay", "yes", "yeah", "yep", "no",
        "uh", "um", "umm", "hmm", "mm", "ah", "oh", "huh", "aha",
    )

    fun words(text: String): List<String> = TextNormalizer.tokens(text)

    fun isStopCommand(text: String): Boolean {
        val tokens = words(text).filter { it !in politeWords }
        if (tokens.isEmpty()) return false
        if (tokens.joinToString(" ") in stopPhrases) return true
        return tokens.size <= MAX_STOP_TOKENS && tokens.all { it in stopWords }
    }

    /** Новая фраза, ради которой стоит прервать текущее действие. */
    fun isSubstantial(text: String): Boolean = words(text).count { it !in interjections } >= MIN_WORDS

    private const val MAX_STOP_TOKENS = 3
}
