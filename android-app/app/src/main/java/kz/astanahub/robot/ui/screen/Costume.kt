package kz.astanahub.robot.ui.screen

import android.content.Context
import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.R

/**
 * Костюм робота-компаньона (прототип mynbay: обычный, казахский, айтишник, космонавт — в этом порядке). Слои
 * отрисованы из прототипа без глаз и без анимации (голова, тело, левая и правая рука): поворот рук, покачивание тела
 * и голову двигает приложение ([Companion]). Глаза — живое лицо, оно садится в экран головы.
 * Меняется свайпом по роботу или точками под ним (как в прототипе) и в диагностике.
 */
enum class Costume(
    val code: String,
    @DrawableRes val head: Int,
    @DrawableRes val body: Int,
    @DrawableRes val armL: Int,
    @DrawableRes val armR: Int,
) {
    CLASSIC("default", R.drawable.mf_bot_head_default, R.drawable.mf_bot_body_default, R.drawable.mf_bot_arml_default, R.drawable.mf_bot_armr_default),
    KAZAKH("kazakh", R.drawable.mf_bot_head_kazakh, R.drawable.mf_bot_body_kazakh, R.drawable.mf_bot_arml_kazakh, R.drawable.mf_bot_armr_kazakh),
    BUSINESS("business", R.drawable.mf_bot_head_business, R.drawable.mf_bot_body_business, R.drawable.mf_bot_arml_business, R.drawable.mf_bot_armr_business),
    GALAXY("galaxy", R.drawable.mf_bot_head_galaxy, R.drawable.mf_bot_body_galaxy, R.drawable.mf_bot_arml_galaxy, R.drawable.mf_bot_armr_galaxy),
    ;

    /** Название по-русски (диагностика); на экране — из ресурсов на языке экрана ([ScreenTexts.costumes]). */
    val label: String get() = LABELS[ordinal]

    fun next(step: Int): Costume = entries[((ordinal + step) % entries.size + entries.size) % entries.size]

    companion object {
        private val LABELS = listOf("Обычный", "Казахский", "Айтишник", "Космонавт")

        fun fromCode(code: String?): Costume = entries.firstOrNull { it.code == code } ?: CLASSIC
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
