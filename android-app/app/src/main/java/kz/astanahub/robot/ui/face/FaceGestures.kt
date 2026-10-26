package kz.astanahub.robot.ui.face

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Разбор жестов по лицу (чистый Kotlin, тест без Android): тап, удержание, поглаживание и свайп к меню
 * распознаются **параллельно с первого касания** (apple-design §10): пока палец в пределах гистерезиса [slop] —
 * живы тап и удержание; вышел за него — по направлению выбирается свайп (влево, прямо) или поглаживание
 * (вверх-вниз, вправо, по кругу, туда-сюда). Свайп, который начал петлять, отменяется в пользу поглаживания.
 *
 * Время — `uptimeMillis` (как у событий касания); удержание срабатывает в [tick] (Compose-слой вызывает его,
 * пока палец лежит). Отклик на касание — сразу в [down] (лицо смотрит на палец).
 */
class FaceGestureRecognizer(
    /** Гистерезис: дальше — уже не тап и не удержание, px. */
    private val slop: Float,
    /** Поглаживание включается, когда палец прошёл столько, px. */
    private val strokeMinPath: Float,
    /** Удержание (вход в админку); false — недоступно (робот без PIN). */
    private val holdEnabled: Boolean,
    private val listener: Listener,
    private val holdMillis: Long = HOLD_MS,
) {
    interface Listener {
        /** Касание: отклик сразу. */
        fun onDown(x: Float, y: Float) {}

        /** Палец сдвинулся (для взгляда за пальцем). */
        fun onMove(x: Float, y: Float) {}

        fun onTap(x: Float, y: Float) {}

        /** Удержание началось (кольцо появится через [HOLD_SHOW_MS]) / оборвалось. */
        fun onHoldStart(downTime: Long) {}
        fun onHoldCancel() {}

        /** Удержание выдержано [HOLD_MS] — вход в админку. */
        fun onHold() {}

        /** Свайп к меню: смещение пальца по x от точки касания. */
        fun onSwipe(dx: Float) {}
        fun onSwipeEnd(dx: Float, velocity: Float) {}

        /** Свайп отменён (жест стал поглаживанием) — лицо возвращается на место. */
        fun onSwipeCancel() {}

        /** Поглаживание: скорость пальца, px/с. */
        fun onStroke(vx: Float, vy: Float) {}
        fun onStrokeEnd() {}

        /** Палец поднят (любой исход). */
        fun onUp() {}
    }

    enum class Mode { NONE, PRESSED, HOLD, SWIPE, STROKE }

    var mode: Mode = Mode.NONE
        private set

    /** Поглаживание уже включилось (путь ≥ [strokeMinPath]). */
    var stroking: Boolean = false
        private set

    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var path = 0f
    private var holdStarted = false

    // Последние точки — для скорости (кольцо, без аллокаций).
    private val st = LongArray(SAMPLES)
    private val sx = FloatArray(SAMPLES)
    private val sy = FloatArray(SAMPLES)
    private var count = 0

    fun down(x: Float, y: Float, t: Long) {
        mode = Mode.PRESSED
        stroking = false
        x0 = x
        y0 = y
        t0 = t
        lastX = x
        lastY = y
        path = 0f
        count = 0
        sample(x, y, t)
        listener.onDown(x, y)
        holdStarted = holdEnabled
        if (holdStarted) listener.onHoldStart(t)
    }

    fun move(x: Float, y: Float, t: Long) {
        if (mode == Mode.NONE || mode == Mode.HOLD) return
        val stepX = x - lastX
        val stepY = y - lastY
        path += sqrt(stepX * stepX + stepY * stepY)
        lastX = x
        lastY = y
        sample(x, y, t)
        listener.onMove(x, y)
        val dx = x - x0
        val dy = y - y0
        when (mode) {
            Mode.PRESSED -> {
                if (dx * dx + dy * dy <= slop * slop) return
                cancelHold()
                // Вышли за гистерезис: влево и по горизонтали — свайп, иначе — поглаживание.
                if (dx < 0f && abs(dx) >= abs(dy)) {
                    mode = Mode.SWIPE
                    listener.onSwipe(dx)
                } else {
                    mode = Mode.STROKE
                    strokeStep()
                }
            }
            Mode.SWIPE -> {
                if (swipeLost(dx, dy)) {
                    listener.onSwipeCancel()
                    mode = Mode.STROKE
                    strokeStep()
                } else {
                    listener.onSwipe(dx)
                }
            }
            Mode.STROKE -> strokeStep()
            else -> Unit
        }
    }

    /** Время идёт, палец лежит: удержание. */
    fun tick(t: Long) {
        if (mode == Mode.PRESSED && holdStarted && t - t0 >= holdMillis) {
            mode = Mode.HOLD
            holdStarted = false
            listener.onHold()
        }
    }

    fun up(x: Float, y: Float, t: Long) {
        if (mode == Mode.NONE) return
        move(x, y, t)
        when (mode) {
            Mode.PRESSED -> {
                cancelHold()
                if (t - t0 <= TAP_MAX_MS) listener.onTap(x0, y0)
            }
            Mode.SWIPE -> listener.onSwipeEnd(x - x0, velocityX())
            Mode.STROKE -> if (stroking) listener.onStrokeEnd()
            else -> Unit
        }
        finish()
    }

    /** Жест отобрали (система, второй палец): без тапа и без перехода в меню. */
    fun cancel() {
        when (mode) {
            Mode.PRESSED -> cancelHold()
            Mode.SWIPE -> listener.onSwipeCancel()
            Mode.STROKE -> if (stroking) listener.onStrokeEnd()
            else -> Unit
        }
        finish()
    }

    private fun finish() {
        mode = Mode.NONE
        stroking = false
        holdStarted = false
        listener.onUp()
    }

    private fun cancelHold() {
        if (!holdStarted) return
        holdStarted = false
        listener.onHoldCancel()
    }

    /**
     * Свайп «потерян»: палец ушёл больше по вертикали, чем по горизонтали, или путь петляет
     * (прямота = смещение / путь < [MIN_STRAIGHTNESS]: круг, туда-сюда) — это поглаживание.
     */
    private fun swipeLost(dx: Float, dy: Float): Boolean {
        if (path < slop * 3f) return false
        if (abs(dy) > abs(dx)) return true
        val straight = sqrt(dx * dx + dy * dy) / path
        return straight < MIN_STRAIGHTNESS
    }

    private fun strokeStep() {
        if (!stroking && path >= strokeMinPath) stroking = true
        if (stroking) listener.onStroke(velocityX(), velocityY())
    }

    private fun sample(x: Float, y: Float, t: Long) {
        val i = count % SAMPLES
        st[i] = t
        sx[i] = x
        sy[i] = y
        count++
    }

    /** Скорость по последним ~100 мс (px/с). */
    fun velocityX(): Float = velocity(sx)
    fun velocityY(): Float = velocity(sy)

    private fun velocity(v: FloatArray): Float {
        if (count < 2) return 0f
        val last = (count - 1) % SAMPLES
        val tLast = st[last]
        var k = count - 2
        val oldest = (count - SAMPLES).coerceAtLeast(0)
        var first = last
        while (k >= oldest) {
            val i = k % SAMPLES
            if (tLast - st[i] > VELOCITY_WINDOW_MS) break
            first = i
            k--
        }
        val dt = (tLast - st[first]).toFloat()
        if (dt <= 0f) return 0f
        return (v[last] - v[first]) / dt * 1000f
    }

    companion object {
        /** Удержание для входа в админку — как прежнее удержание логотипа (3 с): посетитель случайно не войдёт. */
        const val HOLD_MS = 3_000L

        /** Кольцо удержания появляется не сразу — тап его не мигает. */
        const val HOLD_SHOW_MS = 400L

        /** Дольше — уже не тап (но ещё не удержание: ничего). */
        const val TAP_MAX_MS = 500L

        const val MIN_STRAIGHTNESS = 0.6f
        private const val SAMPLES = 8
        private const val VELOCITY_WINDOW_MS = 100L
    }
}
