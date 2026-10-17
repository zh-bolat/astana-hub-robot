package kz.astanahub.robot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGateTest {
    private var now = 1_000L
    private val gate = EchoGate(tailMillis = 300, clock = { now })

    @Test
    fun `suppressed while speaking and for the tail after`() {
        assertFalse(gate.suppressed())
        gate.begin()
        assertTrue(gate.suppressed())
        now += 5_000
        gate.end()
        now += 299
        assertTrue(gate.suppressed())
        now += 2
        assertFalse(gate.suppressed())
    }

    @Test
    fun `overlapping speech is a counter, extra end does not go negative`() {
        gate.begin(); gate.begin()
        gate.end()
        now += 1_000
        assertTrue("второй голос ещё говорит", gate.speaking)
        gate.end(); gate.end()
        now += 1_000
        assertFalse(gate.suppressed())
        gate.begin()
        assertTrue(gate.speaking)
    }
}
