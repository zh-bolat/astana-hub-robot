package kz.astanahub.robot.core

import java.util.Locale

/** Языки интерфейса и ответов. [code] совпадает с `lang` в docs/api-contract.md. */
enum class Lang(val code: String, val locale: Locale) {
    RU("ru", Locale.forLanguageTag("ru")),
    KK("kk", Locale.forLanguageTag("kk")),
    EN("en", Locale.ENGLISH),
    ;

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
