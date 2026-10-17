package kz.astanahub.robot.core

import java.security.MessageDigest

/**
 * Логи без персональных данных: тексты речи, вопросов и ответов в logcat не пишем —
 * только длину и короткий хеш (чтобы сопоставить повторы, не раскрывая содержимое).
 */
object LogSafe {
    /** «len=17 h=3fa2c1d0»; null/пусто → «len=0». */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return "len=0"
        return "len=${text.length} h=${shortHash(text)}"
    }

    fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
}
