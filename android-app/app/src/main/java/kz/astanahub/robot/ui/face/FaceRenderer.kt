package kz.astanahub.robot.ui.face

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import kotlin.math.min
import kz.astanahub.robot.ui.face.FaceParam as P

/**
 * Рисует лицо точно как прототип (`docs/design/robot-mvp.html`, `EXPR`): на чёрном — только голубые глаза (#3AA8F5)
 * со свечением; каждое выражение — своя фигура из путей прототипа в его поле 200 × 100 (глаза в 50 и 150):
 * овалы с бликом (обычное, слушает, говорит, ведёт, «смотрит» с искоркой), полоски и «?» («думает», «не понял»),
 * галочки «^ ^» (радость), дуги с румянцем (смущение), звёзды (восторг), полоски с искоркой (скука), спирали
 * (нет связи), полоски со слезой (огорчение). Смена фигуры — «раскрытие» `exprIn`, моргание — `sblink`, речь —
 * сжатие в такт голосу, взгляд — сдвиг глаз `lookAt`.
 *
 * Все Paint/Path созданы заранее — в [draw] нет аллокаций.
 */
class FaceRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EYE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EYE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLINT; alpha = (255 * 0.75f).toInt() }

    /** Румянец (прототип: эллипс 16 × 8 #FF2D3D α .7, размытие σ 5): радиальный градиент по кругу радиуса 1. */
    private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(170, 255, 45, 61), Color.argb(120, 255, 45, 61), Color.argb(0, 255, 45, 61)),
            floatArrayOf(0f, 0.45f, 1f),
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
    private val oval = RectF()
    private val path = Path()

    // Фигуры прототипа (поле 200 × 100), готовые пути.
    private val caret = Path().apply {
        for (x in EYES_X) {
            moveTo(x - 15f, 58f)
            lineTo(x, 43f)
            lineTo(x + 15f, 58f)
        }
    }
    private val arcs = Path().apply {
        for (x in EYES_X) {
            moveTo(x - 18f, 52f)
            quadTo(x, 38f, x + 18f, 52f)
        }
    }
    private val stars = Array(2) { i -> svg(starData(EYES_X[i])) }
    private val spirals = Array(2) { i ->
        val x = EYES_X[i]
        svg("M$x 50 m0 -2 a2 2 0 1 1 -2 2 a6 6 0 1 1 6 6 a10 10 0 1 1 -10 -10 a14 14 0 1 1 14 14")
    }
    private val spiralBox = Array(2) { RectF().also { r -> spirals[it].computeBounds(r, true) } }
    private val wideSpark = svg("M28 26 l2 5 5 2 -5 2 -2 5 -2 -5 -5 -2 5 -2z")
    private val boredSpark = svg("M173 38 l1.5 4 4 1.5 -4 1.5 -1.5 4 -1.5 -4 -4 -1.5 4 -1.5z")
    private val tear = svg("M162 60 q8 11 0 16 q-8 -5 0 -16z")

    /** «?» прототипа — глиф Nunito 800 (путь строится, когда задан шрифт; без шрифта — системный жирный). */
    private val question26 = Path()
    private val question30 = Path()
    private var questionReady = false

    /** Шрифт «?» (Nunito ExtraBold); задаёт MainActivity. */
    var typeface: Typeface? = null
        set(value) {
            field = value
            questionReady = false
        }

    /** Масштаб последнего кадра: px на единицу лица. */
    var unit: Float = 1f
        private set

    fun draw(canvas: Canvas, pose: FacePose, width: Float, height: Float) {
        val v = pose.v
        val u = min(width / FaceLayout.WIDTH, height / FaceLayout.HEIGHT)
        unit = u
        if (!questionReady) buildQuestion()
        val now = pose.nowMs
        val e = pose.expr

        canvas.save()
        canvas.translate(width * 0.5f + v[P.HEAD_X] * u, height * 0.5f + v[P.HEAD_Y] * u)
        canvas.rotate(v[P.HEAD_TILT])
        val sc = u * v[P.HEAD_SCALE] * PROTO * (1f + v[P.POP])
        canvas.scale(sc * (1f + v[P.SQUASH]), sc * (1f - v[P.SQUASH]))
        // Теперь единица — единица поля прототипа, (0, 0) — его центр (100, 50).

        // Румянец смущения — вне группы глаз: не раскрывается и не ходит за взглядом.
        if (e == ProtoExpr.BLUSH) drawCheeks(canvas)

        // Группа глаз `.eyes`: взгляд (`lookAt`), раскрытие при смене (`exprIn`), моргание (`sblink`), речь (`talk`).
        canvas.save()
        canvas.translate(v[P.GAZE_X] * FaceLife.LOOK_RANGE_X, v[P.GAZE_Y] * FaceLife.LOOK_RANGE_Y)
        var sx = 1f
        var sy = 1f
        var alpha = 1f
        val inMs = (now - pose.exprSince).toFloat()
        if (inMs in 0f..EXPR_IN_MS) {
            val t = inMs / EXPR_IN_MS
            if (t < 0.55f) {
                val k = EXPR_EASE.transform(t / 0.55f)
                sx = lerp(1.04f, 0.98f, k)
                sy = lerp(0.08f, 1.06f, k)
                alpha = lerp(0.5f, 1f, k)
            } else {
                val k = EXPR_EASE.transform((t - 0.55f) / 0.45f)
                sx = lerp(0.98f, 1f, k)
                sy = lerp(1.06f, 1f, k)
            }
        }
        if (Expressions.blinks(e)) {
            val b = pose.blink
            sx *= 1f + 0.04f * b
            sy *= 1f - 0.92f * b
        }
        if (e == ProtoExpr.SPEAK) {
            // Прототип `talk` (scale 1.03 / .9); у нас — в такт настоящему голосу.
            val k = pose.talk.coerceIn(0f, 1f)
            sx *= 1f + 0.03f * k
            sy *= 1f - 0.1f * k
        }
        canvas.scale(sx, sy)
        canvas.translate(-100f, -50f)
        val a = (255 * alpha).toInt()
        fill.alpha = a
        stroke.alpha = a
        glint.alpha = (255 * 0.75f * alpha).toInt()
        drawShape(canvas, e, now, alpha)
        canvas.restore()

        canvas.restore()
        drawHoldRing(canvas, pose, width, height, u)
    }

    private fun drawShape(canvas: Canvas, e: ProtoExpr, now: Long, alpha: Float) {
        when (e) {
            ProtoExpr.NEUTRAL, ProtoExpr.SPEAK -> ovals(canvas, 0f, 18f, 22f, alpha)
            ProtoExpr.LISTEN -> ovals(canvas, 0f, 21f, 26f, alpha)
            ProtoExpr.GUIDE -> ovals(canvas, 14f, 18f, 22f, alpha)
            ProtoExpr.WIDE -> {
                ovals(canvas, 0f, 21f, 26f, alpha)
                glowFill(canvas, wideSpark, alpha)
                canvas.drawPath(wideSpark, fill)
            }
            ProtoExpr.THINK -> {
                for (x in EYES_X) bar(canvas, x - 17f, 46f, 34f, 9f, alpha)
                question(canvas, question26, 180f, 34f, alpha)
            }
            ProtoExpr.CONFUSED -> {
                bar(canvas, 30f, 48f, 34f, 9f, alpha)
                bar(canvas, 134f, 48f, 34f, 9f, alpha)
                question(canvas, question30, 178f, 40f, alpha)
            }
            ProtoExpr.BORED -> {
                for (x in EYES_X) bar(canvas, x - 18f, 46f, 36f, 9f, alpha)
                glowFill(canvas, boredSpark, alpha)
                canvas.drawPath(boredSpark, fill)
            }
            ProtoExpr.SAD -> {
                bar(canvas, 30f, 48f, 36f, 8f, alpha)
                bar(canvas, 132f, 48f, 36f, 8f, alpha)
                glowFill(canvas, tear, alpha)
                canvas.drawPath(tear, fill)
            }
            ProtoExpr.HAPPY -> strokePath(canvas, caret, 8f, alpha)
            ProtoExpr.BLUSH -> strokePath(canvas, arcs, 6f, alpha)
            ProtoExpr.SPARKLE -> {
                // `.heart`: «биение» .9 с — каждая звезда до 1,12 вокруг своего центра.
                val k = beat(now)
                for (i in 0..1) {
                    canvas.save()
                    canvas.scale(k, k, EYES_X[i], 50f)
                    glowFill(canvas, stars[i], alpha)
                    canvas.drawPath(stars[i], fill)
                    canvas.restore()
                }
            }
            ProtoExpr.DIZZY -> {
                // `.spin`: оборот за 2 с вокруг своего центра.
                val deg = (now % SPIN_MS).toFloat() / SPIN_MS * 360f
                for (i in 0..1) {
                    val box = spiralBox[i]
                    canvas.save()
                    canvas.rotate(deg, box.centerX(), box.centerY())
                    strokePath(canvas, spirals[i], 4f, alpha)
                    canvas.restore()
                }
            }
        }
    }

    /** Овалы глаз со свечением и бликом (`hl` прототипа). */
    private fun ovals(canvas: Canvas, dx: Float, rx: Float, ry: Float, alpha: Float) {
        for (x0 in EYES_X) {
            val x = x0 + dx
            oval.set(x - rx, 50f - ry, x + rx, 50f + ry)
            path.rewind()
            path.addOval(oval, Path.Direction.CW)
            glowFill(canvas, path, alpha)
            canvas.drawOval(oval, fill)
            val gx = x - rx * 0.36f
            val gy = 50f - ry * 0.4f
            oval.set(gx - rx * 0.22f, gy - ry * 0.17f, gx + rx * 0.22f, gy + ry * 0.17f)
            canvas.drawOval(oval, glint)
        }
    }

    private fun bar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        oval.set(x, y, x + w, y + h)
        path.rewind()
        path.addRoundRect(oval, 2f, 2f, Path.Direction.CW)
        glowFill(canvas, path, alpha)
        canvas.drawPath(path, fill)
    }

    private fun question(canvas: Canvas, glyph: Path, x: Float, baseline: Float, alpha: Float) {
        canvas.save()
        canvas.translate(x, baseline)
        glowFill(canvas, glyph, alpha)
        canvas.drawPath(glyph, fill)
        canvas.restore()
    }

    private fun strokePath(canvas: Canvas, p: Path, width: Float, alpha: Float) {
        for (i in HALO_WIDTH.indices) {
            halo.color = HALO_COLOR[i]
            halo.alpha = (HALO_ALPHA[i] * alpha * 255).toInt()
            halo.strokeWidth = width + HALO_WIDTH[i]
            canvas.drawPath(p, halo)
        }
        stroke.strokeWidth = width
        canvas.drawPath(p, stroke)
    }

    /**
     * Свечение прототипа (`drop-shadow(0 0 6px rgba(58,168,245,.85)) drop-shadow(0 0 18px rgba(40,140,240,.45))`):
     * несколько полупрозрачных обводок по контуру — мягкий ореол без размытия (дёшево, работает на любом GPU).
     */
    private fun glowFill(canvas: Canvas, p: Path, alpha: Float) {
        for (i in HALO_WIDTH.indices) {
            halo.color = HALO_COLOR[i]
            halo.alpha = (HALO_ALPHA[i] * alpha * 255).toInt()
            halo.strokeWidth = HALO_WIDTH[i]
            canvas.drawPath(p, halo)
        }
    }

    private fun drawCheeks(canvas: Canvas) {
        for (cx in CHEEKS_X) {
            canvas.save()
            canvas.translate(cx - 100f, 70f - 50f)
            canvas.scale(16f + 2f * BLUSH_SIGMA, 8f + 2f * BLUSH_SIGMA)
            canvas.drawCircle(0f, 0f, 1f, blush)
            canvas.restore()
        }
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

    /** «?» прототипа: `<text font-size="26|30" font-weight="800" text-anchor="middle">` → путь глифа. */
    private fun buildQuestion() {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@FaceRenderer.typeface ?: Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        for ((size, out) in listOf(26f to question26, 30f to question30)) {
            p.textSize = size
            val w = p.measureText("?")
            out.rewind()
            p.getTextPath("?", 0, 1, -w / 2f, 0f, out)
        }
        questionReady = true
    }

    companion object {
        /** Голубые глаза прототипа и их блик. */
        const val EYE = 0xFF3AA8F5.toInt()
        private const val GLINT = 0xFFCDEFFF.toInt()

        /** Единиц лица ([FaceLayout]) в единице поля прототипа: поле 1000 × 500 px на сцене 1600 × 900 → 5 px. */
        const val PROTO = 4.389f

        /** Центры глаз в поле прототипа. */
        private val EYES_X = floatArrayOf(50f, 150f)
        private val CHEEKS_X = floatArrayOf(32f, 168f)
        private const val BLUSH_SIGMA = 5f

        /** «Раскрытие» `exprIn`: .3 с, cubic-bezier(.3, 1.4, .5, 1) на отрезках 0–55 % и 55–100 %. */
        private const val EXPR_IN_MS = 300f
        private val EXPR_EASE = Bezier(0.3f, 1.4f, 0.5f, 1f)
        private val BEAT_EASE = Bezier.EASE_IN_OUT
        private const val BEAT_MS = 900L
        private const val SPIN_MS = 2_000L

        /** Ореол: ширины обводки (единицы поля), цвета и прозрачность — два drop-shadow прототипа. */
        private val HALO_WIDTH = floatArrayOf(1.6f, 3.4f, 6.4f, 10f)
        private val HALO_ALPHA = floatArrayOf(0.32f, 0.2f, 0.12f, 0.06f)
        private val HALO_COLOR = intArrayOf(
            Color.rgb(58, 168, 245), Color.rgb(58, 168, 245), Color.rgb(40, 140, 240), Color.rgb(40, 140, 240),
        )

        private const val RING_RADIUS = 56f
        private const val RING_STROKE = 7f

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        /** Масштаб `beat`: 1 → 1,12 (50 %) → 1, ease-in-out на отрезках. */
        private fun beat(now: Long): Float {
            val t = (now % BEAT_MS).toFloat() / BEAT_MS
            val k = if (t < 0.5f) BEAT_EASE.transform(t / 0.5f) else 1f - BEAT_EASE.transform((t - 0.5f) / 0.5f)
            return 1f + 0.12f * k
        }

        /** Звезда восторга прототипа (`sparkle`). */
        private fun starData(x: Float) =
            "M$x 26 Q${x + 3} 47 ${x + 12} 50 Q${x + 3} 53 $x 74 Q${x - 3} 53 ${x - 12} 50 Q${x - 3} 47 $x 26Z"

        private fun svg(d: String): Path = PathParser.createPathFromPathData(d)
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
