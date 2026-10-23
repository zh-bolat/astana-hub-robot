package kz.astanahub.robot.presence

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пробуждение по человеку: порог расстояния и времени, анти-спам, уход — на виртуальном времени. */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceTrackerTest {

    private val config = PresenceConfig()

    private class Harness(scope: TestScope, config: PresenceConfig) {
        val persons = MutableStateFlow<List<SeenPerson>>(emptyList())
        val events = mutableListOf<PresenceEvent>()
        val tracker = PresenceTracker(config, clock = { scope.testScheduler.currentTime })
        val job = scope.launch { tracker.track(persons).collect { events += it } }
    }

    private fun TestScope.harness() = Harness(this, config).also { runCurrent() }

    @Test
    fun `person standing close for 0_7 s arrives and is greeted`() = runTest {
        val h = harness()
        h.persons.value = listOf(SeenPerson(5, 1.5))
        advanceTimeBy(600); runCurrent()
        assertTrue(h.events.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf<PresenceEvent>(PresenceEvent.Arrived(5, greet = true)), h.events)
        h.job.cancel()
    }

    @Test
    fun `passer-by and far person do not wake the robot`() = runTest {
        val h = harness()
        h.persons.value = listOf(SeenPerson(1, 1.8))
        advanceTimeBy(400); runCurrent()
        h.persons.value = emptyList() // прошёл мимо
        advanceTimeBy(2_000); runCurrent()
        h.persons.value = listOf(SeenPerson(2, 4.0)) // стоит в 4 м
        advanceTimeBy(10_000); runCurrent()
        assertTrue(h.events.isEmpty())
        h.job.cancel()
    }

    @Test
    fun `nobody near for leave time - visitor left, short gaps do not count`() = runTest {
        val h = harness()
        h.persons.value = listOf(SeenPerson(5, 1.0))
        advanceTimeBy(800); runCurrent()
        h.persons.value = listOf(SeenPerson(5, 2.8)) // отошёл на шаг — ещё рядом (гистерезис)
        advanceTimeBy(20_000); runCurrent()
        h.persons.value = emptyList()
        advanceTimeBy(config.leaveMillis - 1_000); runCurrent()
        h.persons.value = listOf(SeenPerson(5, 1.2)) // вернулся
        advanceTimeBy(5_000); runCurrent()
        h.persons.value = emptyList()
        advanceTimeBy(config.leaveMillis); runCurrent()
        assertEquals(listOf(PresenceEvent.Arrived(5, true), PresenceEvent.Left), h.events)
        h.job.cancel()
    }

    @Test
    fun `same person is not greeted again within the anti-spam window`() = runTest {
        val h = harness()
        fun visit(id: Int) {
            h.persons.value = listOf(SeenPerson(id, 1.0))
            advanceTimeBy(800); runCurrent()
            h.persons.value = emptyList()
            advanceTimeBy(config.leaveMillis + 100); runCurrent()
        }
        visit(7)
        visit(7) // через ~16 с
        visit(8) // другой человек
        advanceTimeBy(config.regreetMillis); runCurrent()
        visit(7) // через 5 мин — снова здороваемся
        val arrivals = h.events.filterIsInstance<PresenceEvent.Arrived>()
        assertEquals(listOf(true, false, true, true), arrivals.map { it.greet })
        assertEquals(listOf(7, 7, 8, 7), arrivals.map { it.personId })
        h.job.cancel()
    }

    @Test
    fun `person without face id is greeted at most once a minute, face id comes later`() = runTest {
        val h = harness()
        h.persons.value = listOf(SeenPerson(-1, 2.0))
        advanceTimeBy(800); runCurrent()
        h.persons.value = listOf(SeenPerson(12, 1.0)) // подошёл ближе — лицо распознано
        runCurrent()
        h.persons.value = emptyList()
        advanceTimeBy(config.leaveMillis + 100); runCurrent()
        h.persons.value = listOf(SeenPerson(-1, 2.0))
        advanceTimeBy(800); runCurrent()
        assertEquals(
            listOf(
                PresenceEvent.Arrived(-1, true), PresenceEvent.FaceId(12), PresenceEvent.Left,
                PresenceEvent.Arrived(-1, false),
            ),
            h.events,
        )
        h.job.cancel()
    }
}
