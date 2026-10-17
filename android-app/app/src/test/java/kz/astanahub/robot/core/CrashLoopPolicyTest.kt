package kz.astanahub.robot.core

import kz.astanahub.robot.core.CrashLoopPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLoopPolicyTest {

    @Test
    fun `three restarts in five minutes, the fourth crash pauses`() {
        var history = emptyList<Long>()
        val decisions = listOf(0L, 60_000L, 120_000L, 180_000L).map { now ->
            val (d, h) = CrashLoopPolicy.onCrash(history, now)
            history = h
            d
        }
        assertEquals(listOf(Decision.RESTART, Decision.RESTART, Decision.RESTART, Decision.PAUSE), decisions)
    }

    @Test
    fun `old crashes leave the window`() {
        val (d, h) = CrashLoopPolicy.onCrash(listOf(0L, 10_000L, 20_000L), 20_000L + CrashLoopPolicy.WINDOW_MILLIS)
        assertEquals(Decision.RESTART, d)
        assertEquals(listOf(20_000L + CrashLoopPolicy.WINDOW_MILLIS), h)
    }

    @Test
    fun `timestamps from before reboot are ignored`() {
        val (d, h) = CrashLoopPolicy.onCrash(listOf(900_000L, 901_000L, 902_000L), 5_000L)
        assertEquals(Decision.RESTART, d)
        assertEquals(listOf(5_000L), h)
    }
}
