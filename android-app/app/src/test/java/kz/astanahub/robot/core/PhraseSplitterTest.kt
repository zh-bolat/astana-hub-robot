package kz.astanahub.robot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseSplitterTest {

    @Test
    fun `short text is one phrase`() {
        assertEquals(listOf("Привет. Как дела?"), PhraseSplitter.split("Привет. Как дела?"))
    }

    @Test
    fun `links are removed`() {
        val out = PhraseSplitter.split("Подробнее на https://astanahub.com/ru/events и www.example.kz сегодня.")
        assertEquals(listOf("Подробнее на и сегодня."), out)
    }

    @Test
    fun `long text is split into phrases of at most 300 chars`() {
        val sentence = "Это довольно длинное предложение про Astana Hub и его программы. "
        val text = sentence.repeat(20)
        val out = PhraseSplitter.split(text)
        assertTrue(out.size > 1)
        out.forEach { assertTrue(it.length <= PhraseSplitter.MAX_PHRASE_LENGTH) }
        assertEquals(text.trim().replace(Regex("\\s+"), " "), out.joinToString(" "))
    }

    @Test
    fun `sentence without punctuation longer than limit is wrapped by words`() {
        val text = (1..120).joinToString(" ") { "слово$it" }
        val out = PhraseSplitter.split(text)
        out.forEach { assertTrue(it.length <= 300) }
        assertEquals(text, out.joinToString(" "))
    }

    @Test
    fun `giant token is cut hard`() {
        val out = PhraseSplitter.split("a".repeat(700))
        assertEquals(listOf(300, 300, 100), out.map { it.length })
    }

    @Test
    fun `blank and link-only text gives nothing`() {
        assertTrue(PhraseSplitter.split("   ").isEmpty())
        assertTrue(PhraseSplitter.split("https://x.y/z").isEmpty())
        assertFalse(PhraseSplitter.normalize(listOf("ok")).isEmpty())
    }
}
