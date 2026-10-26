package kz.astanahub.robot.ui.screen

import android.content.Context
import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.R

/**
 * Костюм робота-компаньона на странице приложения — только вид (прототип: классика, космос, казахский, деловой).
 * Картинки отрисованы из прототипа (`docs/design/robot-mvp.html`) без глаз: глаза — живое лицо, оно садится в экран
 * головы ([CompanionLayout]). Выбирает сотрудник в диагностике.
 */
enum class Costume(val code: String, val label: String, @DrawableRes val image: Int) {
    CLASSIC("default", "Обычный", R.drawable.mf_robot_default),
    GALAXY("galaxy", "Космос", R.drawable.mf_robot_galaxy),
    KAZAKH("kazakh", "Казахский", R.drawable.mf_robot_kazakh),
    BUSINESS("business", "Деловой", R.drawable.mf_robot_business),
    ;

    companion object {
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
