package kz.astanahub.robot.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLogTest {

    private val log = PresenceLog(maxLines = 4, minGapMillis = 2_000, time = { "t$it" })

    @Test
    fun `persons are logged with id and distance, nearest face id remembered`() {
        assertNull(log.lastFaceId)
        assertTrue(log.onPersons(listOf(SeenPerson(5, 2.4), SeenPerson(3, 1.25)), 0))
        assertEquals("t0  PersonApi: id=3 1.25 м; id=5 2.40 м", log.text())
        assertEquals(3, log.lastFaceId)
    }

    @Test
    fun `distance jitter is throttled, a change of ids is logged at once`() {
        log.onPersons(listOf(SeenPerson(3, 1.2)), 0)
        assertFalse(log.onPersons(listOf(SeenPerson(3, 1.3)), 500))
        assertTrue(log.onPersons(listOf(SeenPerson(3, 1.3), SeenPerson(4, 2.0)), 600))
        assertTrue(log.onPersons(listOf(SeenPerson(3, 1.1), SeenPerson(4, 2.0)), 2_700))
        assertTrue(log.onPersons(emptyList(), 2_800))
        assertEquals("t2800  PersonApi: никого", log.text().lines().last())
        assertEquals(3, log.lastFaceId) // ушли — id для «Следить за мной» остаётся последним
    }

    @Test
    fun `anonymous person without a face id is not a follow target`() {
        log.onPersons(listOf(SeenPerson(-1, 1.0)), 0)
        assertNull(log.lastFaceId)
    }

    @Test
    fun `tracker events are logged, old lines dropped`() {
        log.onEvent(PresenceEvent.Arrived(8, greet = true), 1)
        log.onEvent(PresenceEvent.FaceId(9), 2)
        log.onEvent(PresenceEvent.Left, 3)
        log.onEvent(PresenceEvent.Arrived(9, greet = false), 4)
        log.onEvent(PresenceEvent.Left, 5)
        val lines = log.text().lines()
        assertEquals(4, lines.size)
        assertEquals("t2  → id лица 9", lines[0])
        assertEquals("t4  → подошёл id=9 (недавно здоровались)", lines[2])
        assertEquals(9, log.lastFaceId)
    }
}
