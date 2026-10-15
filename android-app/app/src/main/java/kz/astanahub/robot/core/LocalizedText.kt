package kz.astanahub.robot.core

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import kz.astanahub.robot.R

/**
 * Строки на конкретном языке независимо от системной локали робота:
 * язык выбирает посетитель кнопками RU/KZ/EN.
 */
class LocalizedText(context: Context) : Phrases {
    private val app = context.applicationContext
    private val contexts = mutableMapOf<Lang, Context>()

    fun get(@StringRes id: Int, lang: Lang): String = contextFor(lang).getString(id)

    fun get(@StringRes id: Int, lang: Lang, vararg args: Any): String = contextFor(lang).getString(id, *args)

    override fun get(key: PhraseKey, lang: Lang): String = get(
        when (key) {
            PhraseKey.NO_CONNECTION -> R.string.phrase_no_connection
            PhraseKey.NOT_HEARD -> R.string.phrase_not_heard
            PhraseKey.ONE_MOMENT -> R.string.phrase_one_moment
            PhraseKey.CAMERA_UNAVAILABLE -> R.string.phrase_camera_unavailable
        },
        lang,
    )

    @Synchronized
    private fun contextFor(lang: Lang): Context = contexts.getOrPut(lang) {
        val config = Configuration(app.resources.configuration).apply { setLocale(lang.locale) }
        app.createConfigurationContext(config)
    }
}
