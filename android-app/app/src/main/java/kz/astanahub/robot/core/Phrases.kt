package kz.astanahub.robot.core

/** Фразы, которые робот говорит сам, без сервера (работают офлайн). */
enum class PhraseKey {
    NO_CONNECTION,
    NOT_HEARD,
    ONE_MOMENT,
    CAMERA_UNAVAILABLE,
}

fun interface Phrases {
    fun get(key: PhraseKey, lang: Lang): String
}
