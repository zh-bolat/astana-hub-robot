package kz.astanahub.robot.live

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.core.Lang
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/** Транспорт `WS /v1/live`: кадры туда, события сюда. Канал [events] закрывается после [LiveEvent.Closed]. */
interface LiveConnection {
    val events: ReceiveChannel<LiveEvent>

    fun sendText(frame: String): Boolean

    /** Микрофон: PCM 16-bit LE mono 16 кГц. */
    fun sendAudio(pcm: ByteArray): Boolean

    /** Штатно закрыть (посетитель ушёл / переход в запасной режим). Событие Closed не приходит. */
    fun close()
}

/** OkHttp-реализация; `baseUrl` — как у Retrofit (`http://host:8010/v1`) → `ws://host:8010/v1/live`. */
class OkHttpLiveConnection(
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    lang: Lang,
    sessionId: String,
) : LiveConnection {

    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<LiveEvent> = channel

    @Volatile private var closedByUs = false
    private val ws: WebSocket

    init {
        val request = Request.Builder().url(liveUrl(baseUrl, lang, sessionId)).apply {
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        ws = client.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS) // разговор длится минутами
            .readTimeout(0, TimeUnit.SECONDS) // тишина в разговоре — норма; живость — ping
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
            .newWebSocket(request, Listener())
    }

    override fun sendText(frame: String): Boolean = !closedByUs && ws.send(frame)

    override fun sendAudio(pcm: ByteArray): Boolean = !closedByUs && ws.send(pcm.toByteString())

    override fun close() {
        if (closedByUs) return
        closedByUs = true
        ws.close(1000, null)
        channel.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            LiveEvent.parse(text)?.let { channel.trySend(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            channel.trySend(LiveEvent.Audio(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (!closedByUs) channel.trySend(LiveEvent.Closed(LiveEvent.closeError(code, reason)))
            channel.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUs) return
            Timber.w("live ws failure (http=%s): %s", response?.code, t.message)
            val kind = when (response?.code) {
                401, 403 -> ErrorKind.UNAUTHORIZED
                404 -> ErrorKind.NOT_CONFIGURED // сервер без /v1/live
                else -> ErrorKind.NETWORK
            }
            channel.trySend(LiveEvent.Closed(BackendError(kind, httpStatus = response?.code, message = t.message)))
            channel.close()
        }
    }

    companion object {
        const val PING_INTERVAL_S = 10L

        fun liveUrl(baseUrl: String, lang: Lang, sessionId: String): String =
            RetrofitBackendApi.normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
                .addPathSegment("live")
                .addQueryParameter("session_id", sessionId)
                .addQueryParameter("lang", lang.code)
                .build()
                .toString()
    }
}
