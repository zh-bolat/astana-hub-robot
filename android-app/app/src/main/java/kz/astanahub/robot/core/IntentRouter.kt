package kz.astanahub.robot.core

/**
 * Роутинг фразы посетителя (шаг 4.5):
 * where_am_i → «где я»; guide_to → «проводи / апар / take me»; where_is → «где / қайда / where»;
 * look → «что это / бұл не / what is this»; остальное → /ask.
 * [Routed.subject] — фраза без служебных слов (для сопоставления с местом).
 */
object IntentRouter {
    enum class Intent { WHERE_AM_I, GUIDE_TO, WHERE_IS, LOOK, ASK }

    data class Routed(val intent: Intent, val subject: String)

    /** Граница слова для кириллицы: `\b` в java.util.regex по умолчанию понимает только ASCII. */
    private const val W = """(?![\p{L}\p{N}])"""
    private const val B = """(?<![\p{L}\p{N}])"""

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val whereAmI = listOf(
        rx("""${B}где\s+(я|мы)(\s+(сейчас|находимся|нахожусь))?$W"""),
        rx("""${B}(мен|біз)\s+қай\s*(да|жерде)(мын|мыз)?$W"""),
        rx("""${B}where\s+(am\s+i|are\s+we)$W"""),
    )

    private val guide = listOf(
        rx("""${B}(про|от|до)вед(и|ите)$W"""),
        rx("""${B}(про|от)вод(и|ите)$W"""),
        rx("""${B}отвез(и|ите)$W"""),
        rx("""${B}покаж(и|ите)\s+(мне\s+)?дорогу$W"""),
        rx("""${B}(можете|можешь|могли\s+бы)\s+(меня\s+)?(проводить|отвести|довести)$W"""),
        rx("""${B}апар(ыңыз|ыңызшы|шы|ып\s+(бер|салыңыз|жіберіңіз)\S*)?$W"""),
        rx("""${B}алып\s+бар(ыңыз|ыңызшы|шы)?$W"""),
        rx("""${B}жеткізіп\s+(сал|қой|жібер)\S*$W"""),
        rx("""${B}(take|guide|lead|walk|bring)\s+(me|us)$W"""),
        rx("""${B}show\s+(me|us)\s+the\s+way$W"""),
    )

    private val whereIs = listOf(
        rx("""${B}где$W"""),
        rx("""${B}как\s+(пройти|попасть|найти|дойти)$W"""),
        rx("""${B}куда\s+(идти|пройти)$W"""),
        rx("""${B}қай\s*да$W"""),
        rx("""${B}қай\s+жерде$W"""),
        rx("""${B}қалай\s+(барамын|барсам|баруға|жетемін)$W"""),
        rx("""${B}where(\s+is|'s|\s+are|\s+can\s+i\s+find)?$W"""),
        rx("""${B}how\s+(do|can)\s+i\s+get\s+to$W"""),
    )

    private val look = listOf(
        rx("""^\s*(а\s+)?что\s+(это|там|здесь|тут)$W.*"""),
        rx("""^\s*что\s+ты\s+видишь$W.*"""),
        rx("""^\s*(бұл|мынау|анау)\s+не$W.*"""),
        rx("""^\s*не\s+көріп\s+тұрсың$W.*"""),
        rx("""^\s*what(\s+is|'s)\s+(this|that)$W.*"""),
        rx("""^\s*what\s+do\s+you\s+see$W.*"""),
    )

    /** Длинные фразы («Что это за программа Tech Garden?») — вопросы к базе, а не к камере. */
    private const val MAX_LOOK_LENGTH = 40

    fun route(text: String): Routed {
        val t = text.trim()
        return when {
            whereAmI.any { it.containsMatchIn(t) } -> Routed(Intent.WHERE_AM_I, "")
            guide.any { it.containsMatchIn(t) } -> Routed(Intent.GUIDE_TO, strip(t, guide))
            t.length <= MAX_LOOK_LENGTH && look.any { it.matches(t) } -> Routed(Intent.LOOK, t)
            whereIs.any { it.containsMatchIn(t) } -> Routed(Intent.WHERE_IS, strip(t, whereIs))
            else -> Routed(Intent.ASK, t)
        }
    }

    private fun strip(text: String, patterns: List<Regex>): String =
        patterns.fold(text) { acc, r -> r.replace(acc, " ") }.replace(Regex("""\s+"""), " ").trim()
}
