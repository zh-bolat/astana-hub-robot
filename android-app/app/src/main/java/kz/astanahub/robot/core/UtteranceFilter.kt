package kz.astanahub.robot.core

import kz.astanahub.robot.places.TextNormalizer

/** Контекст фильтра шума: робот только что задал вопрос (тогда «да/нет» — ответ) и слова из названий мест. */
data class NoiseContext(
    val robotAskedQuestion: Boolean = false,
    /** Нормализованные ([TextNormalizer]) названия мест из `/v1/places` и их слова. */
    val placeWords: Set<String> = emptySet(),
)

/** Почему реплика признана шумом (в лог и счётчики — без текста). */
enum class NoiseReason { EMPTY, MARKS_ONLY, INTERJECTION, FUNCTION_WORDS, ACK_WITHOUT_QUESTION, SINGLE_WORD }

/**
 * Фильтр посторонних реплик.
 * - Во всех фазах (и в покое): [noiseReason] — шум распознавания (междометия RU/KZ/EN, пометки «(смеётся)» / «[музыка]»,
 *   только знаки и цифры, одно слово вне белого списка, «да/нет» без вопроса робота) в `/v1/chat` не уходит.
 * - Пока робот думает / говорит / ведёт: принимаем только явную команду «стоп» или новую содержательную фразу
 *   (≥ [MIN_WORDS] слов без междометий).
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

    // ---------------------------------------------------------------------------------------------
    // Шум вместо реплики (во всех фазах).
    // ---------------------------------------------------------------------------------------------

    /** Пометки распознавателя: «(смеётся)», «[музыка]», «{шум}», «*кашляет*», «<noise>», «♪». */
    private val marks = Regex("""\([^)]*\)|\[[^\]]*]|\{[^}]*}|\*[^*]*\*|<[^>]*>|[♪♫]+""")

    /** Междометия после схлопывания повторов букв («мммм» → «м», «нууу» → «ну», «hmmm» → «hm»). */
    private val fillerBases = setOf(
        // ru
        "м", "мх", "мхм", "хм", "гм", "ну", "угу", "ага", "э", "эм", "эх", "эй", "ах", "ох", "ой", "ай", "уф", "фу", "ух",
        "а", "о", "у", "ы", "оу", "ау", "эээ", "мда", "ммда", "тсс", "кхм", "кхе", "бр", "фух", "ого", "вау", "упс",
        // kk (после свёртки: «әй» → «ай», «уһ» → «ух»)
        "апырай", "ойбай", "ойпырай", "па", "пай",
        // en
        "uh", "um", "uhm", "hm", "mm", "m", "mhm", "ah", "oh", "eh", "er", "erm", "huh", "aha", "uhhuh", "ahh",
        "wow", "oops", "ooh", "oo", "ow", "ugh", "shh", "psst", "hmm",
    )

    /** Смех: «ха-ха», «хахаха», «хи-хи», «hahaha», «hehe», «lol». */
    private val laughter = Regex("""(х[аеиы])+х?|(h[ae]){2,}h?|hah|лол|lol|ахах(а|ах)*|axax""")

    /** Описания звуков без скобок («Смеётся.»): так их иногда возвращает STT. */
    private val soundWords = setOf(
        "смеется", "смеются", "смех", "смешок", "хохот", "хохочет", "кашляет", "кашель", "покашливает", "вздыхает",
        "вздох", "музыка", "аплодисменты", "шум", "шумы", "неразборчиво", "неразборчивая", "неразборчивый", "тишина",
        "плачет", "чихает", "шепот", "шепчет", "стук", "звонок",
        "кулки", "куледи", "жотел", // kk: күлкі, күледі, жөтел
        "laughs", "laughter", "laughing", "laugh", "chuckles", "giggles", "music", "applause", "clapping", "noise",
        "inaudible", "unintelligible", "indistinct", "coughs", "cough", "coughing", "sighs", "sigh", "silence",
        "crosstalk", "background", "foreign", "sneezes", "whispering", "blank", "audio",
    )

    /** Ответы «да/нет/хорошо» — осмысленны, только если робот только что задал вопрос. */
    private val ackWords = setOf(
        "да", "нет", "неа", "конечно", "хорошо", "ладно", "давай", "давайте", "ок", "окей", "согласен", "согласна",
        "иа", "ия", "жок", "жарайды", "болады", "макул", "кеттик",
        "yes", "no", "yeah", "yep", "yup", "nope", "nah", "sure", "ok", "okay", "alright",
    )

    /** Служебные слова: сами по себе — не обращение к роботу («да ну его», «вот это», «Да, я.»). */
    private val functionWords = setOf(
        // ru
        "я", "ты", "он", "она", "оно", "мы", "вы", "они", "его", "ее", "их", "им", "ему", "ей", "меня", "тебя", "нас", "вас",
        "мне", "тебе", "это", "этот", "эта", "эти", "то", "тот", "та", "те", "вот", "так", "же", "ли", "бы", "и", "но",
        "не", "ни", "уж", "ведь", "вон", "там", "тут", "все", "еще", "уже", "типа", "короче", "блин", "значит", "просто",
        "сам", "сама", "ага", "или", "то", "чего", "какой", "такой", "ж",
        // kk
        "мен", "сен", "ол", "биз", "сиз", "олар", "бул", "сол", "осы", "мына", "гой", "ма", "ме", "ба", "бе", "пе",
        "жане", "бирак", "енди", "де", "та", "те",
        // en
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "this", "that", "the", "a", "an",
        "and", "but", "or", "so", "well", "just", "like", "s", "t", "m", "re", "ll", "ve", "d", "is", "am", "are",
        "was", "of", "to", "not", "too", "right", "now", "then", "there",
    )

    /** Осмысленные одиночные слова: приветствие, спасибо, прощание, стоп, помощь. */
    private val singleWordWhitelist = setOf(
        "привет", "приветик", "здравствуйте", "здравствуй", "здрасте", "здрасьте", "добрый",
        "салам", "салем", "салеметсиз", "армысыз",
        "hello", "hi", "hey", "greetings",
        "спасибо", "благодарю", "рахмет", "thanks", "thank", "thx",
        "пока", "досвидания", "bye", "goodbye", "саубол",
        "помогите", "помощь", "комек", "help", "меню", "menu",
    ) + stopWords

    /** Короткие вопросы из служебных слов («Бұл не?» — «Что это?» по-казахски: «не» = «что»). */
    private val meaningfulPhrases = setOf("бул не", "не бул", "мынау не", "мына не", "бул кандай", "ол не")

    /**
     * Шум вместо реплики? null — осмысленная реплика (отправляем), иначе причина (в лог, без текста).
     * Название места из `/v1/places` — всегда осмысленно, даже одним словом.
     */
    fun noiseReason(text: String, context: NoiseContext = NoiseContext()): NoiseReason? {
        if (text.isBlank()) return NoiseReason.EMPTY
        val stripped = marks.replace(text, " ")
        val hadMarks = marks.containsMatchIn(text)
        val tokens = TextNormalizer.tokens(stripped).filter { t -> !t.all { it.isDigit() } }
        if (tokens.isEmpty()) return if (hadMarks) NoiseReason.MARKS_ONLY else NoiseReason.EMPTY

        if (tokens.joinToString(" ") in meaningfulPhrases) return null
        if (context.placeWords.isNotEmpty()) {
            val joined = tokens.joinToString(" ")
            if (joined in context.placeWords) return null
            if (tokens.any { it in context.placeWords && !isFiller(it) && it !in functionWords && it !in ackWords }) return null
        }

        val words = tokens.filter { !isFiller(it) && it !in soundWords }
        if (words.isEmpty()) {
            return if (tokens.all { it in soundWords }) NoiseReason.MARKS_ONLY else NoiseReason.INTERJECTION
        }
        val content = words.filter { it !in functionWords && it !in ackWords }
        if (content.isEmpty()) {
            if (words.any { it in ackWords }) {
                return if (context.robotAskedQuestion) null else NoiseReason.ACK_WITHOUT_QUESTION
            }
            return NoiseReason.FUNCTION_WORDS
        }
        if (words.size == 1 && words[0] !in singleWordWhitelist) return NoiseReason.SINGLE_WORD
        return null
    }

    fun isNoise(text: String, context: NoiseContext = NoiseContext()): Boolean = noiseReason(text, context) != null

    private fun isFiller(token: String): Boolean {
        val squeezed = squeeze(token)
        return squeezed in fillerBases || token in fillerBases || laughter.matches(token)
    }

    /** «мммм» → «м», «нууу» → «ну», «ээээ» → «э», «hmmm» → «hm». */
    private fun squeeze(token: String): String {
        val sb = StringBuilder(token.length)
        for (c in token) if (sb.isEmpty() || sb[sb.length - 1] != c) sb.append(c)
        return sb.toString()
    }
}
