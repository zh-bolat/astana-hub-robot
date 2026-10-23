package kz.astanahub.robot.chat

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorEnvelopeDto
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.ChatRequestDto
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.backend.RobotStateDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/**
 * `POST /v1/chat` (только SSE). Поток событий — холодный [Flow]: отмена сборщика закрывает HTTP-соединение
 * (сервер видит обрыв и пишет ход с `interrupted: true`). Поток всегда заканчивается `Done` или `Error`:
 * HTTP-ошибка до начала потока и обрыв без `done` тоже приходят как [ChatEvent.Error].
 * Таймауты хода (turn ≤ 4 с, первая фраза ≤ 8 с) считает [AgentTurnRunner], здесь — только верхняя граница сокета.
 */
class OkHttpChatClient(
    private val baseUrl: String,
    private val token: String,
    client: OkHttpClient,
) {
    /** Свой клиент: общий callTimeout 15 с оборвал бы ход с `look` (до 30 с). */
    private val streamClient = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    fun chat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val body = requestJson.encodeToString(ChatRequestDto.serializer(), request.toDto())
        val httpRequest = Request.Builder()
            .url(endpoint("chat"))
            .header("Accept", "text/event-stream")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(body.toRequestBody(JSON))
            .build()
        val call = streamClient.newCall(httpRequest)
        launch(Dispatchers.IO) {
            val terminal: ChatEvent? = try {
                call.execute().use { response -> read(response) { send(it) } }
            } catch (e: InterruptedIOException) {
                if (call.isCanceled()) null else ChatEvent.Error(BackendError(ErrorKind.TIMEOUT, message = e.message))
            } catch (e: IOException) {
                if (call.isCanceled()) null
                else ChatEvent.Error(BackendError(ErrorKind.NETWORK, message = e.message)).also { Timber.w("chat stream: %s", e.message) }
            }
            if (terminal != null) send(terminal)
            channel.close()
        }
        awaitClose { call.cancel() }
    }

    /**
     * Читает поток до `done`/`error`; возвращает финальное событие, которое ещё нужно отправить
     * (ошибку HTTP или обрыв), либо null, если `done`/`error` уже отправлены.
     */
    private suspend fun read(response: Response, emit: suspend (ChatEvent) -> Unit): ChatEvent? {
        if (!response.isSuccessful) return ChatEvent.Error(httpError(response))
        val source = response.body?.source() ?: return ChatEvent.Error(BackendError(ErrorKind.UNKNOWN, message = "empty body"))
        val parser = SseParser()
        while (true) {
            val line = source.readUtf8Line() ?: break
            val message = parser.feed(line) ?: continue
            val event = ChatEventDecoder.decode(message) ?: continue
            emit(event)
            if (event is ChatEvent.Done || event is ChatEvent.Error) return null
        }
        return ChatEvent.Error(BackendError(ErrorKind.NETWORK, message = "stream closed before done"))
    }

    private fun endpoint(path: String) =
        RetrofitBackendApi.normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder().addPathSegments(path).build()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STREAM_READ_TIMEOUT_S = 35L

        /** `robot_state` и `text` уходят всегда (даже по умолчанию), null-поля — нет. */
        private val requestJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        internal fun ChatRequest.toDto() = ChatRequestDto(
            sessionId = sessionId,
            text = RetrofitBackendApi.clipQuestion(text),
            lang = lang?.code,
            input = input?.code,
            event = event,
            robotState = RobotStateDto(nearestPlaceId, navigating),
        )

        internal fun httpError(response: Response): BackendError {
            val raw = runCatching { response.body?.string() }.getOrNull()
            val envelope = raw?.takeIf { it.isNotBlank() }?.let {
                runCatching { RetrofitBackendApi.json.decodeFromString(ErrorEnvelopeDto.serializer(), it) }.getOrNull()
            }
            val code = envelope?.error?.code
            return BackendError(
                kind = RetrofitBackendApi.kindFor(code, response.code),
                httpStatus = response.code,
                serverCode = code,
                message = envelope?.error?.message,
                requestId = envelope?.requestId ?: response.header("X-Request-Id"),
                retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
            )
        }
    }
}
