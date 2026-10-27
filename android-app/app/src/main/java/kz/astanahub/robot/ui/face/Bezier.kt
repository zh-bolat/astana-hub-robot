package kz.astanahub.robot.ui.face

import kotlin.math.abs

/**
 * Кривая CSS `cubic-bezier(x1, y1, x2, y2)` (чистый Kotlin, без Compose — движок лица считается и в проверке
 * аллокаций на голой JVM). [transform] — значение кривой в доле времени 0..1; без аллокаций.
 */
class Bezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {

    fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        // Ищем t, при котором x(t) = x: Ньютон, при плохой производной — деление пополам.
        var t = x
        repeat(8) {
            val dx = curve(t, x1, x2) - x
            if (abs(dx) < 1e-5f) return curve(t, y1, y2)
            val d = slope(t, x1, x2)
            if (abs(d) < 1e-6f) return@repeat
            t -= dx / d
        }
        var lo = 0f
        var hi = 1f
        t = x
        repeat(24) {
            val v = curve(t, x1, x2)
            if (abs(v - x) < 1e-5f) return curve(t, y1, y2)
            if (v < x) lo = t else hi = t
            t = (lo + hi) * 0.5f
        }
        return curve(t, y1, y2)
    }

    private fun curve(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    private fun slope(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b)
    }

    companion object {
        /** CSS `ease-in-out`. */
        val EASE_IN_OUT = Bezier(0.42f, 0f, 0.58f, 1f)
    }
}
