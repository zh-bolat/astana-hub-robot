package kz.astanahub.robot.ui.screen

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kz.astanahub.robot.ui.face.FaceLayout
import kz.astanahub.robot.ui.face.project
import kz.astanahub.robot.ui.face.rubberBand

/**
 * Переход экран 1 (лицо) ↔ экран 2 (функции): прогресс 0 — лицо, 1 — экран 2. Чистые функции — тест.
 *
 * Палец ведёт переход 1:1 (прошёл ширину экрана — переход целиком), за краями — rubber-band 0.55 (apple-design §9).
 * Отпускание: решение по **знаку скорости** (быстрее [MIN_VELOCITY]); медленный жест — по проекции импульса Apple
 * (куда «доедет» бросок) за половину пути. Дальше — пружина от текущего прогресса с передачей скорости пальца.
 */
object ScreenSwipe {

    /** Быстрее (px/с) — решает знак скорости, не позиция. */
    const val MIN_VELOCITY = 300f

    /** Прогресс под пальцем: [start] — прогресс в начале жеста, [dx] — сдвиг пальца по x (влево — отрицательный). */
    fun progress(start: Float, dx: Float, width: Float): Float {
        if (width <= 0f) return start
        val raw = start - dx / width
        return when {
            raw > 1f -> 1f + rubberBand((raw - 1f) * width, width) / width
            raw < 0f -> -rubberBand(-raw * width, width) / width
            else -> raw
        }
    }

    /** Итог отпускания: открыть экран 2 или вернуться к лицу; [velocity] — скорость для пружины, прогресс/с. */
    data class Release(val open: Boolean, val velocity: Float, val fling: Boolean)

    /**
     * [velocityX] — скорость пальца по x, px/с (влево — отрицательная, это «к экрану 2»).
     * [allowOpen] = false (поездка: лицо обязано остаться) — только возврат.
     */
    fun release(progress: Float, velocityX: Float, width: Float, allowOpen: Boolean = true): Release {
        val v = if (width > 0f) -velocityX / width else 0f
        val fling = abs(velocityX) >= MIN_VELOCITY
        val open = allowOpen && if (fling) {
            velocityX < 0f
        } else {
            progress - project(velocityX) / width.coerceAtLeast(1f) >= 0.5f
        }
        return Release(open, v, fling)
    }
}

/**
 * Лицо при переходе (чистые функции — тест): полноэкранное (0) непрерывно сжимается в мини-лицо в шапке экрана 2 (1)
 * — по прогрессу жеста, не по таймеру. Масштаб — по логарифму (на глаз равномерно: каждый шаг пальца уменьшает лицо
 * в одно и то же число раз), центр видимого лица едет по прямой к центру слота в шапке.
 */
object FaceMorph {

    /** Видимое лицо на холсте [FaceLayout] (глаза со взглядом, брови, рот): ширина, высота, центр по y. */
    const val CONTENT_WIDTH = 840f
    const val CONTENT_HEIGHT = 480f
    const val CONTENT_CENTER_Y = 22f

    data class Transform(val scale: Float, val translationX: Float, val translationY: Float)

    /** Масштаб, при котором видимое лицо — высотой [slotHeight] (px) на экране [width] × [height]. */
    fun miniScale(width: Float, height: Float, slotHeight: Float): Float {
        val u = min(width / FaceLayout.WIDTH, height / FaceLayout.HEIGHT)
        if (u <= 0f) return 1f
        return (slotHeight / (CONTENT_HEIGHT * u)).coerceIn(0.02f, 1f)
    }

    /**
     * Преобразование слоя лица (во весь экран, transformOrigin — центр) при прогрессе [p]. За краями (rubber-band) лицо
     * едет вместе с экраном под пальцем: p < 0 — полноразмерное вправо, p > 1 — мини-лицо в слоте шапки влево.
     */
    fun transform(p: Float, width: Float, height: Float, slotCx: Float, slotCy: Float, slotHeight: Float): Transform {
        val u = min(width / FaceLayout.WIDTH, height / FaceLayout.HEIGHT)
        val faceCy0 = height * 0.5f + CONTENT_CENTER_Y * u
        if (p < 0f) return Transform(1f, -p * width, 0f)
        if (p > 1f) {
            val docked = transform(1f, width, height, slotCx, slotCy, slotHeight)
            return docked.copy(translationX = docked.translationX - (p - 1f) * width)
        }
        val t = p
        val scale = miniScale(width, height, slotHeight).pow(t)
        val cx = width * 0.5f + (slotCx - width * 0.5f) * t
        val cy = faceCy0 + (slotCy - faceCy0) * t
        return Transform(
            scale = scale,
            translationX = cx - width * 0.5f,
            translationY = cy - height * 0.5f - (faceCy0 - height * 0.5f) * scale,
        )
    }
}
