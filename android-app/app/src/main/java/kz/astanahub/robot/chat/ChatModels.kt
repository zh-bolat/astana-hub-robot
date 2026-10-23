package kz.astanahub.robot.chat

import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.core.Lang

/** Откуда пришла реплика (`input` в `POST /v1/chat`, для аналитики). */
enum class ChatInput(val code: String) { VOICE("voice"), TEXT("text"), BUTTON("button") }

/** Запрос `POST /v1/chat` (docs/api-contract.md). Пустой [text] допустим только с [event]. */
data class ChatRequest(
    val sessionId: String,
    val text: String,
    val lang: Lang?,
    val input: ChatInput? = null,
    val event: String? = null,
    val nearestPlaceId: String? = null,
    val navigating: Boolean = false,
) {
    companion object {
        /** `event: visitor_arrived` — к роботу подошёл человек, агент здоровается. */
        const val EVENT_VISITOR_ARRIVED = "visitor_arrived"
    }
}

/** События SSE `/v1/chat`. `turn` — всегда первое, `done` или `error` — всегда последнее. */
sealed interface ChatEvent {
    data class Turn(val turnId: String, val requestId: String?) : ChatEvent

    /** `neutral`/`happy`/`thinking`/`surprised`/`sad`/`excited` (неизвестное имя — как есть, лицо решит само). */
    data class Emotion(val name: String) : ChatEvent

    data class Phrase(val text: String) : ChatEvent

    data class Action(val name: String, val args: Map<String, String>) : ChatEvent {
        val placeId: String? get() = args["place_id"]?.takeIf { it.isNotBlank() }
        val mapName: String? get() = args["map_name"]?.takeIf { it.isNotBlank() }

        companion object {
            const val WHERE_IS = "where_is"
            const val GUIDE_TO = "guide_to"
            const val STOP = "stop"
            const val LOOK = "look"
        }
    }

    data class Done(val requestId: String?, val lang: Lang?, val tools: List<String>) : ChatEvent

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
