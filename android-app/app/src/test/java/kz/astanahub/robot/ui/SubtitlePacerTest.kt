package kz.astanahub.robot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePacerTest {

    private val pacer = SubtitlePacer(millisPerChar = 10)

    @Test
    fun `sentence end closes a chunk`() {
        val plan = pacer.plan("Раз два. Три")
        assertEquals(listOf(0, 0, 1), plan.map { it.chunk })
    }

    @Test
    fun `long sentence is split every seven words`() {
        val plan = pacer.plan((1..10).joinToString(" ") { "w$it" })
        assertEquals(List(7) { 0 } + List(3) { 1 }, plan.map { it.chunk })
    }

    @Test
    fun `words light up one by one within the current chunk`() {
        val plan = pacer.plan("ab cd. ef")
        // ab: 0..30, cd.: 30..70 (+пауза после точки), ef — следующий кусок.
        assertEquals(SubtitlePacer.Frame(listOf("ab", "cd."), 1), pacer.frameAt(plan, 0))
        assertEquals(SubtitlePacer.Frame(listOf("ab", "cd."), 2), pacer.frameAt(plan, 35))
        val efStart = plan[2].startMillis
        assertEquals(SubtitlePacer.Frame(listOf("ef"), 1), pacer.frameAt(plan, efStart))
        assertEquals(SubtitlePacer.Frame(listOf("ef"), 1), pacer.frameAt(plan, efStart + 10_000))
    }

    @Test
    fun `blank text gives no frame`() {
        assertNull(pacer.frameAt(pacer.plan("   "), 0))
        assertEquals(0L, pacer.durationMillis(emptyList()))
    }
}
