package kz.astanahub.robot.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.robot.RobotPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Когда робот шлёт серверу фразы «где X» (`state.directions`): старт, после поездки, сдвиг, нет позиции, сбои. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDirectionsTest {

    private var pose: RobotPose? = RobotPose(0.0, 0.0, 0.0)
    private var poseError: Exception? = null
    private var phrases: Map<String, Map<Lang, String>> = mapOf("cafe" to mapOf(Lang.RU to "Кафе — справа от вас."))
    private var buildError: Exception? = null
    private var accept = true
    private var now = 0L
    private val sent = mutableListOf<Map<String, Map<Lang, String>>>()
    private val builtFrom = mutableListOf<RobotPose>()
    private val journal = mutableListOf<String>()

    private fun reporter() = LiveDirectionsReporter(
        pose = { poseError?.let { throw it }; pose },
        build = { p -> buildError?.let { throw it }; builtFrom += p; phrases },
        send = { d -> if (accept) { sent += d; true } else false },
        clock = { now },
        log = { journal += it },
    )

    @Test
    fun `start and after trip are sent whatever the movement, with the reason in the journal`() = runTest {
        val r = reporter()
        assertEquals(DirectionsReason.START, r.report(DirectionsReason.START))
        assertEquals(1, sent.size)
        assertEquals(listOf("live directions → сервер: мест 1, причина=start"), journal)
        assertEquals(DirectionsReason.AFTER_TRIP, r.report(DirectionsReason.AFTER_TRIP)) // та же поза — всё равно шлём
        assertEquals(2, sent.size)
        assertEquals("live directions → сервер: мест 1, причина=after_trip", journal.last())
    }

    @Test
    fun `tick sends only after a shift of half a meter or a turn of 20 degrees since the last send`() = runTest {
        val r = reporter()
        r.report(DirectionsReason.START)
        pose = RobotPose(0.3, 0.3, Math.toRadians(15.0)) // 0,42 м и 15°
        assertNull(r.tick())
        pose = RobotPose(0.5, 0.0, 0.0)
        assertEquals(DirectionsReason.MOVED, r.tick())
        assertNull("compared with the last sent pose", r.tick())
        pose = RobotPose(0.5, 0.0, Math.toRadians(-21.0))
        assertEquals(DirectionsReason.MOVED, r.tick())
        // Курс через ±180°: 170° → -175° — это 15°, не 345°.
        pose = RobotPose(0.5, 0.0, Math.toRadians(170.0)); r.tick()
        pose = RobotPose(0.5, 0.0, Math.toRadians(-175.0))
        assertNull(r.tick())
        assertEquals(4, sent.size)
        assertEquals("live directions → сервер: мест 1, причина=moved", journal.last())
    }

    @Test
    fun `no position - nothing is sent, the start is retried on the next tick`() = runTest {
        pose = null
        val r = reporter()
        assertNull(r.report(DirectionsReason.START))
        assertTrue(sent.isEmpty())
        assertTrue(builtFrom.isEmpty())
        assertEquals(listOf("live directions (start): нет позиции робота — не шлю"), journal)
        assertNull(r.tick()) // всё ещё нет — тихо
        assertEquals(1, journal.size)
        pose = RobotPose(1.0, 1.0, 0.0)
        assertEquals(DirectionsReason.START, r.tick())
        assertEquals(listOf(RobotPose(1.0, 1.0, 0.0)), builtFrom)
    }

    @Test
    fun `robot api and map errors do not escape and the set is retried later`() = runTest {
        poseError = IllegalStateException("RobotApi disconnected")
        val r = reporter()
        assertNull(r.report(DirectionsReason.AFTER_TRIP))
        assertTrue(journal.single(), journal.single().startsWith("live directions (after_trip): ошибка getPosition"))
        poseError = null
        buildError = RuntimeException("getPlaceList")
        assertNull(r.tick())
        assertNull(r.report(DirectionsReason.START))
        buildError = null
        assertEquals(DirectionsReason.START, r.tick())
        assertEquals(1, sent.size)
    }

    @Test
    fun `empty set or closed session is not counted as sent`() = runTest {
        phrases = emptyMap()
        val r = reporter()
        assertNull(r.report(DirectionsReason.START))
        assertEquals("live directions (start): нет мест базы на карте — не шлю", journal.last())
        phrases = mapOf("wc" to mapOf(Lang.EN to "Restroom is on your left."))
        accept = false
        assertNull(r.tick())
        assertEquals(0, r.sentCount)
        accept = true
        assertEquals(DirectionsReason.START, r.tick())
        assertEquals(1, r.sentCount)
    }

    @Test
    fun `a standing robot refreshes the set before the server stops trusting it`() = runTest {
        val r = reporter()
        r.report(DirectionsReason.START)
        now += LiveDirectionsReporter.REFRESH_MILLIS - 1
        assertNull(r.tick())
        now += 1
        assertEquals(DirectionsReason.REFRESH, r.tick())
    }

    @Test
    fun `run checks every period until cancelled`() = runTest {
        val r = reporter()
        r.report(DirectionsReason.START)
        val job = launch { r.run(15_000) }
        pose = RobotPose(2.0, 0.0, 0.0)
        advanceTimeBy(14_999); runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, sent.size)
        pose = RobotPose(4.0, 0.0, 0.0)
        job.cancel()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, sent.size)
    }
}
