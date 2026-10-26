package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.ui.face.FaceLayout
import kz.astanahub.robot.ui.face.project
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переход экран 1 ↔ экран 2: палец 1:1, rubber-band, решение по знаку скорости и проекции, морфинг лица. */
class ScreenSwipeTest {

    private val w = 1920f
    private val h = 1080f

    @Test
    fun `screen follows the finger one to one inside the range`() {
        assertEquals(0.25f, ScreenSwipe.progress(0f, -480f, w), 1e-5f)
        assertEquals(0.5f, ScreenSwipe.progress(0f, -960f, w), 1e-5f)
        // Закрытие с экрана 2: вправо.
        assertEquals(0.75f, ScreenSwipe.progress(1f, 480f, w), 1e-5f)
    }

    @Test
    fun `beyond the edges - rubber band 0_55, monotonic and less than the finger`() {
        val over = ScreenSwipe.progress(1f, -480f, w) - 1f // тянут дальше открытого
        assertTrue(over > 0f && over < 0.25f)
        // Формула Apple: (x·d·c) / (d + c·x) в px.
        val x = 480f
        assertEquals((x * w * 0.55f) / (w + 0.55f * x) / w, over, 1e-5f)
        val under = ScreenSwipe.progress(0f, 300f, w) // вправо от лица
        assertTrue(under < 0f && under > -300f / w)
        var prev = 1f
        for (dx in 0..2000 step 100) {
            val p = ScreenSwipe.progress(1f, -dx.toFloat(), w)
            assertTrue(p >= prev - 1e-6f)
            prev = p
        }
        // Асимптота Apple — размер экрана: даже за пределами экрана пальцем прогресс не доходит до 2.
        assertTrue(ScreenSwipe.progress(1f, -100_000f, w) < 2f)
        assertTrue("a full-width overdrag shows about a third", ScreenSwipe.progress(1f, -w, w) < 1.4f)
    }

    @Test
    fun `release decides by the sign of velocity, not by position`() {
        // Почти до конца, но бросок назад — закрыть.
        assertFalse(ScreenSwipe.release(0.9f, 900f, w).open)
        // Чуть-чуть сдвинули, но бросок влево — открыть.
        assertTrue(ScreenSwipe.release(0.1f, -900f, w).open)
        val r = ScreenSwipe.release(0.4f, -1200f, w)
        assertTrue(r.fling)
        assertEquals(1200f / w, r.velocity, 1e-5f) // передача скорости пружине, прогресс/с
    }

    @Test
    fun `slow release - momentum projection over the half`() {
        assertTrue(ScreenSwipe.release(0.55f, 0f, w).open)
        assertFalse(ScreenSwipe.release(0.45f, 0f, w).open)
        // Медленно, но с импульсом влево: 0.45 + проекция ≥ 0.5.
        val v = -250f
        val projected = 0.45f - project(v) / w
        assertEquals(projected >= 0.5f, ScreenSwipe.release(0.45f, v, w).open)
    }

    @Test
    fun `during a trip the screen never opens`() {
        assertFalse(ScreenSwipe.release(0.9f, -3000f, w, allowOpen = false).open)
    }

    @Test
    fun `face morph - full face at 0, mini face centred in the header slot at 1, continuous between`() {
        val slotCx = 144f
        val slotCy = 77f
        val slotH = 86f
        val t0 = FaceMorph.transform(0f, w, h, slotCx, slotCy, slotH)
        assertEquals(FaceMorph.Transform(1f, 0f, 0f), t0)
        val t1 = FaceMorph.transform(1f, w, h, slotCx, slotCy, slotH)
        val u = min(w / FaceLayout.WIDTH, h / FaceLayout.HEIGHT)
        // Видимое лицо в слоте: высота = высоте слота, центр — в центре слота.
        assertEquals(slotH, FaceMorph.CONTENT_HEIGHT * u * t1.scale, 0.5f)
        val faceCy = h / 2 + FaceMorph.CONTENT_CENTER_Y * u
        assertEquals(slotCx, w / 2 + t1.translationX, 0.5f)
        assertEquals(slotCy, h / 2 + (faceCy - h / 2) * t1.scale + t1.translationY, 0.5f)
        // Непрерывно и монотонно: без скачков на любом шаге пальца.
        var prev = t0
        var p = 0f
        while (p <= 1f) {
            val t = FaceMorph.transform(p, w, h, slotCx, slotCy, slotH)
            assertTrue(t.scale <= prev.scale + 1e-6f)
            assertTrue(abs(t.scale - prev.scale) < 0.05f)
            assertTrue(abs(t.translationX - prev.translationX) < 20f)
            assertTrue(abs(t.translationY - prev.translationY) < 20f)
            prev = t
            p += 0.01f
        }
    }

    @Test
    fun `face morph - even shrink rate (log scale)`() {
        val a = FaceMorph.transform(0.25f, w, h, 144f, 77f, 86f).scale
        val b = FaceMorph.transform(0.5f, w, h, 144f, 77f, 86f).scale
        val c = FaceMorph.transform(0.75f, w, h, 144f, 77f, 86f).scale
        assertEquals(a / b, b / c, 1e-3f)
    }

    @Test
    fun `face morph beyond the edges stays sane`() {
        val left = FaceMorph.transform(-0.05f, w, h, 144f, 77f, 86f)
        assertEquals(1f, left.scale, 0f)
        assertTrue(left.translationX > 0f) // за пальцем вправо
        val over = FaceMorph.transform(1.1f, w, h, 144f, 77f, 86f)
        assertTrue(over.scale < FaceMorph.transform(1f, w, h, 144f, 77f, 86f).scale)
        assertTrue(over.scale > 0f)
    }
}
