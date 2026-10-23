package kz.astanahub.robot.backend

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.astanahub.robot.core.Lang
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/** Событие live-распознавания (WS /v1/stt/stream, docs/api-contract.md). */
sealed interface LiveSttEvent {
    data class Ready(val provider: String) : LiveSttEvent
    /** Текст фразы целиком на данный момент — заменяет предыдущий. */
    data class Partial(val text: String) : LiveSttEvent
    data class Final(val result: SttResult) : LiveSttEvent
    data class Failed(val error: BackendError) : LiveSttEvent
}

/** Одна фраза: звук уходит по ходу речи, ответы сервера — в [events] (канал закрывается после Final/Failed). */
interface LiveSttSession {
    val events: ReceiveChannel<LiveSttEvent>

    /** PCM 16-bit little-endian mono 16 кГц. */
    fun sendPcm(pcm: ByteArray): Boolean

    /** Посетитель закончил (необязательно: сервер сам находит конец фразы). */
    fun end()

    /** Отмена: финального текста не будет. */
    fun cancel()
}

/** OkHttp-реализация; `baseUrl` — как у Retrofit (`http://host:8010/v1`). */
class OkHttpLiveSttSession(
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    lang: Lang?,
    sessionId: String,
) : LiveSttSession {

    private val channel = Channel<LiveSttEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<LiveSttEvent> = channel

    @Volatile private var ready = false
    private val ws: WebSocket

    init {
        val url = streamUrl(baseUrl, lang, sessionId)
        val request = Request.Builder().url(url).apply {
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        ws = client.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS) // сессия живёт, пока посетитель говорит
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .newWebSocket(request, Listener())
    }

    override fun sendPcm(pcm: ByteArray): Boolean = ws.send(pcm.toByteString())

    override fun end() {
        ws.send("""{"type":"end"}""")
    }

    override fun cancel() {
        ws.send("""{"type":"cancel"}""")
        ws.close(1000, null)
        channel.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseEvent(text) ?: return
            if (event is LiveSttEvent.Ready) ready = true
            channel.trySend(event)
            if (event is LiveSttEvent.Final || event is LiveSttEvent.Failed) channel.close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000) {
                val kind = when {
                    reason.startsWith("unauthorized") -> ErrorKind.UNAUTHORIZED
                    reason.startsWith("rate_limited") -> ErrorKind.RATE_LIMITED
                    else -> ErrorKind.UNKNOWN
                }
                channel.trySend(LiveSttEvent.Failed(BackendError(kind, message = "ws closed $code $reason")))
            }
            webSocket.close(1000, null)
            channel.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w("live STT failure (ready=%s, http=%s): %s", ready, response?.code, t.message)
            val kind = when (response?.code) {
                401, 403 -> ErrorKind.UNAUTHORIZED
                404 -> ErrorKind.NOT_CONFIGURED // старый сервер без /stt/stream
                else -> ErrorKind.NETWORK
            }
            channel.trySend(LiveSttEvent.Failed(BackendError(kind, httpStatus = response?.code, message = t.message)))
            channel.close()
        }
    }

    companion object {
        fun streamUrl(baseUrl: String, lang: Lang?, sessionId: String): String =
            RetrofitBackendApi.normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
                .addPathSegments("stt/stream")
                .addQueryParameter("session_id", sessionId)
                .apply { if (lang != null) addQueryParameter("lang", lang.code) }
                .build()
                .toString()

        fun parseEvent(text: String): LiveSttEvent? {
            val obj: JsonObject = runCatching { RetrofitBackendApi.json.parseToJsonElement(text).jsonObject }
                .getOrNull() ?: return null
            fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
            return when (str("type")) {
                "ready" -> LiveSttEvent.Ready(str("provider").orEmpty())
                "partial" -> LiveSttEvent.Partial(str("text").orEmpty().trim())
                "final" -> LiveSttEvent.Final(
                    SttResult(str("request_id"), str("text").orEmpty().trim(), Lang.fromCode(str("lang")), null),
                )
                "error" -> {
                    val code = str("code")
                    LiveSttEvent.Failed(
                        BackendError(RetrofitBackendApi.kindFor(code, 0), serverCode = code, message = str("message")),
                    )
                }
                else -> null
            }
        }
    }
}
