package kz.astanahub.robot.places

import kotlin.math.max
import kotlin.math.min
import kz.astanahub.robot.backend.Place

/**
 * Сопоставление фразы посетителя с местом по names (ru/kk/en), synonyms и map_name.
 * Точное вхождение > совпадение основы (падежи: «ресепшена», «коворкинге») > нечёткое (опечатки ASR).
 */
object PlaceMatcher {

    const val MIN_SCORE = 0.8

    /** Служебные слова вопросов «где / проводи», не участвующие в сопоставлении. */
    private val stopWords = setOf(
        // ru
        "где", "находится", "находятся", "здесь", "тут", "у", "вас", "а", "как", "пройти", "найти", "дойти",
        "попасть", "мне", "нам", "в", "во", "на", "к", "ко", "до", "пожалуйста", "проводи", "проводите",
        "проведи", "проведите", "отведи", "отведите", "покажи", "покажите", "дорогу", "можно", "есть", "ли",
        "мне", "нужно", "нужен", "нужна", "ищу", "подскажите", "подскажи", "skazhite",
        // kk (после свёртки букв)
        "кайда", "кай", "жерде", "орналаскан", "калай", "барамын", "барам", "апар", "апарыныз", "апарып",
        "алып", "барыныз", "жеткизип", "жеткизиниз", "корсет", "корсетиниз", "бар", "ма", "ме", "маган",
        "бизге", "осы", "мына",
        // en
        "where", "is", "are", "the", "a", "an", "can", "i", "find", "how", "do", "get", "to", "take", "me",
        "us", "guide", "lead", "show", "way", "please", "s", "there", "bring", "located",
    )

    data class Match(val place: Place, val score: Double, val term: String)

    fun contentTokens(text: String): List<String> = TextNormalizer.tokens(text).filter { it !in stopWords }

    fun match(query: String, places: List<Place>): Match? {
        val qTokens = contentTokens(query)
        if (qTokens.isEmpty()) return null
        val qJoined = " " + qTokens.joinToString(" ") + " "
        var best: Match? = null
        for (place in places) {
            val terms = (place.names.values + place.synonyms + place.mapName)
                .map { raw -> contentTokens(raw).ifEmpty { TextNormalizer.tokens(raw) }.joinToString(" ") }
                .filter { it.isNotEmpty() }
                .distinct()
            for (term in terms) {
                val score = scoreTerm(term, qTokens, qJoined)
                val current = best
                if (score >= MIN_SCORE && (current == null || score > current.score ||
                        (score == current.score && term.length > current.term.length))
                ) {
                    best = Match(place, score, term)
                }
            }
        }
        return best
    }

    private fun scoreTerm(term: String, qTokens: List<String>, qJoined: String): Double {
        if (qJoined.contains(" $term ")) return 1.0 + term.length / 1000.0 // длиннее — точнее («конференц зал» > «зал»)
        val tTokens = term.split(' ')
        val sims = tTokens.map { t -> qTokens.maxOf { q -> similarity(t, q) } }
        return sims.average()
    }

    /** 1 — равны; 0.9 — общая основа (падежные окончания); иначе 1 − Левенштейн/длина. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val shorter = min(a.length, b.length)
        if (shorter >= 4) {
            val prefix = a.commonPrefixWith(b).length
            if (prefix >= max(4, max(a.length, b.length) - 3) && prefix >= shorter - 1) return 0.9
        }
        if (shorter < 4) {
            // Короткие слова: только окончание («зал» → «зале», «зала»), без опечаток.
            val longer = if (a.length >= b.length) a else b
            val short = if (a.length >= b.length) b else a
            return if (shorter == 3 && longer.startsWith(short) && longer.length - short.length <= 2) 0.85 else 0.0
        }
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    internal fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
