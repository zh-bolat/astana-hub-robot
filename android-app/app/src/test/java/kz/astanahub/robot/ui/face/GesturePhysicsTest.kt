package kz.astanahub.robot.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Физика жестов Apple: проекция импульса и rubber-band. Решение перехода экран 1 ↔ экран 2 — в
 * [kz.astanahub.robot.ui.screen.ScreenSwipeTest] (M3 заменил прежний свайп к меню).
 */
class GesturePhysicsTest {

    private val width = 1920f

    @Test
    fun `projection is Apple's exponential decay form`() {
        assertEquals(499f, project(1_000f), 0.5f)
        assertEquals(0f, project(0f), 0f)
        assertEquals(-499f, project(-1_000f), 0.5f)
    }

    @Test
    fun `rubber band resists more the further you pull`() {
        val near = rubberBand(100f, width)
        val far = rubberBand(1_000f, width)
        assertTrue(near < 100f && far < 1_000f)
        assertTrue(far / 1_000f < near / 100f)
    }
}
