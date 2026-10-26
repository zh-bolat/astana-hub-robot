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
import kotlin.math.min
import kotlin.math.sin
import kz.astanahub.robot.ui.face.FaceParam as P

/**
 * Рисует лицо по [FacePose] в стиле прототипа (`docs/design/robot-mvp.html`): на чёрном — только глаза, голубые
 * (#3AA8F5) со свечением и светлым бликом; выражения — формой глаз (овалы, полоски «думает», дуги «^ ^», «∪» сна),
 * «?», слеза, румянец, «zz»-кружки. Рта и бровей нет (параметры движка остаются — рисуются только глаза).
 * Все Paint/Path/шейдеры созданы заранее — в [draw] нет аллокаций; свечение — готовые радиальные градиенты.
 */
class FaceRenderer {
    private val geometry = FaceGeometry()

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EYE
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = FaceLayout.STROKE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EYE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = FaceLayout.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EYE }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLINT }

    /** Свечение глаз (прототип: drop-shadow 6 px 85 % + 18 px 45 %): круг радиуса 1 с градиентом, масштабируется. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(150, 58, 168, 245), Color.argb(70, 40, 140, 240), Color.argb(0, 40, 140, 240)),
            floatArrayOf(0.55f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Румянец (прототип «смущение»): размытый красный овал. */
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(180, 255, 45, 61), Color.argb(90, 255, 45, 61), Color.argb(0, 255, 45, 61)),
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
    private val ovalRect = RectF()

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

    /** Слеза (прототип «плачет»): капля острым верхом. */
    private val tearPath = Path().apply {
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

        drawBlush(canvas, v)
        drawEye(canvas, geometry.left, glow)
        drawEye(canvas, geometry.right, glow)
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
        // Свечение: эллипс чуть больше глаза; у закрытого — сплющен до полоски.
        val half = FaceLayout.STROKE * 0.5f
        val glowH = ((e.top + e.bottom) * 0.5f + half).coerceAtLeast(half)
        glowSpot(canvas, 0f, e.yc + (e.bottom - e.top) * 0.5f, (e.a + half) * GLOW_SCALE, glowH * GLOW_SCALE, glow * GLOW_STRENGTH)

        FaceRendererShapes.lens(path, -e.a, e.yc, e.a, e.yc, 0f, e.yc - e.top, e.yc + e.bottom)
        canvas.drawPath(path, eyePaint)

        // Блик (прототип: светло-голубой овал сверху-слева) — пока глаз открыт.
        val open = ((e.top + e.bottom) / (2f * e.h.coerceAtLeast(1f))).coerceIn(0f, 1f)
        if (open > 0.25f) {
            val rx = e.a + half
            val ry = e.h + half
            glintPaint.alpha = (255 * GLINT_ALPHA * ((open - 0.25f) / 0.35f).coerceIn(0f, 1f)).toInt()
            val gx = -rx * 0.36f
            val gy = e.yc + (e.bottom - e.top) * 0.5f - ry * 0.4f * open
            ovalRect.set(gx - rx * 0.22f, gy - ry * 0.17f, gx + rx * 0.22f, gy + ry * 0.17f)
            canvas.drawOval(ovalRect, glintPaint)
        }

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

    /** Пятно света под элементом: эллипс (rx, ry), яркость [strength] 0..1. */
    private fun glowSpot(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, strength: Float) {
        if (strength <= 0.01f) return
        glowPaint.alpha = (255 * strength.coerceIn(0f, 1f)).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    /** Румянец под глазами (поглаживание): размытые красные овалы снаружи-снизу. */
    private fun drawBlush(canvas: Canvas, v: FloatArray) {
        val blush = v[P.BLUSH]
        if (blush <= 0.01f) return
        blushPaint.alpha = (255 * blush.coerceAtMost(1f)).toInt()
        for (side in -1..1 step 2) {
            val e = if (side < 0) geometry.left else geometry.right
            canvas.save()
            canvas.translate(e.cx + side * BLUSH_DX, e.cy + BLUSH_DY)
            canvas.scale(BLUSH_RX, BLUSH_RY)
            canvas.drawCircle(0f, 0f, 1f, blushPaint)
            canvas.restore()
        }
    }

    private fun drawDecor(canvas: Canvas, pose: FacePose) {
        val v = pose.v

        // «?» справа над правым глазом (прототип: «думает», «не понял»): обводится, точка в конце, лёгкое покачивание.
        val q = v[P.QUESTION]
        if (q > 0.01f) {
            canvas.save()
            canvas.translate(geometry.right.cx + Q_DX, geometry.right.cy + Q_DY)
            canvas.rotate(-6f + 5f * sin(pose.wobble))
            val k = 0.62f + 0.18f * q
            canvas.scale(k, k)
            decorPath.rewind()
            questionMeasure.getSegment(0f, questionLength * q.coerceAtMost(1f), decorPath, true)
            strokePaint.strokeWidth = QUESTION_STROKE
            strokePaint.alpha = (255 * q).toInt().coerceIn(0, 255)
            canvas.drawPath(decorPath, strokePaint)
            val dot = ((q - 0.8f) / 0.2f).coerceIn(0f, 1f)
            if (dot > 0f) {
                fillPaint.alpha = (255 * dot).toInt()
                canvas.drawCircle(0f, 64f, 15f * dot, fillPaint)
                fillPaint.alpha = 255
            }
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
            canvas.restore()
        }

        // Слеза под правым глазом (прототип «плачет»): медленно стекает и гаснет.
        val sweat = v[P.SWEAT]
        if (sweat > 0.01f) {
            val slide = pose.sweatPhase
            canvas.save()
            canvas.translate(geometry.right.cx + geometry.right.a * 0.6f, geometry.right.cy + TEAR_DY + 26f * slide)
            val k = 0.85f + 0.3f * sweat
            canvas.scale(k, k)
            fillPaint.alpha = (255 * sweat * (1f - 0.6f * slide)).toInt().coerceIn(0, 255)
            canvas.drawPath(tearPath, fillPaint)
            fillPaint.alpha = 255
            canvas.restore()
        }

        // «zz» сна (прототип): кружки поднимаются справа над головой и тают.
        val z = v[P.ZZZ]
        if (z > 0.01f) {
            strokePaint.strokeWidth = Z_STROKE
            for (i in 0 until 3) {
                val p = (pose.zPhase + i / 3f) % 1f
                val r = 10f + 16f * p
                val x = geometry.right.cx + 150f + 60f * p
                val y = geometry.right.cy - 90f - 160f * p
                val a = sin(PI.toFloat() * p) * z
                if (a <= 0.01f) continue
                strokePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, r, strokePaint)
            }
            strokePaint.strokeWidth = FaceLayout.STROKE
            strokePaint.alpha = 255
        }
    }

    companion object {
        private const val K = FaceGeometry.KAPPA

        /** Голубые глаза прототипа и их блик. */
        const val EYE = 0xFF3AA8F5.toInt()
        private const val GLINT = 0xFFCDEFFF.toInt()
        private const val GLINT_ALPHA = 0.75f

        /** Насколько свечение глаза больше самого глаза и его яркость. */
        private const val GLOW_SCALE = 1.32f
        private const val GLOW_STRENGTH = 0.95f
        private const val LID_PAD = 70f
        private const val QUESTION_STROKE = 24f
        private const val Q_DX = 150f
        private const val Q_DY = -60f
        private const val TEAR_DY = 120f
        private const val BLUSH_DX = 60f
        private const val BLUSH_DY = 105f
        private const val BLUSH_RX = 80f
        private const val BLUSH_RY = 42f
        private const val Z_STROKE = 10f
        private const val RING_RADIUS = 56f
        private const val RING_STROKE = 7f
    }
}

/** Общие фигуры лица. */
object FaceRendererShapes {
    private const val K = FaceGeometry.KAPPA

    /**
     * «Линза»: углы (lx, ly), (rx, ry), вершины (ax, top) и (ax, bottom), четверти эллипса между ними.
     * Совпали вершины — вырождается в кривую (глаз-полоска, дуга).
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
