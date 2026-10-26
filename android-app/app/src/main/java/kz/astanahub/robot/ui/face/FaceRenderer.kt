package kz.astanahub.robot.ui.face

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kz.astanahub.robot.ui.face.FaceParam as P

/**
 * Рисует лицо по [FacePose] на android.graphics.Canvas: белым по чёрному, ровные штрихи со скруглёнными
 * концами, мягкое свечение. Все Paint/Path/шейдеры созданы заранее — в [draw] нет аллокаций.
 *
 * Свечение без размытия (RenderEffect только с API 31, на роботе может быть API 28): у глаз — радиальный
 * градиент под глазом, у штрихов — два широких полупрозрачных прохода. Одинаково на всех версиях Android.
 */
class FaceRenderer {
    private val geometry = FaceGeometry()

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = FaceLayout.STROKE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = FaceLayout.STROKE
    }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /**
     * Свечение: круг радиуса 1 с радиальным градиентом, масштабируется под элемент (шейдер не пересоздаётся).
     * Мягкое пятно света, а не обводка: ступенчатые «ореолы» из широких штрихов читаются как контур.
     */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Кольцо удержания (вход в админку): тонкий белый штрих вокруг пальца. */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringRect = RectF()

    private val path = Path()
    private val lidPath = Path()
    private val decorPath = Path()

    /** «?» — крючок со стержнем (обводится постепенно) и точка. */
    private val questionPath = Path().apply {
        moveTo(-38f, -52f)
        cubicTo(-38f, -96f, 40f, -96f, 40f, -52f)
        cubicTo(40f, -22f, 0f, -18f, 0f, 14f)
        lineTo(0f, 30f)
    }
    private val questionMeasure = PathMeasure(questionPath, false)
    private val questionLength = questionMeasure.length

    /** Капля (ошибка): контур. */
    private val sweatPath = Path().apply {
        moveTo(0f, -30f)
        cubicTo(12f, -12f, 22f, 0f, 22f, 12f)
        cubicTo(22f, 26f, 12f, 34f, 0f, 34f)
        cubicTo(-12f, 34f, -22f, 26f, -22f, 12f)
        cubicTo(-22f, 0f, -12f, -12f, 0f, -30f)
        close()
    }

    /** Масштаб последнего кадра: px на единицу лица. */
    var unit: Float = 1f
        private set

    fun draw(canvas: Canvas, pose: FacePose, width: Float, height: Float) {
        val v = pose.v
        geometry.compute(pose)
        val u = min(width / FaceLayout.WIDTH, height / FaceLayout.HEIGHT)
        unit = u
        val glow = v[P.GLOW].coerceIn(0f, 1.2f)

        canvas.save()
        canvas.translate(width * 0.5f + v[P.HEAD_X] * u, height * 0.5f + v[P.HEAD_Y] * u)
        canvas.rotate(v[P.HEAD_TILT])
        val scale = u * v[P.HEAD_SCALE]
        canvas.scale(scale * (1f + v[P.SQUASH]), scale * (1f - v[P.SQUASH]))

        drawEye(canvas, geometry.left, glow)
        drawEye(canvas, geometry.right, glow)
        drawBrow(canvas, geometry.browLeft, glow)
        drawBrow(canvas, geometry.browRight, glow)
        drawMouth(canvas, glow)
        drawDecor(canvas, pose)
        canvas.restore()
        drawHoldRing(canvas, pose, width, height, u)
    }

    /** Кольцо удержания у пальца, в координатах экрана: дорожка и заполнение по часовой от 12 часов. */
    private fun drawHoldRing(canvas: Canvas, pose: FacePose, width: Float, height: Float, u: Float) {
        val a = pose.holdAlpha
        if (a <= 0.01f) return
        val cx = width * 0.5f * (1f + pose.touchX)
        val cy = height * 0.5f * (1f + pose.touchY)
        val r = RING_RADIUS * u * (0.9f + 0.1f * a)
        ringRect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.strokeWidth = RING_STROKE * u
        ringPaint.alpha = (255 * 0.2f * a).toInt()
        canvas.drawOval(ringRect, ringPaint)
        val sweep = 360f * pose.holdProgress
        if (sweep > 0.5f) {
            ringPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            canvas.drawArc(ringRect, -90f, sweep, false, ringPaint)
        }
    }

    private fun drawEye(canvas: Canvas, e: FaceGeometry.Eye, glow: Float) {
        canvas.save()
        canvas.translate(e.cx, e.cy)
        // Свечение: эллипс чуть больше глаза; у закрытого — сплющен до линии.
        val glowH = ((e.top + e.bottom) * 0.5f).coerceAtLeast(FaceLayout.STROKE) * GLOW_SCALE
        glowSpot(canvas, 0f, e.yc + (e.bottom - e.top) * 0.5f, e.a * GLOW_SCALE, glowH, 0f, glow * 0.9f)

        lens(path, -e.a, e.yc, e.a, e.yc, 0f, e.yc - e.top, e.yc + e.bottom)
        canvas.drawPath(path, shapePaint)

        // Веко — чёрная кромка поверх глаза и его свечения.
        if (e.lid > 0.004f) {
            val x = e.a + FaceLayout.STROKE + LID_PAD
            val topY = -e.h * GLOW_SCALE - FaceLayout.STROKE - LID_PAD
            lidPath.rewind()
            lidPath.moveTo(-x, topY)
            lidPath.lineTo(x, topY)
            lidPath.lineTo(x, e.lidY + e.lidSlope * x)
            lidPath.lineTo(-x, e.lidY - e.lidSlope * x)
            lidPath.close()
            canvas.drawPath(lidPath, lidPaint)
        }
        canvas.restore()
    }

    private fun drawBrow(canvas: Canvas, b: FaceGeometry.Brow, glow: Float) {
        if (b.alpha <= 0.01f) return
        val dx = b.x1 - b.x0
        val dy = b.y1 - b.y0
        val half = sqrt(dx * dx + dy * dy) * 0.5f
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        glowSpot(canvas, (b.x0 + b.x1) * 0.5f, (b.y0 + b.y1) * 0.5f, half + GLOW_PAD, GLOW_PAD * 1.4f, angle, glow * STROKE_GLOW * b.alpha)
        path.rewind()
        path.moveTo(b.x0, b.y0)
        path.quadTo(b.cx, b.cy, b.x1, b.y1)
        strokePaint.alpha = (255 * b.alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(path, strokePaint)
        strokePaint.alpha = 255
    }

    private fun drawMouth(canvas: Canvas, glow: Float) {
        val m = geometry.mouth
        val open = (m.bottomY - m.topY) * 0.5f
        glowSpot(canvas, m.ax, (m.topY + m.bottomY) * 0.5f, (m.rx - m.lx) * 0.5f + GLOW_PAD, open + GLOW_PAD * 1.4f, 0f, glow * STROKE_GLOW)
        lens(path, m.lx, m.ly, m.rx, m.ry, m.ax, m.topY, m.bottomY)
        canvas.drawPath(path, shapePaint)
    }

    /** Пятно света под элементом: эллипс (rx, ry) с поворотом [degrees], яркость [strength] 0..1. */
    private fun glowSpot(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, degrees: Float, strength: Float) {
        if (strength <= 0.01f) return
        glowPaint.alpha = (255 * strength.coerceIn(0f, 1f)).toInt()
        canvas.save()
        canvas.translate(x, y)
        if (degrees != 0f) canvas.rotate(degrees)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    private fun drawDecor(canvas: Canvas, pose: FacePose) {
        val v = pose.v

        // «?» у левой брови: обводится штрихом, точка появляется в конце, лёгкое покачивание.
        val q = v[P.QUESTION]
        if (q > 0.01f) {
            canvas.save()
            canvas.translate(-FaceLayout.EYE_X - 160f, FaceLayout.EYE_Y - 140f)
            canvas.rotate(-8f + 5f * sin(pose.wobble))
            val k = 0.75f + 0.25f * q
            canvas.scale(k, k)
            decorPath.rewind()
            questionMeasure.getSegment(0f, questionLength * q.coerceAtMost(1f), decorPath, true)
            strokePaint.strokeWidth = QUESTION_STROKE
            strokePaint.alpha = (255 * q).toInt().coerceIn(0, 255)
            canvas.drawPath(decorPath, strokePaint)
            val dot = ((q - 0.8f) / 0.2f).coerceIn(0f, 1f)
            if (dot > 0f) {
                shapePaint.strokeWidth = 0f
                shapePaint.alpha = (255 * dot).toInt()
                canvas.drawCircle(0f, 62f, 13f * dot, shapePaint)
                shapePaint.strokeWidth = FaceLayout.STROKE
                shapePaint.alpha = 255
            }
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
            canvas.restore()
        }

        // «///» на щеках.
        val blush = v[P.BLUSH]
        if (blush > 0.01f) {
            strokePaint.strokeWidth = BLUSH_STROKE
            strokePaint.alpha = (255 * 0.75f * blush).toInt().coerceIn(0, 255)
            drawBlush(canvas, geometry.left, -1f)
            drawBlush(canvas, geometry.right, 1f)
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
        }

        // Капля у правой брови: медленно сползает.
        val sweat = v[P.SWEAT]
        if (sweat > 0.01f) {
            val slide = pose.sweatPhase
            canvas.save()
            canvas.translate(geometry.browRight.x1 + 46f, geometry.browRight.y1 + 10f + 18f * slide)
            val k = 0.7f + 0.3f * sweat
            canvas.scale(k, k)
            strokePaint.strokeWidth = SWEAT_STROKE
            strokePaint.alpha = (255 * sweat * (1f - 0.5f * slide)).toInt().coerceIn(0, 255)
            canvas.drawPath(sweatPath, strokePaint)
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
            canvas.restore()
        }

        // «z z z» поднимаются справа над головой и тают.
        val z = v[P.ZZZ]
        if (z > 0.01f) {
            strokePaint.strokeWidth = Z_STROKE
            for (i in 0 until 3) {
                val p = (pose.zPhase + i / 3f) % 1f
                val size = 14f + 18f * p
                val x = FaceLayout.EYE_X + 150f + 70f * p
                val y = FaceLayout.EYE_Y - 140f - 170f * p
                val a = sin(PI.toFloat() * p) * z
                if (a <= 0.01f) continue
                strokePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
                decorPath.rewind()
                decorPath.moveTo(x - size, y - size)
                decorPath.lineTo(x + size, y - size)
                decorPath.lineTo(x - size, y + size)
                decorPath.lineTo(x + size, y + size)
                canvas.drawPath(decorPath, strokePaint)
            }
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
        }
    }

    private fun drawBlush(canvas: Canvas, e: FaceGeometry.Eye, side: Float) {
        val bx = e.cx + side * 18f
        val by = e.cy + e.h + 52f
        decorPath.rewind()
        for (i in -1..1) {
            val x = bx + i * 22f
            decorPath.moveTo(x - 9f, by + 13f)
            decorPath.lineTo(x + 9f, by - 13f)
        }
        canvas.drawPath(decorPath, strokePaint)
    }

    companion object {
        private const val K = FaceGeometry.KAPPA

        /** Насколько свечение глаза больше самого глаза. */
        private const val GLOW_SCALE = 1.65f
        /** Насколько пятно света выходит за штрих (ед.) и его яркость относительно глаз. */
        private const val GLOW_PAD = 30f
        private const val STROKE_GLOW = 0.55f
        private const val LID_PAD = 70f
        private const val QUESTION_STROKE = 22f
        private const val BLUSH_STROKE = 9f
        private const val SWEAT_STROKE = 9f
        private const val Z_STROKE = 9f
        private const val RING_RADIUS = 56f
        private const val RING_STROKE = 7f

        /**
         * «Линза»: углы (lx, ly), (rx, ry), вершины (ax, top) и (ax, bottom), четверти эллипса между ними.
         * Совпали вершины — вырождается в кривую (глаз-линия, рот-штрих).
         */
        fun lens(p: Path, lx: Float, ly: Float, rx: Float, ry: Float, ax: Float, top: Float, bottom: Float) {
            p.rewind()
            p.moveTo(lx, ly)
            p.cubicTo(lx, ly + K * (top - ly), ax + K * (lx - ax), top, ax, top)
            p.cubicTo(ax + K * (rx - ax), top, rx, ry + K * (top - ry), rx, ry)
            p.cubicTo(rx, ry + K * (bottom - ry), ax + K * (rx - ax), bottom, ax, bottom)
            p.cubicTo(ax + K * (lx - ax), bottom, lx, ly + K * (bottom - ly), lx, ly)
            p.close()
        }
    }
}
