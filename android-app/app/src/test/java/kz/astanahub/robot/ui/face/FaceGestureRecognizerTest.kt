package kz.astanahub.robot.ui.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тап, удержание, поглаживание и свайп — параллельно с первого касания, гистерезис 12 px. */
class FaceGestureRecognizerTest {

    private class Log : FaceGestureRecognizer.Listener {
        val events = mutableListOf<String>()
        var lastSwipe = 0f
        var swipeEndVelocity = 0f
        var strokes = 0
        override fun onDown(x: Float, y: Float) { events += "down" }
        override fun onTap(x: Float, y: Float) { events += "tap" }
        override fun onHoldStart(downTime: Long) { events += "holdStart" }
        override fun onHoldCancel() { events += "holdCancel" }
        override fun onHold() { events += "hold" }
        override fun onSwipe(dx: Float) { if ("swipe" !in events) events += "swipe"; lastSwipe = dx }
        override fun onSwipeEnd(dx: Float, velocity: Float) { events += "swipeEnd"; swipeEndVelocity = velocity }
        override fun onSwipeCancel() { events += "swipeCancel" }
        override fun onStroke(vx: Float, vy: Float) { if (strokes++ == 0) events += "stroke" }
        override fun onStrokeEnd() { events += "strokeEnd" }
        override fun onUp() { events += "up" }
    }

    private val log = Log()
    private val r = FaceGestureRecognizer(slop = 12f, strokeMinPath = 100f, holdEnabled = true, listener = log)
    private var t = 1_000L

