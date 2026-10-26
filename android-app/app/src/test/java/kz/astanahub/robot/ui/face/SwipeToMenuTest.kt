package kz.astanahub.robot.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отпускание свайпа с лица к меню: решение по скорости и проекции, засчитанный свайп не откатывается. */
class SwipeToMenuTest {

    private val width = 1920f
    private val minExit = 144f

    @Test
    fun `fast flick left commits and keeps going left with the finger's velocity`() {
        val r = SwipeToMenu.release(fingerDx = -200f, shown = -150f, velocity = -2_400f, width = width, minExit = minExit)
        assertTrue(r.commit)
        assertTrue("target ${r.target} is further left than the release point", r.target < -150f - minExit)
        assertEquals(-2_400f, r.velocity, 0f)
        // Пружина без перелёта назад: проекция (|v|·0.5 с) много больше |v| / ω (Gentle: ω ≈ 12.6 рад/с).
        assertTrue(-150f - r.target > 2_400f / 12.6f)
    }

    @Test
    fun `slow long drag commits by projection and never starts moving right`() {
        val r = SwipeToMenu.release(fingerDx = -700f, shown = -300f, velocity = 300f, width = width, minExit = minExit)
        assertTrue(r.commit)
        assertTrue(r.target <= -300f - minExit)
        assertEquals(0f, r.velocity, 0f)
    }

    @Test
    fun `short or rightward gesture springs back with the finger's velocity`() {
        val back = SwipeToMenu.release(fingerDx = -150f, shown = -120f, velocity = -200f, width = width, minExit = minExit)
        assertFalse(back.commit)
        assertEquals(0f, back.target, 0f)
        assertEquals(-200f, back.velocity, 0f)
        val right = SwipeToMenu.release(fingerDx = -900f, shown = -350f, velocity = 1_500f, width = width, minExit = minExit)
        assertFalse("decision by velocity sign, not position", right.commit)
    }

    @Test
    fun `projection is Apple's exponential decay form`() {
        assertEquals(499f, project(1_000f), 0.5f)
        assertEquals(0f, project(0f), 0f)
    }

    @Test
    fun `rubber band resists more the further you pull`() {
        val near = rubberBand(100f, width)
        val far = rubberBand(1_000f, width)
        assertTrue(near < 100f && far < 1_000f)
        assertTrue(far / 1_000f < near / 100f)
    }
}
