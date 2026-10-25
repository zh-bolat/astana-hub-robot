package kz.astanahub.robot.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang

/** Клиент backend по docs/api-contract.md (v1). Все методы не бросают исключений. */
interface BackendApi {
    /** GET /v1/health (без авторизации) → версия сервера. */
    suspend fun health(): ApiResult<String> = ApiResult.Failure(BackendError(ErrorKind.NOT_CONFIGURED))

    suspend fun ask(question: String, context: AskContext): ApiResult<Answer>

    suspend fun look(jpeg: ByteArray, question: String?, context: AskContext): ApiResult<Answer>

    /** Точки карты; с [etag] сервер может ответить 304 → [PlacesResult.notModified]. */
    suspend fun places(etag: String? = null): ApiResult<PlacesResult>

    suspend fun quickQuestions(lang: Lang): ApiResult<List<QuickQuestion>>

    /** Распознать фразу: WAV PCM16 mono 16 кГц, ≤ 15 с. [lang] = null — автоопределение на сервере. */
    suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String): ApiResult<SttResult>

    /** Синтез одной фразы (≤ 300 символов) → MP3. */
    suspend fun tts(text: String, lang: Lang): ApiResult<ByteArray>

    /**
     * Потоковый синтез (`POST /v1/tts?stream=true`): Success — заголовки пришли, байты читаются из [TtsStream]
     * по мере синтеза. По умолчанию — обычный [tts], обёрнутый в поток.
     */
    suspend fun ttsStream(text: String, lang: Lang): ApiResult<TtsStream> = when (val r = tts(text, lang)) {
        is ApiResult.Success -> ApiResult.Success(BytesTtsStream(r.value))
        is ApiResult.Failure -> r
    }

    /** `POST /v1/metrics/turn` — тайминги хода (без текстов). Ошибку вызывающий только логирует. */
    suspend fun postTurnMetrics(metrics: TurnMetrics): ApiResult<Unit> =
        ApiResult.Failure(BackendError(ErrorKind.NOT_CONFIGURED))

    /** Live-распознавание одной фразы (WS /v1/stt/stream); null — не поддерживается (заглушки). */
    fun openLiveStt(lang: Lang?, sessionId: String): LiveSttSession? = null

    /**
     * Live STT в принципе есть: тогда робот слушает микрофон сам и открывает WS только на речь
     * (порог громкости и минимальная длительность). false — фраза пишется целиком и уходит в `/v1/stt`.
     */
    val supportsLiveStt: Boolean get() = false

    /** Живой голосовой режим (WS /v1/live); null — не поддерживается (заглушки, нет адреса сервера). */
    fun openLive(lang: Lang, sessionId: String): kz.astanahub.robot.live.LiveConnection? = null

    /**
     * `POST /v1/chat` (SSE). Поток заканчивается [ChatEvent.Done] или [ChatEvent.Error];
     * отмена сборщика обрывает соединение (посетитель перебил). Действие `look` клиент выполняет сам
     * через [look] (`POST /v1/look`), отдельного эндпоинта для кадра нет.
     */
    fun chat(request: ChatRequest): Flow<ChatEvent> =
        flowOf(ChatEvent.Error(BackendError(ErrorKind.NOT_CONFIGURED, message = "chat is not supported")))

    /** `POST /v1/sessions/{session_id}/messages` — ответ, который робот дал сам (история и лог). */
    suspend fun postLocalMessage(sessionId: String, message: LocalMessage): ApiResult<Unit> =
        ApiResult.Failure(BackendError(ErrorKind.NOT_CONFIGURED))
}

/** Локальный ответ робота для истории разговора. [kind]: `where_is` / `guide_to` / `where_am_i`. */
data class LocalMessage(
    val kind: String,
    val question: String,
    val answer: String,
    val lang: Lang,
    val placeId: String? = null,
    val found: Boolean = true,
    val interrupted: Boolean = false,
) {
    companion object {
        const val KIND_WHERE_IS = "where_is"
        const val KIND_GUIDE_TO = "guide_to"
        const val KIND_WHERE_AM_I = "where_am_i"
    }
}
