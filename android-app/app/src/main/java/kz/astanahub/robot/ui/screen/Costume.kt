package kz.astanahub.robot.ui.screen

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.R

/**
 * Слой робота-компаньона: картинка, снятая из прототипа (прозрачный фон, ×2, обрезана по содержимому), и её место в
 * рамке слоёв [BOX_W] × [BOX_H] dp (рамка = `#ind` прототипа − (100, 120); dp = px сцены 1600 × 900).
 */
@Immutable
data class Layer(@DrawableRes val res: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Костюм робота-компаньона (прототип mynbay: обычный, казахский, айтишник, космонавт — в этом порядке). Тело
 * (с одеждой и домброй, без рук), руки и их тени, корпус головы, чёрный экран, шапка / наушники и корона-система —
 * отдельные слои из прототипа ([CompanionLayers]); движения — у [Companion]. Глаза — живое лицо в экране головы.
 * Меняется свайпом по роботу или точками под ним (как в прототипе) и в диагностике.
 */
enum class Costume(val code: String) {
    CLASSIC("default"),
    KAZAKH("kazakh"),
    BUSINESS("business"),
    GALAXY("galaxy"),
    ;

    /** Название по-русски (диагностика); на экране — из ресурсов на языке экрана ([ScreenTexts.costumes]). */
    val label: String get() = LABELS[ordinal]

    fun next(step: Int): Costume = entries[((ordinal + step) % entries.size + entries.size) % entries.size]

    companion object {
        private val LABELS = listOf("Обычный", "Казахский", "Айтишник", "Космонавт")

        fun fromCode(code: String?): Costume = entries.firstOrNull { it.code == code } ?: CLASSIC
    }
}

/** Слои костюмов: места — из снятия (`build_assets.py`, dp в рамке 540 × 640). */
object CompanionLayers {
    val glow = Layer(R.drawable.mf_c_glow, 10f, 90f, 520f, 520f)
    val shell = Layer(R.drawable.mf_c_shell, 80f, 103f, 380f, 302f)
    val screen = Layer(R.drawable.mf_c_screen, 133f, 139f, 274f, 188f)
    val orbit = Layer(R.drawable.mf_c_orbit_galaxy, 155f, 71f, 230f, 92f)

    fun body(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_body_default, 190f, 333f, 160f, 151f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_body_kazakh, 160f, 333f, 190f, 162f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_body_business, 187f, 333f, 166f, 154f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_body_galaxy, 189f, 333f, 162f, 154f)
    }

    fun bodyShadow(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_sh_body_default, 182f, 331f, 176f, 167f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_sh_body_kazakh, 153f, 331f, 205f, 177f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_sh_body_business, 180f, 331f, 179f, 170f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_sh_body_galaxy, 182f, 331f, 176f, 170f)
    }

    fun armL(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_arml_default, 168f, 370f, 38f, 83f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_arml_kazakh, 166f, 368f, 42f, 85f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_arml_business, 167f, 369f, 40f, 84f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_arml_galaxy, 166f, 368f, 42f, 85f)
    }

    fun armLShadow(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_sh_arml_default, 161f, 369f, 52f, 97f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_sh_arml_kazakh, 159f, 367f, 56f, 99f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_sh_arml_business, 160f, 368f, 54f, 98f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_sh_arml_galaxy, 159f, 367f, 56f, 99f)
    }

    fun armR(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_armr_default, 334f, 370f, 38f, 83f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_armr_kazakh, 332f, 368f, 42f, 85f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_armr_business, 333f, 369f, 40f, 84f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_armr_galaxy, 332f, 368f, 42f, 85f)
    }

    fun armRShadow(c: Costume): Layer = when (c) {
        Costume.CLASSIC -> Layer(R.drawable.mf_c_sh_armr_default, 327f, 369f, 52f, 97f)
        Costume.KAZAKH -> Layer(R.drawable.mf_c_sh_armr_kazakh, 325f, 367f, 56f, 99f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_sh_armr_business, 326f, 368f, 54f, 98f)
        Costume.GALAXY -> Layer(R.drawable.mf_c_sh_armr_galaxy, 325f, 367f, 56f, 99f)
    }

    /** Наряд головы: такия (казахский), наушники (айтишник); у космонавта — корона-система [orbit] отдельно. */
    fun headX(c: Costume): Layer? = when (c) {
        Costume.KAZAKH -> Layer(R.drawable.mf_c_hat_kazakh, 172f, 65f, 196f, 109f)
        Costume.BUSINESS -> Layer(R.drawable.mf_c_headset_business, 87f, 85f, 366f, 196f)
        else -> null
    }
}

class CostumeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("robot_costume", Context.MODE_PRIVATE)
    private val _costume = MutableStateFlow(Costume.fromCode(prefs.getString(KEY, null)))
    val costume: StateFlow<Costume> = _costume

    fun set(c: Costume) {
        prefs.edit().putString(KEY, c.code).apply()
        _costume.value = c
    }

    private companion object {
        const val KEY = "costume"
    }
}
