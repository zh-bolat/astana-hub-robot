package kz.astanahub.robot.core

/**
 * Фразы, которые робот говорит сам, без сервера (работают офлайн).
 * Все они заранее синтезируются голосом ElevenLabs и лежат в кеше приложения (voice/PhraseAudioCache).
 */
enum class PhraseKey {
    NO_CONNECTION,
    NOT_HEARD,
    ONE_MOMENT,
    CAMERA_UNAVAILABLE,
    /** Приветствие подошедшему, если агент не ответил за 2 с или нет связи. */
    GREETING,
    /** Серверный синтез не отвечает: текст остаётся на экране, голосом — только эта фраза (из кеша). */
    CANT_SPEAK,
    /** «Сейчас посмотрю» — перед снимком камеры. */
    LOOKING,
    /** Короткие «хм…/так…/сейчас…», пока агент готовит первую фразу (только из кеша). */
    FILLER_HM,
    FILLER_SO,
    FILLER_NOW,
    ;

    companion object {
        val FILLERS = listOf(FILLER_HM, FILLER_SO, FILLER_NOW)
    }
}

fun interface Phrases {
    fun get(key: PhraseKey, lang: Lang): String
}
