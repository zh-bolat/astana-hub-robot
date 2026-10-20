package kz.astanahub.robot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Лицо робота: объёмный синий шар с глазами. Состояние = [Phase]:
 * IDLE — моргает; LISTENING — расходятся кольца; THINKING — глаза вверх, крутится дуга;
 * LOOKING — глаза шире; SPEAKING — шар «дышит»; NAVIGATING — глаза смотрят вправо (по ходу движения).
 * Свет сверху слева: блик вверху, затемнение внизу, мягкая синяя тень под шаром.
 * Шар занимает [FACE_FRACTION] стороны вида — остальное место для колец и тени.
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

    private var phaseStarted = SystemClock.uptimeMillis()
    private val born = SystemClock.uptimeMillis()

    private val blue = Color.parseColor("#1F4FFF")
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 8, 22, 90) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = blue }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = blue
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = radius()
        // Основной цвет шара: светлее у блика, темнее к краю.
        facePaint.shader = RadialGradient(
            cx - r * 0.32f, cy - r * 0.44f, r * 1.75f,
            intArrayOf(Color.parseColor("#6E8FFF"), Color.parseColor("#2F5CFF"), blue, Color.parseColor("#1236C2")),
            floatArrayOf(0f, 0.30f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        // Затемнение снизу (объём): прозрачно сверху, тёмно-синее у нижнего края.
        shadePaint.shader = RadialGradient(
            cx, cy - r * 0.35f, r * 1.45f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(95, 8, 22, 90)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        glossPaint.shader = RadialGradient(
            0f, 0f, r * 0.34f,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        // Тень под шаром (на «пол»): синяя, размытая.
        dropPaint.shader = RadialGradient(
            0f, 0f, r * 1.1f,
            intArrayOf(Color.argb(80, 31, 79, 255), Color.argb(30, 31, 79, 255), Color.argb(0, 31, 79, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        // Глаз: белый, к низу чуть голубее (координаты — относительно центра глаза).
        val ew = r * 0.143f
        val eh = r * 0.186f
        eyePaint.shader = RadialGradient(
            0f, -eh * 0.35f, max(ew, eh) * 1.25f,
            intArrayOf(Color.WHITE, Color.WHITE, Color.parseColor("#DCE4FF")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        ringPaint.strokeWidth = r * 0.043f
        arcPaint.strokeWidth = r * 0.043f
    }

    private fun radius() = min(width, height) * FACE_FRACTION / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        val inPhase = (now - phaseStarted) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()

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

        val scale = if (phase == Phase.SPEAKING) 1f + level * 0.05f else 1f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, r, facePaint)
        canvas.drawCircle(cx, cy, r, shadePaint)

        // Глаза.
        val ew = r * 0.143f // полуширина (40/280 диаметра лица)
        val eh = r * 0.186f // полувысота (52/280)
        var dx = 0f
        var dy = 0f
        var eyeScale = 1f
        when (phase) {
            Phase.THINKING -> dy = -r * 0.186f
            Phase.NAVIGATING -> dx = r * 0.214f
            Phase.LOOKING -> eyeScale = 1.15f
            else -> Unit
        }
        val blink = if (phase != Phase.THINKING && phase != Phase.LISTENING) blinkFactor(now) else 1f
        val gap = r * 0.371f // от центра лица до центра глаза: (64/2 + 40/2) / 140
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * gap + dx
            val ey = cy + dy
            canvas.save()
            canvas.translate(ex, ey)
            canvas.scale(eyeScale, eyeScale * blink)
            rect.set(-ew, -eh + r * 0.02f, ew, eh + r * 0.02f)
            canvas.drawOval(rect, eyeShadowPaint)
            rect.set(-ew, -eh, ew, eh)
            canvas.drawOval(rect, eyePaint)
            canvas.restore()
        }

        // Блик сверху слева.
        canvas.save()
        canvas.translate(cx - r * 0.38f, cy - r * 0.58f)
        canvas.rotate(-24f)
        canvas.scale(1f, 0.6f)
        canvas.drawCircle(0f, 0f, r * 0.34f, glossPaint)
        canvas.restore()
        canvas.restore()

        if (isShown) postInvalidateOnAnimation()
    }

    /** Моргание раз в [BLINK_PERIOD_MS]: глаза на долю секунды сжимаются по высоте. */
    private fun blinkFactor(now: Long): Float {
        val p = ((now - born) % BLINK_PERIOD_MS).toFloat()
        if (p > BLINK_MS) return 1f
        return 1f - 0.9f * sin(PI.toFloat() * p / BLINK_MS)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private companion object {
        const val FACE_FRACTION = 0.7f
        const val BLINK_PERIOD_MS = 4_200L
        const val BLINK_MS = 180f
    }
}
