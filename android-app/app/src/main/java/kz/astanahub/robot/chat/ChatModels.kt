package kz.astanahub.robot.chat

import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.core.Lang

/**
 * Запрос `POST /v1/chat` (docs/api-contract.md) — как у `/ask`: `question`, `lang`, `session_id`, `place_id`;
 * плюс `event` (v1.1). Пустой [question] допустим только с [event].
 */
data class ChatRequest(
    val sessionId: String,
    val question: String,
    val lang: Lang?,
    val placeId: String? = null,
    val event: String? = null,
) {
    companion object {
        /** `event: visitor_arrived` — к роботу подошёл человек, агент здоровается. */
        const val EVENT_VISITOR_ARRIVED = "visitor_arrived"
    }
}

/** Действие робота из ответа агента: `{type, place_id}`. */
data class ChatAction(val type: String, val placeId: String? = null) {
    companion object {
        /** Показать, где место (направление — WayFinder робота); робот не едет. */
        const val WHERE_IS = "where_is"

        /** Проводить: ехать после того, как доиграла последняя фраза ответа. */
        const val GUIDE_TO = "guide_to"

        /** Кадр → `POST /v1/look` (вопрос — реплика посетителя), описание озвучить. */
        const val LOOK = "look"
    }
}

/** События SSE `/v1/chat`. `done` или `error` — всегда последнее. */
sealed interface ChatEvent {
    /** v1.1: `neutral`/`happy`/`thinking`/`surprised`/`sad`/`excited` (неизвестное имя — как есть, лицо решит само). */
    data class Emotion(val name: String) : ChatEvent

    data class Phrase(val text: String) : ChatEvent

    /** Приходит до фраз, которые его описывают. */
    data class Action(val action: ChatAction) : ChatEvent {
        val type: String get() = action.type
        val placeId: String? get() = action.placeId
    }

    data class Done(
        val requestId: String?,
        val lang: Lang?,
        val found: Boolean = true,
        val sources: List<String> = emptyList(),
        /** Все действия ответа (те же, что пришли событиями `action`). */
        val actions: List<ChatAction> = emptyList(),
        /** v1.2: реплика — шум, модель не звали. Робот молчит, лицо без реакции, запасного пути нет. */
        val ignored: Boolean = false,
    ) : ChatEvent

    /**
     * `event: error` с сервера, HTTP-ошибка до начала потока или обрыв потока без `done`
     * ([BackendError.kind] = NETWORK / TIMEOUT).
     */
    data class Error(val error: BackendError) : ChatEvent
}

/** Режим разговора: agent — `/v1/chat`; legacy — прежний путь (локальные правила + `/v1/ask`). */
enum class ChatMode(val code: String) {
    AGENT("agent"),
    LEGACY("legacy"),
    ;

    companion object {
        fun fromCode(code: String?): ChatMode? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}
