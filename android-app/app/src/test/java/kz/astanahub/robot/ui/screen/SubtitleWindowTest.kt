package kz.astanahub.robot.ui.screen

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleWindowTest {
    private val long = "Astana Hub — международный технопарк IT-стартапов в Астане. Здесь работают сотни компаний и проходят мероприятия."

    @Test
    fun `before speech the first piece is shown`() {
        val w = SubtitleWindow.words(long)
        assertEquals(0..6, SubtitleWindow.window(w, 0))
    }

    @Test
    fun `pieces are up to seven words and break after a sentence end`() {
        val w = SubtitleWindow.words(long)
        assertEquals(0..6, SubtitleWindow.window(w, 7)) // седьмое слово сказано — кусок тот же
        assertEquals(7..7, SubtitleWindow.window(w, 8)) // «Астане.» — конец предложения
        assertEquals(8..14, SubtitleWindow.window(w, 9))
        assertEquals(8..w.lastIndex, SubtitleWindow.window(w, w.size)) // всё сказано — последний кусок
    }

    @Test
    fun `spoken words are lit, the rest are faint`() {
        val faint = Color(0x59FFFFFF)
        val t = SubtitleWindow.text("Раз два три", 2, faint)
        assertEquals("Раз два три", t.text)
        assertEquals(1, t.spanStyles.size)
        assertEquals(faint, t.spanStyles[0].item.color)
        assertEquals("три", t.text.substring(t.spanStyles[0].start, t.spanStyles[0].end))
    }

    @Test
    fun `empty text gives nothing`() {
        assertTrue(SubtitleWindow.text("  ", 0, Color.Gray).text.isEmpty())
    }
}
