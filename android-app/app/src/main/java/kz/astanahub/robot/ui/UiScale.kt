package kz.astanahub.robot.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Масштаб интерфейса, не зависящий от density прошивки робота.
 * Вёрстка рассчитана на «киоск» шириной [DESIGN_WIDTH_DP] dp; density подбираем от реальной ширины экрана в px:
 * на 1920×1080 это density 1.2 при масштабе 100% — при любом densityDpi, который выставил производитель.
 * Пользовательский масштаб 60–150% (экран диагностики) умножает density. fontScale системы игнорируем (= 1).
 */
object UiScale {
    const val DESIGN_WIDTH_DP = 1600
    const val MIN_PERCENT = 60
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 10
    const val DEFAULT_PERCENT = 100

    private const val PREFS = "ui"
    private const val KEY_SCALE = "scale_percent"

    fun percent(context: Context): Int =
        prefs(context).getInt(KEY_SCALE, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun setPercent(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SCALE, value.coerceIn(MIN_PERCENT, MAX_PERCENT)).apply()
    }

    /** Для Activity.attachBaseContext: контекст с пересчитанными density / screenWidthDp. */
    fun wrap(base: Context): Context {
        val config = overrideConfiguration(base, percent(base))
        return base.createConfigurationContext(config)
    }

    fun overrideConfiguration(base: Context, percent: Int): Configuration {
        val real = Resources.getSystem().displayMetrics
        val densityDpi = targetDensityDpi(real.widthPixels, real.heightPixels, percent)
        val density = densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
        return Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
            fontScale = 1f
            screenWidthDp = (real.widthPixels / density).roundToInt()
            screenHeightDp = (real.heightPixels / density).roundToInt()
            smallestScreenWidthDp = min(screenWidthDp, screenHeightDp)
        }
    }

    /** Длинная сторона экрана = DESIGN_WIDTH_DP / (percent/100) dp. Чистая функция — покрыта тестом. */
    fun targetDensityDpi(widthPx: Int, heightPx: Int, percent: Int): Int {
        val longSide = max(widthPx, heightPx).coerceAtLeast(1)
        val dpi = longSide.toFloat() / DESIGN_WIDTH_DP * DisplayMetrics.DENSITY_DEFAULT * percent / 100f
        return dpi.roundToInt().coerceAtLeast(MIN_DENSITY_DPI)
    }

    private const val MIN_DENSITY_DPI = 60

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
