package kz.astanahub.robot.ui.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Субтитры как в прототипе: фраза режется на короткие куски — до 7 слов, разрыв и после конца предложения
 * (`.!?:;`); на экране один кусок за раз (тот, где звучит текущее слово), слова в нём зажигаются по мере речи.
 * Так длинная фраза не вылезает за пузырь и не наезжает на кнопки лица.
 */
internal object SubtitleWindow {
    const val MAX_WORDS = 7

    fun words(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotBlank() }

    /** Индексы слов текущего куска: того, где последнее сказанное слово ([spoken] − 1; до речи — первый кусок). */
    fun window(words: List<String>, spoken: Int): IntRange {
        if (words.isEmpty()) return IntRange.EMPTY
        val at = (spoken - 1).coerceIn(0, words.size - 1)
        var start = 0
        var n = 0
        for (i in words.indices) {
            n++
            val end = n >= MAX_WORDS || words[i].last() in ENDS
            if (i >= at && (end || i == words.lastIndex)) return start..i
            if (end) {
                start = i + 1
                n = 0
            }
        }
        return start..words.lastIndex
    }

    /** Текущий кусок: сказанные слова — цветом текста, ещё не сказанные — [faint]. */
    fun text(phrase: String, spoken: Int, faint: Color): AnnotatedString {
        val words = words(phrase)
        val range = window(words, spoken)
        return buildAnnotatedString {
            for (i in range) {
                if (i > range.first) append(' ')
                if (i < spoken) append(words[i]) else withStyle(SpanStyle(color = faint)) { append(words[i]) }
            }
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val ENDS = ".!?:;"
}
