package kz.astanahub.robot.ui.face

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kz.astanahub.robot.ui.face.FaceParam as P

class ExpressionsTest {

    private fun value(m: FaceMood, p: Int): Float {
        val e = Expressions.mood(m)
        return if (e.has(p)) e[p] else Expressions.NEUTRAL[p]
    }

    private val n = Expressions.NEUTRAL

    @Test
    fun `neutral defines every parameter`() {
        for (p in 0 until P.COUNT) assertTrue(P.NAMES[p], n.has(p))
        assertEquals(P.COUNT, P.NAMES.size)
        assertEquals(P.HEAD_FOLLOW_Y, P.indexOf("headFollowY"))
    }

    @Test
    fun `every mood except idle differs from neutral`() {
        for (m in FaceMood.entries) {
            // Заставка — те же глаза, что в покое (фигуры выбирает FaceLife).
            if (m == FaceMood.IDLE || m == FaceMood.SLEEP) continue
            val differs = (0 until P.COUNT).any { abs(value(m, it) - n[it]) > 1e-3f }
            assertTrue("$m looks like neutral", differs)
        }
    }

    @Test
    fun `every pair of moods is distinguishable`() {
        // Заставка рисуется глазами покоя (фигуру выбирает FaceLife) — её с покоем не сравниваем.
        val moods = FaceMood.entries.filter { it != FaceMood.SLEEP }
        for (i in moods.indices) for (j in i + 1 until moods.size) {
            val a = moods[i]
            val b = moods[j]
            val differs = (0 until P.COUNT).any { abs(value(a, it) - value(b, it)) > 1e-3f }
            assertTrue("$a == $b", differs)
        }
    }

    @Test
    fun `expressions say what the brief asks`() {
        // Радость — глаза-дуги и улыбка.
        assertTrue(value(FaceMood.HAPPY, P.SMILE_L) >= 0.9f)
        assertTrue(value(FaceMood.HAPPY, P.MOUTH_SMILE) > n[P.MOUTH_SMILE] + 15f)
        // Слушает — глаза шире, реагирует на голос.
        assertTrue(value(FaceMood.LISTENING, P.EYE_H) > n[P.EYE_H])
        assertEquals(1f, value(FaceMood.LISTENING, P.LISTEN), 0f)
        // Думает (прототип `think`) — полоски и «?», всё на месте.
        assertEquals(0f, value(FaceMood.THINKING, P.GAZE_Y), 0f)
        assertEquals(0f, value(FaceMood.THINKING, P.HEAD_TILT), 0f)
        assertEquals(0f, value(FaceMood.THINKING, P.OPEN_L), 0f)
        assertEquals(1f, value(FaceMood.THINKING, P.QUESTION), 0f)
        assertTrue(abs(value(FaceMood.THINKING, P.BROW_L) - value(FaceMood.THINKING, P.BROW_R)) > 10f)
        // Говорит — рот от звука.
        assertEquals(1f, value(FaceMood.SPEAKING, P.TALK), 0f)
        // Едет (прототип `guide`) — глаза сдвинуты по ходу, решительные брови.
        assertEquals(ProtoExpr.GUIDE, Expressions.proto(FaceMood.TRAVEL))
        assertTrue(value(FaceMood.TRAVEL, P.BROW_TILT_L) < 0f)
        // Не понял (прототип `confused`) — полоски и «?», без наклона.
        assertEquals(1f, value(FaceMood.CONFUSED, P.QUESTION), 0f)
        assertEquals(0f, value(FaceMood.CONFUSED, P.OPEN_L), 0f)
        assertEquals(0f, value(FaceMood.CONFUSED, P.HEAD_TILT), 0f)
        // Ошибка — огорчение формой: веко и брови «домиком», уголки вниз, капля.
        assertTrue(value(FaceMood.ERROR, P.MOUTH_SMILE) < 0f)
        assertTrue(value(FaceMood.ERROR, P.LID_L) > 0.2f && value(FaceMood.ERROR, P.LID_ANGLE) > 0f)
        assertTrue(value(FaceMood.ERROR, P.BROW_TILT_L) > 0f)
        assertEquals(1f, value(FaceMood.ERROR, P.SWEAT), 0f)
        // Заставка — прототип «никогда не спит»: глаза открыты, без «z».
        assertEquals(1f, value(FaceMood.SLEEP, P.OPEN_L), 0f)
        assertEquals(0f, value(FaceMood.SLEEP, P.ZZZ), 0f)
        // Зевок — рот широко.
        assertEquals(1f, value(FaceMood.YAWN, P.MOUTH_OPEN), 0f)
        // Пробуждение — глаза распахнуты.
        assertTrue(value(FaceMood.WAKE, P.EYE_H) > n[P.EYE_H] + 10f)
        // Поглаживание — жмурится дугами вверх, румянец.
        assertEquals(0f, value(FaceMood.PETTED, P.OPEN_L), 0f)
        assertTrue(value(FaceMood.PETTED, P.ARC) > 0f)
        assertEquals(1f, value(FaceMood.PETTED, P.BLUSH), 0f)
        // Удивление — глаза и брови вверх, рот «о».
        assertTrue(value(FaceMood.SURPRISED, P.EYE_H) > value(FaceMood.LISTENING, P.EYE_H))
        assertTrue(value(FaceMood.SURPRISED, P.BROW_L) > 25f)
        assertTrue(value(FaceMood.SURPRISED, P.MOUTH_OPEN) > 0.5f && value(FaceMood.SURPRISED, P.MOUTH_ROUND) == 1f)
    }

