package kz.astanahub.robot.backend

import kz.astanahub.robot.core.Lang

/** Ответ /v1/ask и /v1/look (одинаковый формат). */
data class Answer(
    val requestId: String?,
    val text: String,
    val phrases: List<String>,
    val lang: Lang?,
    val found: Boolean,
)

data class Place(
    val id: String,
    val mapName: String,
    val names: Map<String, String>,
    val synonyms: List<String>,
    val description: Map<String, String>,
)

data class PlacesResult(
    /** null при 304 Not Modified. */
    val places: List<Place>?,
    val etag: String?,
    val notModified: Boolean,
)

data class QuickQuestion(val id: String, val text: String)

/** Ответ /v1/stt. Пустой [text] — тишина, в /ask не отправляем. */
data class SttResult(
    val requestId: String?,
    val text: String,
    val lang: Lang?,
    val confidence: Double?,
)

/** Виды ошибок из раздела «Ошибки» контракта + клиентские (сеть, таймаут, нет настроек). */
enum class ErrorKind {
    BAD_REQUEST,
    UNAUTHORIZED,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    LLM_UNAVAILABLE,
    /** 503 speech_unavailable: провайдер STT/TTS недоступен — ответ только текстом / встроенным TTS. */
    SPEECH_UNAVAILABLE,
    TIMEOUT,
    /** Нет сети / сервер недоступен. */
    NETWORK,
    /** BACKEND_URL не задан. */
    NOT_CONFIGURED,
    UNKNOWN,
}

data class BackendError(
    val kind: ErrorKind,
    val httpStatus: Int? = null,
    val serverCode: String? = null,
    val message: String? = null,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: BackendError) : ApiResult<Nothing>
}

/** Контекст запроса к /ask и /look. */
data class AskContext(
    val sessionId: String,
    val lang: Lang?,
    val placeId: String? = null,
)

/** Действие робота из ответа агента (/v1/chat). */
enum class ChatActionType(val code: String) {
    /** Показать, где место: под ответом — кнопка «Проводить». */
    WHERE_IS("where_is"),
    /** Проводить: робот едет, когда договорил ответ. */
    GUIDE_TO("guide_to"),
    /** Посмотреть камерой: кадр → /v1/look. */
    LOOK("look");

    companion object {
        fun fromCode(code: String?): ChatActionType? = entries.firstOrNull { it.code == code }
    }
}

data class ChatAction(val type: ChatActionType, val placeId: String?)

/** Ответ /v1/chat: как у /ask плюс действия робота. */
data class ChatAnswer(val answer: Answer, val actions: List<ChatAction> = emptyList())

/** Что робот ответил сам, без сервера («где X», «проводи», «где я») — в историю и память агента. */
data class LocalMessage(
    val sessionId: String,
    val kind: String,
    val question: String,
    val answer: String,
    val lang: Lang,
    val placeId: String? = null,
    val found: Boolean = true,
)
