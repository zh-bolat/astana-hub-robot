package kz.astanahub.robot.ui.face

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kz.astanahub.robot.ui.face.FaceParam as P

/**
 * Геометрия лица в единицах [FaceLayout] (до трансформации «головы»): центры, размеры и контрольные точки
 * глаз, бровей и рта. Чистая математика (тестируется на JVM), без аллокаций — поля переиспользуются.
 *
 * Глаз и рот — одна и та же фигура: два угла и две вершины (верхняя, нижняя), между ними — четверти эллипса
 * (кубические Безье, κ = 0.5523). Фигура рисуется заливкой и штрихом [FaceLayout.STROKE] со скруглением: когда
 * верх и низ совпадают, заливки нет и остаётся штрих — закрытый глаз, глаз-дуга, рот-линия. Так любые формы
 * морфятся друг в друга непрерывно.
 */
class FaceGeometry {

    /** Глаз (центр, полуширина, высоты верхней/нижней дуги от линии углов, веко). */
    class Eye {
        var cx = 0f
        var cy = 0f
        /** Полуширина. */
        var a = 0f
        /** Полная полувысота открытого глаза (для свечения и века). */
        var h = 0f
        /** Сдвиг линии углов от центра (вниз при закрывании). */
        var yc = 0f
        /** Верхняя дуга: вершина на yc − top (> 0 — выпукла вверх). */
        var top = 0f
        /** Нижняя дуга: вершина на yc + bottom (< 0 — вогнута вверх: глаз-дуга). */
        var bottom = 0f
        /** Веко: прямая y = lidY + lidSlope·x (в координатах глаза), всё выше — закрыто. */
        var lid = 0f
        var lidY = 0f
        var lidSlope = 0f
    }

    /** Бровь: квадратичная кривая inner → control → outer. */
    class Brow {
        var x0 = 0f
        var y0 = 0f
        var cx = 0f
        var cy = 0f
        var x1 = 0f
        var y1 = 0f
        var alpha = 1f
    }

    /** Рот: углы, вершины верхней и нижней губы. */
    class Mouth {
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f
        var ax = 0f
        var topY = 0f
        var bottomY = 0f
    }

    val left = Eye()
    val right = Eye()
    val browLeft = Brow()
    val browRight = Brow()
    val mouth = Mouth()

    fun compute(pose: FacePose) {
        val v = pose.v
        eye(left, v, -1, pose.eyeStretch)
        eye(right, v, 1, pose.eyeStretch)
        brow(browLeft, v, left, -1)
        brow(browRight, v, right, 1)
        mouth(v)
    }

    private fun eye(e: Eye, v: FloatArray, side: Int, stretch: Float) {
        val s = side.toFloat()
        val gx = v[P.GAZE_X]
        val gy = v[P.GAZE_Y]
        // Перспектива: смотрит вправо — правый (для зрителя) глаз дальше и меньше, левый ближе.
        val perspective = 1f - FaceLayout.PERSPECTIVE * gx * s
        val asym = 1f - v[P.EYE_ASYM] * s
        val size = perspective * asym * (1f + v[P.POP])
        e.a = v[P.EYE_W] * size * (1f + stretch)
        e.h = v[P.EYE_H] * size * (1f - 0.5f * stretch)
        e.cx = s * (FaceLayout.EYE_X + v[P.EYE_SPREAD]) + gx * FaceLayout.GAZE_RANGE_X - s * abs(gx) * 12f
        e.cy = FaceLayout.EYE_Y + v[P.EYE_Y] + gy * FaceLayout.GAZE_RANGE_Y

        val open = if (side < 0) v[P.OPEN_L] else v[P.OPEN_R]
        val smile = if (side < 0) v[P.SMILE_L] else v[P.SMILE_R]
        // Закрытый глаз — дуга высотой ARC·0.42·h (+ «∩», − «∪»).
        val closedArc = v[P.ARC] * e.h * 0.42f
        val openTop = e.h
        val openBottom = e.h * (1f - 1.85f * smile)
        e.top = lerp(closedArc, openTop, open)
        e.bottom = lerp(-closedArc, openBottom, open)
        e.yc = (1f - open) * e.h * 0.18f

        e.lid = if (side < 0) v[P.LID_L] else v[P.LID_R]
        val stroke = FaceLayout.STROKE
        e.lidY = lerp(-e.h - stroke, e.h * 0.9f, e.lid)
        // Положительный угол — внешний уголок ниже; внешний у левого глаза слева (x < 0).
        e.lidSlope = tan(Math.toRadians(v[P.LID_ANGLE].toDouble())).toFloat() * s
    }

