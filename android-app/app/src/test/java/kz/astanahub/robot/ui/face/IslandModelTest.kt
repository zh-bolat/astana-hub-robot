package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.WhereIsHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Остров экрана лица: where_is на ~9 с, «да» → поездка без мигания, итог поездки коротко и уход. */
class IslandModelTest {

    private val m = IslandModel()
    private var now = 10_000L
    private fun hint(seq: Int, place: String = "Кафе") = WhereIsHint(place, 15.0, -Math.PI / 2, canGuide = true, seq = seq)

    @Test
    fun `where_is island shows for 9 s, then goes`() {
        assertTrue(m.update(null, hint(1), now))
        assertEquals(IslandContent.WhereIs(hint(1)), m.content)
        assertEquals(now + IslandModel.WHERE_MS, m.nextChangeAt())
        now += IslandModel.WHERE_MS - 1
        assertFalse(m.tick(now))
        now += 1
        assertTrue(m.tick(now))
        assertNull(m.content)
        assertNull(m.nextChangeAt())
    }

    @Test
    fun `the same hint does not come back, a new answer about the same place does`() {
        m.update(null, hint(1), now)
        now += IslandModel.WHERE_MS
        m.tick(now)
        m.update(null, hint(1), now) // состояние то же — остров не возвращается
        assertNull(m.content)
        m.update(null, hint(2), now) // спросили ещё раз
        assertEquals(IslandContent.WhereIs(hint(2)), m.content)
    }

    @Test
    fun `a new question does not hide the where island - yes turns it into the trip island`() {
        m.update(null, hint(1), now)
        now += 2_000
        // «да»: новый вопрос (whereIs в состоянии остаётся прежним) — остров на месте…
        assertFalse(m.update(null, hint(1), now))
        assertEquals(IslandContent.WhereIs(hint(1)), m.content)
        // …и поездка началась — тот же остров становится островом поездки.
        now += 1_500
        assertTrue(m.update("Кафе", hint(1), now))
        assertEquals(IslandContent.Trip("Кафе"), m.content)
        assertNull("trip island has no timer", m.nextChangeAt())
    }

    @Test
    fun `trip end - waits for the outcome, shows it briefly, then goes`() {
        m.update("Кафе", null, now)
        now += 30_000
        m.update(null, null, now) // ViewModel: поездка кончилась…
        assertEquals("trip island holds while the outcome is on its way", IslandContent.Trip("Кафе"), m.content)
        now += 50
        assertTrue(m.onOutcome(TripOutcome.ARRIVED, now)) // …и итог
        assertEquals(IslandContent.Outcome(TripOutcome.ARRIVED, "Кафе"), m.content)
        assertEquals(now + IslandModel.OUTCOME_MS, m.nextChangeAt())
        now += IslandModel.OUTCOME_MS
        assertTrue(m.tick(now))
        assertNull(m.content)
    }

    @Test
    fun `outcome that arrives before the state - shown from the trip end`() {
        m.update("Кафе", null, now)
        now += 10_000
        m.onOutcome(TripOutcome.STOPPED, now)
        assertEquals(IslandContent.Trip("Кафе"), m.content) // в состоянии ещё едем
        now += 30
        m.update(null, null, now)
        assertEquals(IslandContent.Outcome(TripOutcome.STOPPED, "Кафе"), m.content)
        now += IslandModel.OUTCOME_MS - 1
        m.tick(now)
        assertEquals(IslandContent.Outcome(TripOutcome.STOPPED, "Кафе"), m.content)
        now += 1
        m.tick(now)
        assertNull(m.content)
    }

    @Test
    fun `no outcome within the wait - the island simply goes`() {
        m.update("Кафе", null, now)
        now += 5_000
        m.update(null, null, now)
        assertEquals(now + IslandModel.WAIT_OUTCOME_MS, m.nextChangeAt())
        now += IslandModel.WAIT_OUTCOME_MS
        assertTrue(m.tick(now))
        assertNull(m.content)
    }

    @Test
    fun `outcome without a trip is ignored, where during a trip is ignored`() {
        assertFalse(m.onOutcome(TripOutcome.FAILED, now))
        assertNull(m.content)
        m.update("Кафе", null, now)
        m.update("Кафе", hint(5), now) // ответ «где» в пути — остров поездки остаётся
        assertEquals(IslandContent.Trip("Кафе"), m.content)
        now += 1_000
        m.update(null, hint(5), now)
        m.onOutcome(TripOutcome.ARRIVED, now)
        now += IslandModel.OUTCOME_MS
        m.tick(now)
        assertNull("the old where hint does not pop up after the trip", m.content)
    }

    @Test
    fun `arrow and gaze point the same way as the words`() {
        // Справа от посетителя (−90°): стрелка повёрнута вправо, глаза — вправо.
        assertEquals(90f, IslandGeometry.arrowRotationDeg(-Math.PI / 2), 1e-4f)
        assertTrue(IslandGeometry.gazeX(-Math.PI / 2) > 0.8f)
        // Слева (+90°): влево.
        assertEquals(-90f, IslandGeometry.arrowRotationDeg(Math.PI / 2), 1e-4f)
        assertTrue(IslandGeometry.gazeX(Math.PI / 2) < -0.8f)
        // Прямо вперёд от посетителя (за роботом): стрелка вверх, взгляд чуть вверх; за спиной — вниз.
        assertEquals(0f, IslandGeometry.arrowRotationDeg(0.0), 1e-4f)
        assertTrue(IslandGeometry.gazeY(0.0) < 0f)
        assertTrue(IslandGeometry.gazeY(Math.PI) > 0f)
    }

    @Test
    fun `failed trip shows its own outcome, sleep clears the island at once`() {
        m.update("Ресепшен", null, now)
        m.update(null, null, now + 10)
        m.onOutcome(TripOutcome.FAILED, now + 20)
        assertEquals(IslandContent.Outcome(TripOutcome.FAILED, "Ресепшен"), m.content)
        assertTrue(m.clear())
        assertNull(m.content)
        assertNull(m.nextChangeAt())
    }
}
