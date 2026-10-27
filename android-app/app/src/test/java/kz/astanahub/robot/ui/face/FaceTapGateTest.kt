package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.face.FaceTapGate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** MF4: тап по спящему лицу будит сразу, двойной тап бодрствующего — сон, после пробуждения 600 мс тапы не считаются. */
class FaceTapGateTest {

    private val gate = FaceTapGate()

    @Test
    fun `asleep - one tap wakes at once, without waiting for a second`() {
        assertEquals(Action.WAKE, gate.onTap(10_000, asleep = true))
    }

    @Test
    fun `right after waking taps do not count - a double tap does not put it back to sleep`() {
        assertEquals(Action.WAKE, gate.onTap(10_000, asleep = true))
        assertEquals(Action.NONE, gate.onTap(10_150, asleep = false))
        assertEquals(Action.NONE, gate.onTap(10_400, asleep = false))
        // Окно прошло: обычные правила.
        assertEquals(Action.NONE, gate.onTap(10_700, asleep = false))
        assertEquals(Action.SLEEP, gate.onTap(10_900, asleep = false))
    }

    @Test
    fun `awake - single tap does nothing, double tap within 350 ms sleeps`() {
        assertEquals(Action.NONE, gate.onTap(1_000, asleep = false))
        assertEquals(Action.SLEEP, gate.onTap(1_300, asleep = false))
        // Третий тап — новый первый.
        assertEquals(Action.NONE, gate.onTap(1_500, asleep = false))
    }

    @Test
    fun `two slow taps are not a double tap`() {
        assertEquals(Action.NONE, gate.onTap(1_000, asleep = false))
        assertEquals(Action.NONE, gate.onTap(1_400, asleep = false))
        assertEquals(Action.SLEEP, gate.onTap(1_700, asleep = false))
    }
}