    private fun brow(b: Brow, v: FloatArray, eye: Eye, side: Int) {
        val s = side.toFloat()
        val gx = v[P.GAZE_X]
        val gy = v[P.GAZE_Y]
        val lift = if (side < 0) v[P.BROW_L] else v[P.BROW_R]
        val tilt = if (side < 0) v[P.BROW_TILT_L] else v[P.BROW_TILT_R]
        val len = v[P.BROW_LEN] * 0.5f
        val bx = s * (FaceLayout.EYE_X + v[P.EYE_SPREAD]) + gx * FaceLayout.GAZE_RANGE_X * FaceLayout.BROW_FOLLOW
        val baseH = eye.h.coerceAtLeast(v[P.EYE_H] * 0.8f)
        val by = FaceLayout.EYE_Y + v[P.EYE_Y] + gy * FaceLayout.GAZE_RANGE_Y * FaceLayout.BROW_FOLLOW -
            baseH - FaceLayout.BROW_GAP - lift
        val rad = Math.toRadians(tilt.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        // Внутренний конец — к центру лица (у левой брови справа). Положительный наклон поднимает его.
        b.x0 = bx - s * c * len
        b.y0 = by - sn * len
        b.x1 = bx + s * c * len
        b.y1 = by + sn * len
        // Изгиб: контрольная точка по нормали «вверх» на 2·arch (вершина кривой поднимется на arch).
        var nx = -(b.y1 - b.y0)
        var ny = b.x1 - b.x0
        val n = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-3f)
        nx /= n
        ny /= n
        if (ny > 0f) {
            nx = -nx
            ny = -ny
        }
        val arch = v[P.BROW_ARCH]
        b.cx = (b.x0 + b.x1) * 0.5f + nx * 2f * arch
        b.cy = (b.y0 + b.y1) * 0.5f + ny * 2f * arch
        b.alpha = v[P.BROW_ALPHA]
    }

    private fun mouth(v: FloatArray) {
        val m = mouth
        val gx = v[P.GAZE_X]
        val gy = v[P.GAZE_Y]
        val w = v[P.MOUTH_W]
        val smile = v[P.MOUTH_SMILE]
        val skew = v[P.MOUTH_SKEW]
        val cx = v[P.MOUTH_X] + gx * FaceLayout.GAZE_RANGE_X * FaceLayout.MOUTH_FOLLOW
        val cy = FaceLayout.MOUTH_Y + v[P.MOUTH_Y] + gy * FaceLayout.GAZE_RANGE_Y * FaceLayout.MOUTH_FOLLOW * 0.6f
        m.lx = cx - w
        m.rx = cx + w
        m.ly = cy - smile + skew * 0.5f
        m.ry = cy - smile - skew * 0.5f
        m.ax = cx
        val open = v[P.MOUTH_OPEN] * MOUTH_OPEN_H
        val topShare = lerp(0.22f, 0.5f, v[P.MOUTH_ROUND])
        m.topY = cy - open * topShare
        m.bottomY = cy + open * (1f - topShare)
    }

    companion object {
        /** Полное раскрытие рта (ед.). */
        const val MOUTH_OPEN_H = 72f

        /** κ — четверть эллипса кубической Безье. */
        const val KAPPA = 0.5523f

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
