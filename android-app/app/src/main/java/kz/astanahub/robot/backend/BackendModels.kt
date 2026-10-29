package kz.astanahub.robot.backend

import kz.astanahub.robot.core.Lang

/** Ответ /v1/ask и /v1/look (одинаковый формат). */
data class Answer(
    val requestId: String?,
    val text: String,
    val phrases: List<String>,
    val lang: Lang?,
    val found: Boolean,
    /** Источники для показа (контракт v1.3, необязательно; старый сервер не шлёт). */
    val citations: List<Citation> = emptyList(),
)

/**
 * Источник ответа для человека (`citations` в `/v1/ask`, `/v1/chat` `done`, live `turn_end`; контракт v1.3).
 * Вслух не произносится — маленькая подпись под ответом робота на экране 2. [type]: document | fact | place;
 * [url] — только у документа.
 */
data class Citation(val id: String, val type: String, val title: String, val url: String? = null)

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
