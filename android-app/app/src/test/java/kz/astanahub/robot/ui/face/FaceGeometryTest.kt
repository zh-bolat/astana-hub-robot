package kz.astanahub.robot.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kz.astanahub.robot.ui.face.FaceParam as P

class FaceGeometryTest {

    private val geometry = FaceGeometry()

    private fun pose(block: FloatArray.() -> Unit): FacePose {
        val pose = FacePose()
        Expressions.NEUTRAL.applyTo(pose.v)
        pose.v.block()
        geometry.compute(pose)
        return pose
    }

    @Test
    fun `neutral eye is a symmetric oval`() {
        pose {}
        val e = geometry.left
        assertEquals(e.h, e.top, 1e-3f)
        assertEquals(e.h, e.bottom, 1e-3f)
        assertEquals(0f, e.yc, 1e-3f)
        assertEquals(-geometry.right.cx, e.cx, 1e-3f)
    }

    @Test
    fun `closed eye collapses to a line below the center`() {
        pose { this[P.OPEN_L] = 0f; this[P.OPEN_R] = 0f; this[P.ARC] = 0f }
        val e = geometry.left
        assertEquals(0f, e.top, 1e-3f)
        assertEquals(0f, e.bottom, 1e-3f)
        assertTrue(e.yc > 0f)
    }

    @Test
    fun `happy eye is an upward arc`() {
        pose { this[P.SMILE_L] = 1f }
        val e = geometry.left
        // Верх выпуклый вверх, низ тоже поднят выше линии углов: «∩».
        assertTrue(e.top > 0f)
        assertTrue(e.bottom < 0f)
        assertTrue(e.top + e.bottom > 0f) // толщина дуги положительная
    }

    @Test
    fun `sleeping eye is a downward arc`() {
        pose { this[P.OPEN_L] = 0f; this[P.ARC] = -0.55f }
        val e = geometry.left
        // Вершина «верха» ниже линии углов, низ ещё ниже на ту же величину — «∪».
        assertTrue(e.top < 0f)
        assertTrue(e.bottom > 0f)
        assertEquals(-e.top, e.bottom, 1e-3f)
    }

    @Test
    fun `looking right moves both eyes right and makes the far eye smaller`() {
        pose {}
        val leftX = geometry.left.cx
        val rightX = geometry.right.cx
        pose { this[P.GAZE_X] = 1f }
        assertTrue(geometry.left.cx > leftX)
        assertTrue(geometry.right.cx > rightX)
        assertTrue("near eye bigger", geometry.left.a > geometry.right.a)
    }

    @Test
    fun `looking up moves eyes, brows and mouth up`() {
        pose {}
        val eyeY = geometry.left.cy
        val browY = geometry.browLeft.y0
        val mouthY = geometry.mouth.topY
        pose { this[P.GAZE_Y] = -1f }
        assertTrue(geometry.left.cy < eyeY)
        assertTrue(geometry.browLeft.y0 < browY)
        assertTrue(geometry.mouth.topY < mouthY)
    }

    @Test
    fun `positive brow tilt raises the inner ends on both sides`() {
        pose { this[P.BROW_TILT_L] = 15f; this[P.BROW_TILT_R] = 15f }
        // x0/y0 — внутренний конец (к центру лица).
        assertTrue(geometry.browLeft.x0 > geometry.browLeft.x1)
        assertTrue(geometry.browRight.x0 < geometry.browRight.x1)
        assertTrue(geometry.browLeft.y0 < geometry.browLeft.y1)
        assertTrue(geometry.browRight.y0 < geometry.browRight.y1)
    }

    @Test
    fun `brow arch bends upward and brow sits above the eye`() {
        pose { this[P.BROW_ARCH] = 12f }
        val b = geometry.browLeft
        assertTrue(b.cy < (b.y0 + b.y1) / 2f)
        assertTrue(b.y0 < geometry.left.cy - geometry.left.h)
    }

    @Test
    fun `surprised eyes push the brows up`() {
        pose {}
        val browY = geometry.browLeft.y0
        pose { this[P.EYE_H] = 110f }
        assertTrue(geometry.browLeft.y0 < browY)
    }

    @Test
    fun `smile lifts mouth corners, frown drops them`() {
        pose { this[P.MOUTH_SMILE] = 30f }
        assertTrue(geometry.mouth.ly < geometry.mouth.topY)
        pose { this[P.MOUTH_SMILE] = -20f }
        assertTrue(geometry.mouth.ly > geometry.mouth.topY)
    }

    @Test
    fun `mouth opening height follows open and roundness splits it`() {
        pose { this[P.MOUTH_OPEN] = 0.5f; this[P.MOUTH_ROUND] = 0f }
        val m = geometry.mouth
        assertEquals(0.5f * FaceGeometry.MOUTH_OPEN_H, m.bottomY - m.topY, 1e-3f)
        // «D» (round = 0) — открывается больше вниз: челюсть опускается.
        assertTrue(m.bottomY - FaceLayout.MOUTH_Y > FaceLayout.MOUTH_Y - m.topY)
        pose { this[P.MOUTH_OPEN] = 0.5f; this[P.MOUTH_ROUND] = 1f }
        // «o» — симметрично.
        assertEquals(geometry.mouth.bottomY - FaceLayout.MOUTH_Y, FaceLayout.MOUTH_Y - geometry.mouth.topY, 1e-3f)
    }

    @Test
    fun `skew raises the right corner`() {
        pose { this[P.MOUTH_SKEW] = 12f }
        assertTrue(geometry.mouth.ry < geometry.mouth.ly)
    }

    @Test
    fun `positive lid angle lowers the outer corner of each eye`() {
        pose { this[P.LID_L] = 0.3f; this[P.LID_R] = 0.3f; this[P.LID_ANGLE] = 15f }
        val l = geometry.left
        val r = geometry.right
        // Кромка века y(x) = lidY + slope·x; внешний край левого глаза — x < 0.
        val leftOuter = l.lidY + l.lidSlope * -l.a
        val leftInner = l.lidY + l.lidSlope * l.a
        assertTrue(leftOuter > leftInner)
        val rightOuter = r.lidY + r.lidSlope * r.a
        val rightInner = r.lidY + r.lidSlope * -r.a
        assertTrue(rightOuter > rightInner)
    }

    @Test
    fun `lid covers more as it closes`() {
        pose { this[P.LID_L] = 0f }
        val open = geometry.left.lidY
        pose { this[P.LID_L] = 0.5f }
        assertTrue(geometry.left.lidY > open)
        assertTrue(open < -geometry.left.h) // при 0 кромка выше глаза — ничего не закрывает
    }

    @Test
    fun `asymmetry makes the left eye bigger`() {
        pose { this[P.EYE_ASYM] = 0.12f }
        assertTrue(geometry.left.a > geometry.right.a)
        assertTrue(geometry.left.h > geometry.right.h)
    }
}
