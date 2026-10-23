package kz.astanahub.robot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kz.astanahub.robot.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Лицо робота: объёмный голубой шар с двумя белыми глазами (без бровей, рта и зрачков) и тремя
 * мерцающими звёздочками ✦ рядом. Состояние = [Phase], выражение меняется плавно:
 * LISTENING — глаза крупнее, расходятся кольца; THINKING — глаза вверх-вправо, крутится дуга;
 * SPEAKING — глаза «улыбаются» (плоский низ), шар «дышит»; LOOKING — глаза шире;
 * NAVIGATING — глаза смотрят вправо (по ходу движения).
 * Эмоции ([emotion]): радость — глаза-полумесяцы, грусть — опущенные веки, удивление — круглые глаза,
 * восторг — подпрыгивает, «думаю» — взгляд вверх. Сон ([sleeping]) — глаза-щёлочки, медленное дыхание, «z».
 * Живость: случайное моргание (иногда двойное), взгляд в сторону, изредка подмигивание «^» ([wink]).
 * Размеры — в единицах HTML-прототипа: диаметр лица = 280 единиц.
 */
class RobotFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var phase: Phase = Phase.IDLE
        set(value) {
            if (field == value) return
            field = value
            phaseStarted = SystemClock.uptimeMillis()
            invalidate()
        }

    /** Эмоция (агент `/v1/chat` или события на роботе) — накладывается на фазу. */
    var emotion: Emotion = Emotion.NEUTRAL
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Сон (режим SLEEP): глаза закрыты, медленное дыхание, «z». */
    var sleeping: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var phaseStarted = SystemClock.uptimeMillis()
    private val born = SystemClock.uptimeMillis()
    private var lastFrame = 0L

    private val blue = Color.parseColor("#1F4FFF")
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eyeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(EYE_SHADOW_ALPHA, 8, 22, 90) }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = blue }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = blue
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val eyePath = Path()
    private val cutPath = Path()
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val radii = FloatArray(8)
    private val sparkle: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_sparkle)?.mutate()

    // Выражение глаз: текущее значение плавно догоняет цель (как CSS transition .25s).
    private val eye = EyePose()
    private val target = EyePose()

    // Моргание, взгляд в сторону, подмигивание: время событий (uptime, мс).
    private var nextBlinkAt = born + 1_500L
    private var blinkStart = 0L
    private var secondBlinkAt = 0L
    private var nextGlanceAt = born + 3_500L
    private var glanceUntil = 0L
    private var glanceX = 0f
    private var glanceY = 0f
    private var nextWinkRollAt = born + WINK_ROLL_MS
    private var winkStart = 0L

    /** Подмигнуть правым глазом «^», если уместно: не думает, не слушает и уже не подмигивает. */
    fun wink() {
        val now = SystemClock.uptimeMillis()
        if (!winkAllowed() || winkBusy(now)) return
        glanceUntil = 0L
        winkStart = now + WINK_DELAY_MS
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = radius()
        // Шар чуть светлее прежнего: свет из точки (34 %, 28 %), к краю — насыщенный синий.
        facePaint.shader = RadialGradient(
            cx - r * 0.32f, cy - r * 0.44f, r * 1.96f,
            intArrayOf(Color.parseColor("#7E9BFF"), Color.parseColor("#5A7DFF"), Color.parseColor("#4069FF"), Color.parseColor("#2A50E0")),
            floatArrayOf(0f, 0.38f, 0.60f, 1f), Shader.TileMode.CLAMP,
        )
        // Затемнение снизу (объём): прозрачно сверху, тёмно-синее у нижнего края.
        shadePaint.shader = RadialGradient(
            cx, cy - r * 0.35f, r * 1.45f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(80, 8, 22, 90)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        // Блик: мягкое размытое пятно (16 % → 8 % → 0), без резкого края.
        glossPaint.shader = RadialGradient(
            0f, 0f, r * 0.54f,
            intArrayOf(Color.argb(41, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        // Тень под шаром (на «пол»): синяя, размытая.
        dropPaint.shader = RadialGradient(
            0f, 0f, r * 1.1f,
            intArrayOf(Color.argb(77, 31, 79, 255), Color.argb(26, 31, 79, 255), Color.argb(0, 31, 79, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        // Свечение звёздочки; радиус 1 — масштабируется при рисовании.
        haloPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(110, 100, 140, 255), Color.argb(0, 100, 140, 255)),
            null, Shader.TileMode.CLAMP,
        )
        ringPaint.strokeWidth = r * 0.043f
        arcPaint.strokeWidth = r * 0.043f
    }

    private fun radius() = min(width, height) * FACE_FRACTION / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        val t = (now - born) / 1000f
        val inPhase = (now - phaseStarted) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val u = r / 140f

        updateLiveliness(now)
        updatePose(dt)

        // «Громкость»: без доступа к микрофону — плавная псевдо-случайная волна.
        val level = (0.5f + 0.3f * sin(t * 7.3f) + 0.2f * sin(t * 12.1f + 1f)).coerceIn(0f, 1f)

        // Тень на «полу».
        canvas.save()
        canvas.translate(cx, cy + r * 0.92f)
        canvas.scale(1f, 0.32f)
        canvas.drawCircle(0f, 0f, r * 1.1f, dropPaint)
        canvas.restore()

        if (phase == Phase.LISTENING) {
            ringPaint.alpha = ((0.15f + level * 0.5f) * 255).toInt()
            canvas.drawCircle(cx, cy, r * (1.06f + level * 0.22f), ringPaint)
            ringPaint.alpha = ((0.08f + level * 0.3f) * 255).toInt()
            canvas.drawCircle(cx, cy, r * (1.14f + level * 0.36f), ringPaint)
        }
        if (phase == Phase.THINKING || phase == Phase.LOOKING) {
            val pad = r * 0.13f
            rect.set(cx - r - pad, cy - r - pad, cx + r + pad, cy + r + pad)
            canvas.drawArc(rect, (inPhase * 330f) % 360f - 90f, 90f, false, arcPaint)
        }

        val wp = winkProgress(now)
        val tilt = if (wp < 0f) 0f else Ease.plateau(wp, 0.38f, 0.60f, ramp = 0.38f)
        // Дыхание в простое и во сне, подпрыгивание от восторга.
        val breath = 1f + eye.breath * sin(t * if (sleeping) 1.1f else 1.7f)
        val scale = (if (phase == Phase.SPEAKING && !sleeping) 1f + level * 0.05f else 1f) * breath
        val hop = -eye.bounce * 10f * u * abs(sin(t * 5.2f))
        canvas.save()
        canvas.translate(0f, hop)
        canvas.scale(scale, scale, cx, cy)
        canvas.rotate(-4f * tilt, cx, cy)
        canvas.drawCircle(cx, cy, r, facePaint)
        canvas.drawCircle(cx, cy, r, shadePaint)

        val blink = blinkFactor(now)
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(cx + (side * EYE_OFFSET + eye.dx + glanceX) * u, cy + (eye.dy + glanceY) * u)
            if (side == 1 && wp >= 0f) {
                drawWinkingEye(canvas, u, wp)
            } else {
                // Второй глаз при подмигивании чуть щурится и приподнимается.
                canvas.translate(0f, -4f * u * tilt)
                canvas.scale(eye.sx * (1f + 0.04f * tilt), eye.sy * blink * (1f - 0.16f * tilt))
                drawEye(canvas, u, 1f, side)
            }
            canvas.restore()
        }

        // Блик сверху слева: мягкий эллипс, повёрнутый на −24°.
        canvas.save()
        canvas.translate(cx - r * 0.22f, cy - r * 0.60f)
        canvas.rotate(-24f)
        canvas.scale(1f, 0.67f)
        canvas.drawCircle(0f, 0f, r * 0.54f, glossPaint)
        canvas.restore()
        canvas.restore()

        if (eye.closed > 0.5f) drawSleepZ(canvas, cx, cy, u, t) else drawSparkles(canvas, cx, cy, u, t)

        if (isShown) postInvalidateOnAnimation()
    }

    /**
     * Белый глаз 40×52 единиц. При разговоре ([EyePose.speak] → 1) он ниже (34) и с плоским низом,
     * как border-radius 50% 50% 45% 45% / 85% 85% 30% 30% в прототипе. [heightK] — сжатие по высоте.
     */
    private fun drawEye(canvas: Canvas, u: Float, heightK: Float, side: Int = 0) {
        val speak = eye.speak
        val hw = 20f * u
        val hh = (26f - 9f * speak) * u * heightK * (1f - 0.86f * eye.closed)
        val shift = -4f * u * speak
        val topRy = hh * (1f + (2f * 0.739f - 1f) * speak)
        val botRy = hh * (1f + (2f * 0.261f - 1f) * speak)
        val botRx = hw * (1f - 0.1f * speak)
        radii[0] = hw; radii[1] = topRy; radii[2] = hw; radii[3] = topRy
        radii[4] = botRx; radii[5] = botRy; radii[6] = botRx; radii[7] = botRy
        // Лёгкая тень на 1 единицу ниже, затем сам глаз.
        rect.set(-hw, -hh + shift + u, hw, hh + shift + u)
        eyePath.reset()
        eyePath.addRoundRect(rect, radii, Path.Direction.CW)
        // Радость: снизу «откусывает» эллипс — глаз-полумесяц.
        if (eye.smile > 0.01f) {
            val cy = hh * (2.2f - 1.3f * eye.smile) + shift
            cutPath.reset()
            cutPath.addOval(-hw * 1.3f, cy - hh, hw * 1.3f, cy + hh, Path.Direction.CW)
            eyePath.op(cutPath, Path.Op.DIFFERENCE)
        }
        // Грусть: верхнее веко опущено к внешнему краю глаза.
        if (eye.lid > 0.01f && side != 0) {
            val c = -hh + eye.lid * hh * 0.8f + shift
            val k = 0.45f * eye.lid * hh / hw
            cutPath.reset()
            cutPath.moveTo(-2f * hw, -3f * hh)
            cutPath.lineTo(2f * hw, -3f * hh)
            cutPath.lineTo(2f * hw, c + side * k * 2f * hw)
            cutPath.lineTo(-2f * hw, c - side * k * 2f * hw)
            cutPath.close()
            eyePath.op(cutPath, Path.Op.DIFFERENCE)
        }
        canvas.drawPath(eyePath, eyeShadowPaint)
        eyePath.offset(0f, -u)
        canvas.drawPath(eyePath, eyePaint)
    }

    /** Подмигивание 560 мс: глаз сжимается в щёлку → «^» из двух штрихов под углом 30° → обратно. */
    private fun drawWinkingEye(canvas: Canvas, u: Float, p: Float) {
        val slit = 10f / 52f
        val squash = when {
            p < 0.28f -> lerp(1f, slit, Ease.smooth(p / 0.28f))
            p < 0.76f -> slit
            else -> lerp(slit, 1f, Ease.smooth((p - 0.76f) / 0.24f))
        }
        val chevron = Ease.plateau(p, 0.40f, 0.62f, ramp = 0.10f)
        if (chevron < 1f) {
            eyePaint.alpha = ((1f - chevron) * 255).toInt()
            eyeShadowPaint.alpha = ((1f - chevron) * EYE_SHADOW_ALPHA).toInt()
            canvas.save()
            canvas.scale(eye.sx, eye.sy)
            drawEye(canvas, u, squash)
            canvas.restore()
        }
        if (chevron > 0f) {
            eyePaint.alpha = (chevron * 255).toInt()
            for (side in intArrayOf(-1, 1)) {
                canvas.save()
                canvas.translate(side * 9f * u, 4f * u)
                canvas.rotate(side * 30f)
                rect.set(-15f * u, -5.5f * u, 15f * u, 5.5f * u)
                canvas.drawRoundRect(rect, 6f * u, 6f * u, eyePaint)
                canvas.restore()
            }
        }
        eyePaint.alpha = 255
        eyeShadowPaint.alpha = EYE_SHADOW_ALPHA
    }

    /** Сон: три «z» поднимаются от лица справа вверх и тают. */
    private fun drawSleepZ(canvas: Canvas, cx: Float, cy: Float, u: Float, t: Float) {
        for (i in 0 until 3) {
            val p = ((t / 3.6f) + i / 3f) % 1f
            zPaint.textSize = (26f + 22f * p) * u
            zPaint.alpha = ((1f - p) * 200).toInt().coerceIn(0, 255)
            canvas.drawText("z", cx + (110f + 50f * p) * u, cy - (90f + 110f * p) * u, zPaint)
        }
    }

    /** Три звёздочки ✦: справа сверху крупная, справа мелкая, слева снизу мелкая; мерцают вразнобой. */
    private fun drawSparkles(canvas: Canvas, cx: Float, cy: Float, u: Float, t: Float) {
        val d = sparkle ?: return
        for (s in SPARKLES) {
            val k = 0.5f - 0.5f * cos(2f * PI.toFloat() * (t + s.delay) / s.period)
            val alpha = lerp(0.95f, 0.55f, k)
            val half = s.size / 2f * u * lerp(1f, 0.82f, k)
            val x = cx + s.x * u
            val y = cy + s.y * u
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(half * 1.6f, half * 1.6f)
            haloPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(0f, 0f, 1f, haloPaint)
            canvas.restore()
            d.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
            d.alpha = (alpha * 255).toInt()
            d.draw(canvas)
        }
    }

    private fun winkAllowed() = !sleeping && phase != Phase.THINKING && phase != Phase.LISTENING && emotion != Emotion.SAD

    /** Подмигивание запланировано, идёт или только что закончилось (короткая пауза после). */
    private fun winkBusy(now: Long) =
        winkStart != 0L && now >= winkStart - WINK_DELAY_MS && now - winkStart < WINK_MS + WINK_REST_MS

    /** 0..1 — идёт подмигивание, −1 — нет. */
    private fun winkProgress(now: Long): Float {
        if (winkStart == 0L || now < winkStart) return -1f
        val p = (now - winkStart) / WINK_MS.toFloat()
        return if (p <= 1f) p else -1f
    }

    private fun updateLiveliness(now: Long) {
        val busyWink = winkBusy(now)
        // Моргание: каждые 2.6–5.8 с, в 20 % случаев двойное.
        if (now >= nextBlinkAt) {
            if (phase != Phase.THINKING && !busyWink && !sleeping) {
                blinkStart = now
                secondBlinkAt = if (Random.nextFloat() < 0.2f) now + 260L else 0L
            }
            nextBlinkAt = now + 2_600L + Random.nextLong(3_200L)
        }
        if (secondBlinkAt in 1..now) {
            if (!busyWink) blinkStart = now
            secondBlinkAt = 0L
        }
        // Взгляд в сторону: каждые 3.5–7 с на 0.9–1.6 с (не когда слушает, думает или ведёт).
        val canGlance = !sleeping && (phase == Phase.IDLE || phase == Phase.SPEAKING || phase == Phase.LOOKING)
        if (now >= nextGlanceAt) {
            if (canGlance && !busyWink) {
                val roll = Random.nextFloat()
                target.gx = if (roll < 0.3f) -GLANCE_X else if (roll < 0.6f) GLANCE_X else 0f
                target.gy = if (target.gx != 0f && Random.nextFloat() < 0.4f) GLANCE_Y else 0f
                glanceUntil = now + 900L + Random.nextLong(700L)
            }
            nextGlanceAt = now + 3_500L + Random.nextLong(3_500L)
        }
        if (!canGlance || busyWink || now >= glanceUntil) {
            target.gx = 0f
            target.gy = 0f
        }
        // Подмигивание: раз в 9 с с вероятностью 35 %.
        if (now >= nextWinkRollAt) {
            nextWinkRollAt = now + WINK_ROLL_MS
            if (Random.nextFloat() < 0.35f) wink()
        }
    }

    private fun updatePose(dt: Float) {
        val pose = FacePoses.target(phase, emotion, sleeping)
        target.sx = pose.sx; target.sy = pose.sy; target.dx = pose.dx; target.dy = pose.dy; target.speak = pose.speak
        target.smile = pose.smile; target.lid = pose.lid; target.bounce = pose.bounce
        target.breath = pose.breath; target.closed = pose.closed
        val k = if (dt == 0f) 1f else 1f - exp(-dt / POSE_TAU)
        // Засыпает и просыпается медленно, эмоции — чуть мягче, чем фаза.
        val ks = if (dt == 0f) 1f else 1f - exp(-dt / SLEEP_TAU)
        val ke = if (dt == 0f) 1f else 1f - exp(-dt / EMOTION_TAU)
        eye.smile += (target.smile - eye.smile) * ke
        eye.lid += (target.lid - eye.lid) * ke
        eye.bounce += (target.bounce - eye.bounce) * ke
        eye.breath += (target.breath - eye.breath) * ks
        eye.closed += (target.closed - eye.closed) * ks
        val kg = if (dt == 0f) 1f else 1f - exp(-dt / GLANCE_TAU)
        eye.sx += (target.sx - eye.sx) * k
        eye.sy += (target.sy - eye.sy) * k
        eye.dx += (target.dx - eye.dx) * k
        eye.dy += (target.dy - eye.dy) * k
        eye.speak += (target.speak - eye.speak) * k
        glanceX += (target.gx - glanceX) * kg
        glanceY += (target.gy - glanceY) * kg
    }

    /** Моргание [BLINK_MS]: глаза на мгновение сжимаются до щёлки (6 из 52). */
    private fun blinkFactor(now: Long): Float {
        val p = (now - blinkStart).toFloat()
        if (blinkStart == 0L || p > BLINK_MS) return 1f
        return 1f - (1f - 6f / 52f) * sin(PI.toFloat() * p / BLINK_MS)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            lastFrame = 0L
            invalidate()
        }
    }

    private class EyePose(
        var sx: Float = 1f, var sy: Float = 1f, var dx: Float = 0f, var dy: Float = 0f,
        var speak: Float = 0f, var gx: Float = 0f, var gy: Float = 0f,
        var smile: Float = 0f, var lid: Float = 0f, var bounce: Float = 0f, var breath: Float = 0f, var closed: Float = 0f,
    )

    /** Звёздочка: центр относительно центра лица и размер — в единицах прототипа; период и сдвиг фазы — в секундах. */
    private class Sparkle(val x: Float, val y: Float, val size: Float, val period: Float, val delay: Float)

    /** Плавность cubic-bezier(.45,0,.55,1), приближённо — smoothstep. */
    private object Ease {
        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        /** Плавно 0 → 1 на отрезке [from − ramp, from], держится до [to], обратно к 0 за [ramp]. */
        fun plateau(p: Float, from: Float, to: Float, ramp: Float): Float = when {
            p < from -> smooth((p - (from - ramp)) / ramp)
            p <= to -> 1f
            else -> 1f - smooth((p - to) / ramp)
        }
    }

    private companion object {
        const val FACE_FRACTION = 0.7f
        const val EYE_OFFSET = 52f // от центра лица до центра глаза: (64 + 40) / 2
        const val EYE_SHADOW_ALPHA = 64
        const val GLANCE_X = 16f
        const val GLANCE_Y = 4f
        const val BLINK_MS = 180f
        const val WINK_MS = 560L
        const val WINK_DELAY_MS = 360L
        const val WINK_REST_MS = 300L
        const val WINK_ROLL_MS = 9_000L
        const val POSE_TAU = 0.08f
        const val GLANCE_TAU = 0.12f
        const val EMOTION_TAU = 0.16f
        const val SLEEP_TAU = 0.5f

        // Прототип (лицо 280): s1 44 (right −30, top −14), s2 18 (right −44, top 40), s3 20 (left −34, bottom 22).
        val SPARKLES = listOf(
            Sparkle(x = 148f, y = -132f, size = 44f, period = 4.2f, delay = 0f),
            Sparkle(x = 175f, y = -91f, size = 18f, period = 3.6f, delay = 1.4f),
            Sparkle(x = -164f, y = 108f, size = 20f, period = 4.8f, delay = 2.6f),
        )

        fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
    }
}
