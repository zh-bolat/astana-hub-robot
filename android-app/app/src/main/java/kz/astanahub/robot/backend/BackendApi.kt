package kz.astanahub.robot.backend

import kz.astanahub.robot.core.Lang

/** Клиент backend по docs/api-contract.md (v1). Все методы не бросают исключений. */
interface BackendApi {
    /** GET /v1/health (без авторизации) → версия сервера. */
    suspend fun health(): ApiResult<String> = ApiResult.Failure(BackendError(ErrorKind.NOT_CONFIGURED))

    suspend fun ask(question: String, context: AskContext): ApiResult<Answer>

    /**
     * POST /v1/chat: агент помнит разговор и сам выбирает действие робота.
     * По умолчанию (заглушки, сервер без /chat) — ответ /ask без действий.
     */
    suspend fun chat(question: String, context: AskContext): ApiResult<ChatAnswer> =
        ask(question, context).map { ChatAnswer(it) }

    /** POST /v1/sessions/{id}/messages: локальный ответ робота — в историю и память агента. */
    suspend fun recordLocal(message: LocalMessage): ApiResult<Unit> = ApiResult.Success(Unit)

    suspend fun look(jpeg: ByteArray, question: String?, context: AskContext): ApiResult<Answer>

    /** Точки карты; с [etag] сервер может ответить 304 → [PlacesResult.notModified]. */
    suspend fun places(etag: String? = null): ApiResult<PlacesResult>

    suspend fun quickQuestions(lang: Lang): ApiResult<List<QuickQuestion>>

    /** Распознать фразу: WAV PCM16 mono 16 кГц, ≤ 15 с. [lang] = null — автоопределение на сервере. */
    suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String): ApiResult<SttResult>

    /** Синтез одной фразы (≤ 300 символов) → MP3. */
    suspend fun tts(text: String, lang: Lang): ApiResult<ByteArray>

    /** Live-распознавание одной фразы (WS /v1/stt/stream); null — не поддерживается (заглушки). */
    fun openLiveStt(lang: Lang?, sessionId: String): LiveSttSession? = null
}