    @Test
    fun `moods draw the prototype figures`() {
        assertEquals(ProtoExpr.NEUTRAL, Expressions.proto(FaceMood.IDLE))
        assertEquals(ProtoExpr.LISTEN, Expressions.proto(FaceMood.LISTENING))
        assertEquals(ProtoExpr.THINK, Expressions.proto(FaceMood.THINKING))
        assertEquals(ProtoExpr.SPEAK, Expressions.proto(FaceMood.SPEAKING))
        assertEquals(ProtoExpr.WIDE, Expressions.proto(FaceMood.LOOKING))
        assertEquals(ProtoExpr.GUIDE, Expressions.proto(FaceMood.TRAVEL))
        assertEquals(ProtoExpr.HAPPY, Expressions.proto(FaceMood.HAPPY))
        assertEquals(ProtoExpr.CONFUSED, Expressions.proto(FaceMood.CONFUSED))
        assertEquals(ProtoExpr.SAD, Expressions.proto(FaceMood.ERROR))
        assertEquals(ProtoExpr.BLUSH, Expressions.proto(FaceMood.PETTED))
        assertEquals(ProtoExpr.SPARKLE, Expressions.proto(FaceMood.SPARKLE))
        assertEquals(ProtoExpr.DIZZY, Expressions.proto(FaceMood.DIZZY))
        assertEquals(ProtoExpr.BORED, Expressions.proto(FaceMood.SLEEP, ProtoExpr.BORED))
        // Моргают только овалы `bl`: обычное, слушает, ведёт.
        assertTrue(Expressions.blinks(ProtoExpr.NEUTRAL) && Expressions.blinks(ProtoExpr.LISTEN) && Expressions.blinks(ProtoExpr.GUIDE))
        assertFalse(Expressions.blinks(ProtoExpr.SPEAK) || Expressions.blinks(ProtoExpr.WIDE) || Expressions.blinks(ProtoExpr.HAPPY))
    }

    @Test
    fun `emotion overlays never switch speech reactions`() {
        for (e in FaceEmotion.entries) {
            val o = Expressions.overlay(e) ?: continue
            assertFalse("$e", o.has(P.TALK))
            assertFalse("$e", o.has(P.LISTEN))
        }
        assertEquals(null, Expressions.overlay(FaceEmotion.NEUTRAL))
        // Удивление в покое — рот «о».
        assertTrue(Expressions.overlay(FaceEmotion.SURPRISED)!![P.MOUTH_OPEN] > 0.2f)
    }

    @Test
    fun `overlay blends by weight`() {
        val out = FloatArray(P.COUNT)
        n.applyTo(out)
        Expressions.overlay(FaceEmotion.HAPPY)!!.blendInto(out, 0.5f)
        assertEquals(0.375f, out[P.SMILE_L], 1e-4f)
        // Неупомянутые параметры не меняются.
        assertEquals(n[P.EYE_W], out[P.EYE_W], 0f)
        val before = out.copyOf()
        Expressions.overlay(FaceEmotion.SAD)!!.blendInto(out, 0f)
        assertTrue(before.contentEquals(out))
    }

    @Test
    fun `screensaver keeps the normal tempo, waking is perky`() {
        assertEquals(1f, Expressions.tempo(FaceMood.SLEEP), 0f)
        assertTrue(Expressions.tempo(FaceMood.WAKE) < 1f)
        assertEquals(1f, Expressions.tempo(FaceMood.IDLE), 0f)
    }

    @Test
    fun `names parse case-insensitively`() {
        assertEquals(FaceMood.TRAVEL, FaceMood.fromName(" travel "))
        assertEquals(FaceEmotion.EXCITED, FaceEmotion.fromName("Excited"))
        assertEquals(FaceCue.TRIP_FAILED, FaceCue.fromName("trip_failed"))
        assertEquals(null, FaceMood.fromName("nope"))
    }
}
