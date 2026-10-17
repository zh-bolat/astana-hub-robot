package kz.astanahub.robot.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectorTest {

    @Test
    fun `backoff 5 10 20 40 60 60 seconds until connected`() = runTest {
        var connected = false
        val attemptsAt = mutableListOf<Long>()
        val r = Reconnector(backgroundScope, "test", { connected }, { attemptsAt += testScheduler.currentTime })
        r.schedule()
        r.schedule() // повторный вызов не плодит второй цикл
        advanceTimeBy(195_001)
        assertEquals(listOf(5_000L, 15_000L, 35_000L, 75_000L, 135_000L, 195_000L), attemptsAt)

        connected = true
        advanceTimeBy(120_000)
        assertEquals(6, attemptsAt.size)
        assertFalse(r.pending)
    }

    @Test
    fun `reset (onRecovery) cancels pending reconnect and restarts backoff`() = runTest {
        val attemptsAt = mutableListOf<Long>()
        val r = Reconnector(backgroundScope, "test", { false }, { attemptsAt += testScheduler.currentTime })
        r.schedule()
        advanceTimeBy(3_000)
        r.reset()
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), attemptsAt)

        r.schedule()
        advanceTimeBy(5_001)
        assertEquals(listOf(68_000L), attemptsAt)
    }

    @Test
    fun `exception in connect does not break the loop`() = runTest {
        var calls = 0
        val r = Reconnector(backgroundScope, "test", { false }, { calls++; error("SDK boom") })
        r.schedule()
        advanceTimeBy(15_001)
        assertEquals(2, calls)
    }
}
