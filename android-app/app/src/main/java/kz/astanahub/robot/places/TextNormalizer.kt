package kz.astanahub.robot.places

import java.util.Locale

/** Нормализация для сопоставления мест: регистр, ё, казахские буквы, пунктуация, дефисы. */
object TextNormalizer {

    /** Казахские буквы → ближайшие русские: «асхана», «әжетхана» ≈ «ажетхана», ASR часто путает. */
    private val kazakhFold = mapOf(
        'ә' to 'а', 'ғ' to 'г', 'қ' to 'к', 'ң' to 'н', 'ө' to 'о',
        'ұ' to 'у', 'ү' to 'у', 'һ' to 'х', 'і' to 'и', 'ё' to 'е',
    )

    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (c in lower) sb.append(kazakhFold[c] ?: c)
        return sb.toString().replace(nonWord, " ").trim()
    }

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }
}
