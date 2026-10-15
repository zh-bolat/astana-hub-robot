package kz.astanahub.robot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {
    @Test
    fun `1920x1080 gives the same density regardless of firmware dpi`() {
        // 1920 px / 1600 dp = density 1.2 → 192 dpi
        assertEquals(192, UiScale.targetDensityDpi(1920, 1080, 100))
        assertEquals(192, UiScale.targetDensityDpi(1080, 1920, 100)) // портрет — по длинной стороне
    }

    @Test
    fun `user scale multiplies density`() {
        assertEquals(115, UiScale.targetDensityDpi(1920, 1080, 60))
        assertEquals(288, UiScale.targetDensityDpi(1920, 1080, 150))
    }

    @Test
    fun `other resolutions keep the same dp width`() {
        // 1280×800 → density 0.8 → ширина по-прежнему 1600 dp
        assertEquals(128, UiScale.targetDensityDpi(1280, 800, 100))
    }
}