    /** Провести палец через точки равномерно, по 16 мс. */
    private fun drag(points: List<Pair<Float, Float>>) {
        for ((x, y) in points) {
            t += 16
            r.move(x, y, t)
            r.tick(t)
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int) =
        (1..steps).map { i -> (x0 + (x1 - x0) * i / steps) to (y0 + (y1 - y0) * i / steps) }

    @Test
    fun `touch taken by a button (Stop on the island) never starts a face gesture - no hold, no tap, no swipe`() {
        // Ревью M2-r1, B1: палец 3.6 с на «Стоп» в поездке открывал админку.
        r.down(500f, 900f, t, onControl = true)
        repeat(240) { t += 16; r.tick(t) } // 3.8 с удержания
        r.up(500f, 900f, t)
        assertEquals(emptyList<String>(), log.events)
        // Свайп с кнопки — тоже не жест лица.
        r.down(1500f, 540f, t, onControl = true)
        drag(line(1500f, 540f, 700f, 540f, 20))
        r.up(700f, 540f, t)
        r.cancel()
        assertEquals(emptyList<String>(), log.events)
        // Следующее обычное касание лица — снова жест.
        r.down(500f, 500f, t)
        t += 100
        r.up(500f, 500f, t)
        assertEquals(listOf("down", "holdStart", "holdCancel", "tap", "up"), log.events)
    }

    @Test
    fun `quick touch is a tap, response starts on touch down`() {
        r.down(500f, 400f, t)
        assertEquals(listOf("down", "holdStart"), log.events)
        r.up(503f, 401f, t + 120)
        assertEquals(listOf("down", "holdStart", "holdCancel", "tap", "up"), log.events)
    }

    @Test
    fun `jitter inside the hysteresis is still a tap`() {
        r.down(500f, 400f, t)
        drag(listOf(506f to 404f, 497f to 395f, 508f to 402f))
        r.up(504f, 399f, t + 40)
        assertTrue("tap" in log.events)
        assertFalse("swipe" in log.events || "stroke" in log.events)
    }

    @Test
    fun `slow press is neither tap nor hold`() {
        r.down(500f, 400f, t)
        repeat(60) { t += 16; r.tick(t) }
        r.up(500f, 400f, t)
        assertFalse("tap" in log.events)
        assertFalse("hold" in log.events)
        assertTrue("holdCancel" in log.events)
    }

    @Test
    fun `holding still for 3 s enters admin, once`() {
        r.down(500f, 400f, t)
        while (t < 1_000L + FaceGestureRecognizer.HOLD_MS - 20) { t += 16; r.tick(t) }
        assertFalse("hold" in log.events)
        repeat(5) { t += 16; r.tick(t) }
        assertEquals(1, log.events.count { it == "hold" })
        r.up(500f, 400f, t)
        assertFalse("tap" in log.events)
    }

    @Test
    fun `moving away cancels the hold`() {
        r.down(500f, 400f, t)
        repeat(30) { t += 16; r.tick(t) }
        drag(line(500f, 400f, 500f, 460f, 6))
        repeat(200) { t += 16; r.tick(t) }
        assertTrue("holdCancel" in log.events)
        assertFalse("hold" in log.events)
    }

    @Test
    fun `hold is not offered when admin access is disabled`() {
        val quiet = Log()
        val noHold = FaceGestureRecognizer(slop = 12f, strokeMinPath = 100f, holdEnabled = false, listener = quiet)
        noHold.down(500f, 400f, t)
        repeat(300) { t += 16; noHold.tick(t) }
        assertFalse("holdStart" in quiet.events || "hold" in quiet.events)
    }

    @Test
    fun `straight leftward drag is a swipe following the finger`() {
        r.down(1500f, 540f, t)
        drag(line(1500f, 540f, 900f, 550f, 12))
        assertEquals(FaceGestureRecognizer.Mode.SWIPE, r.mode)
        assertEquals(-600f, log.lastSwipe, 0.5f)
        r.up(900f, 550f, t)
        assertTrue("swipeEnd" in log.events)
        assertTrue("finger moved left: ${log.swipeEndVelocity}", log.swipeEndVelocity < -1_000f)
        assertFalse("stroke" in log.events || "tap" in log.events)
    }

    @Test
    fun `vertical strokes are petting`() {
        r.down(900f, 300f, t)
        drag(line(900f, 300f, 905f, 600f, 10) + line(905f, 600f, 900f, 300f, 10))
        assertEquals(FaceGestureRecognizer.Mode.STROKE, r.mode)
        assertTrue(r.stroking)
        r.up(900f, 300f, t)
        assertEquals(listOf("down", "holdStart", "holdCancel", "stroke", "strokeEnd", "up"), log.events)
    }

    @Test
    fun `circling that starts leftward cancels the swipe and becomes petting`() {
        // Круг от верхней точки против часовой: первые шаги — влево почти по горизонтали.
        r.down(1000f, 440f, t)
        val circle = (1..40).map { i ->
            val a = -PI / 2 - 2 * PI * i / 40
            (1000f + 100f * cos(a).toFloat()) to (540f + 100f * sin(a).toFloat())
        }
        drag(circle)
        assertTrue("swipe" in log.events)
        assertTrue("swipeCancel" in log.events)
        assertTrue(r.stroking)
        r.up(1000f, 440f, t)
        assertFalse("swipeEnd" in log.events)
    }

    @Test
    fun `back and forth horizontal rubbing is petting, never opens the menu`() {
        r.down(1000f, 540f, t)
        drag(line(1000f, 540f, 850f, 545f, 6) + line(850f, 545f, 1000f, 540f, 6) + line(1000f, 540f, 850f, 545f, 6))
        assertTrue(r.stroking)
        r.up(850f, 545f, t)
        assertFalse("swipeEnd" in log.events)
    }

    @Test
    fun `rightward drag is petting, not a swipe`() {
        r.down(800f, 540f, t)
        drag(line(800f, 540f, 1000f, 540f, 8))
        assertEquals(FaceGestureRecognizer.Mode.STROKE, r.mode)
        assertFalse("swipe" in log.events)
    }

    @Test
    fun `cancel never taps and never commits`() {
        r.down(1500f, 540f, t)
        drag(line(1500f, 540f, 1300f, 540f, 4))
        r.cancel()
        assertTrue("swipeCancel" in log.events)
        assertFalse("swipeEnd" in log.events || "tap" in log.events)
    }

    @Test
    fun `velocity uses the last 100 ms`() {
        r.down(0f, 0f, t)
        // Медленно 300 мс, потом быстро.
        drag(line(0f, 0f, -30f, 0f, 18))
        drag(line(-30f, 0f, -230f, 0f, 5))
        assertTrue("fast end dominates: ${r.velocityX()}", r.velocityX() < -2_000f)
    }
}
