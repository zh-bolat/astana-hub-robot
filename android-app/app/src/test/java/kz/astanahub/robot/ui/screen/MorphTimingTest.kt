package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.ui.face.Bezier
import org.junit.Assert.assertEquals
import org.junit.Test

class MorphTimingTest {

    @Test
    fun `inverse undoes the curve`() {
        val c = MorphTiming.CURVE
        for (x in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.95f)) {
            assertEquals(x, c.inverse(c.transform(x)), 0.002f)
        }
        assertEquals(0f, Bezier.EASE.inverse(0f), 0f)
        assertEquals(1f, Bezier.EASE.inverse(1f), 0f)
    }

    @Test
    fun `progress maps back to prototype milliseconds`() {
        assertEquals(0f, MorphTiming.ms(0f, opening = true), 0.5f)
        assertEquals(900f, MorphTiming.ms(1f, opening = true), 0.5f)
        assertEquals(0f, MorphTiming.ms(1f, opening = false), 0.5f)
        assertEquals(900f, MorphTiming.ms(0f, opening = false), 0.5f)
        // Сглаженный прогресс в середине времени — не середина: кривая (.45, 0, .15, 1).
        val half = MorphTiming.CURVE.transform(0.5f)
        assertEquals(450f, MorphTiming.ms(half, opening = true), 2f)
    }

    @Test
    fun `layers follow prototype timers`() {
        // Открытие: страница справа и тело робота — только с 450 мс; к концу — полностью.
        assertEquals(0f, MorphTiming.right(400f, opening = true), 0f)
        assertEquals(0f, MorphTiming.body(440f, opening = true), 0f)
        assertEquals(1f, MorphTiming.right(900f, opening = true), 0.001f)
        assertEquals(1f, MorphTiming.body(900f, opening = true), 0.001f)
        // Чёрный слой лица: в начале открытия непрозрачен, к 585 мс исчез; при закрытии — с 315 мс.
        assertEquals(1f, MorphTiming.faceLayer(80f, opening = true), 0f)
        assertEquals(0f, MorphTiming.faceLayer(600f, opening = true), 0f)
        assertEquals(0f, MorphTiming.faceLayer(300f, opening = false), 0f)
        assertEquals(1f, MorphTiming.faceLayer(900f, opening = false), 0f)
        // Закрытие: правая колонка гаснет за 300 мс.
        assertEquals(0f, MorphTiming.right(300f, opening = false), 0.001f)
    }
}
