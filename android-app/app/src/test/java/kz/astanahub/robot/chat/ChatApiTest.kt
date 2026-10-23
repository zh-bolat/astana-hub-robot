package kz.astanahub.robot.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.LocalMessage
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.core.Lang
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `/v1/chat` поверх MockWebServer: SSE, ошибки до потока, обрыв, кадр `look`, история. runBlocking — реальное время. */
class ChatApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RetrofitBackendApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = RetrofitBackendApi(server.url("/v1").toString(), token = "secret-token")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun sse(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream").setBody(body)

    private val request = ChatRequest(
        sessionId = "s-1", text = "Где тут поработать?", lang = Lang.RU, input = ChatInput.VOICE,
        nearestPlaceId = "reception", navigating = false,
    )

    @Test
    fun `chat streams events and sends contract request`() = runBlocking {
        server.enqueue(
            sse(
                "event: turn\ndata: {\"turn_id\":\"trn_1\",\"request_id\":\"req_1\"}\n\n" +
                    "event: emotion\ndata: {\"name\":\"thinking\"}\n\n" +
                    "event: phrase\ndata: {\"text\":\"Есть коворкинг.\"}\n\n" +
                    "event: done\ndata: {\"request_id\":\"req_1\",\"lang\":\"ru\",\"tools\":[]}\n\n",
            ),
        )
        val events = withTimeout(5_000) { api.chat(request).toList() }
        assertEquals(
            listOf(
                ChatEvent.Turn("trn_1", "req_1"),
                ChatEvent.Emotion("thinking"),
                ChatEvent.Phrase("Есть коворкинг."),
                ChatEvent.Done("req_1", Lang.RU, emptyList()),
            ),
            events,
        )
        val sent = server.takeRequest()
        assertEquals("/v1/chat", sent.path)
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals("Bearer secret-token", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertTrue(body, body.contains("\"session_id\":\"s-1\""))
        assertTrue(body, body.contains("\"input\":\"voice\""))
        assertTrue(body, body.contains("\"robot_state\":{\"nearest_place_id\":\"reception\",\"navigating\":false}"))
        assertTrue(body, !body.contains("\"event\""))
    }

    @Test
    fun `visitor arrived goes with empty text and event`() = runBlocking {
        server.enqueue(sse("event: turn\ndata: {\"turn_id\":\"t\"}\n\nevent: done\ndata: {}\n\n"))
        api.chat(ChatRequest("s-2", "", Lang.KK, event = ChatRequest.EVENT_VISITOR_ARRIVED)).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"text\":\"\""))
        assertTrue(body, body.contains("\"event\":\"visitor_arrived\""))
    }

    @Test
    fun `http error before the stream becomes an error event`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "3")
                .setBody("{\"error\":{\"code\":\"rate_limited\",\"message\":\"slow\"},\"request_id\":\"req_x\"}"),
        )
        val e = api.chat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.RATE_LIMITED, e.error.kind)
        assertEquals(3L, e.error.retryAfterSeconds)
    }

    @Test
    fun `stream closed before done ends with a network error`() = runBlocking {
        server.enqueue(sse("event: turn\ndata: {\"turn_id\":\"t\"}\n\nevent: phrase\ndata: {\"text\":\"Раз.\"}\n\n"))
        val events = api.chat(request).toList()
        assertEquals(ChatEvent.Phrase("Раз."), events[1])
        assertEquals(ErrorKind.NETWORK, (events.last() as ChatEvent.Error).error.kind)
    }

    @Test
    fun `server unreachable is a network error, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = api.chat(request).toList().single() as ChatEvent.Error
        assertTrue(e.error.kind == ErrorKind.NETWORK || e.error.kind == ErrorKind.TIMEOUT)
    }

    @Test
    fun `cancelling the collector closes the connection (interruption)`() = runBlocking {
        // Сервер прислал turn и «висит» — посетитель перебил.
        server.enqueue(
            sse("event: turn\ndata: {\"turn_id\":\"t\"}\n\n" + "event: phrase\ndata: {\"text\":\"…\"}\n\n".repeat(50))
                .throttleBody(40, 1, TimeUnit.SECONDS),
        )
        val first = withTimeout(5_000) { api.chat(request).first() }
        assertEquals(ChatEvent.Turn("t", null), first)
        // first() отменяет сбор после первого события: следующий запрос проходит, соединение не висит.
        server.enqueue(sse("event: done\ndata: {}\n\n"))
        val next = withTimeout(5_000) { api.chat(request).toList() }
        assertTrue(next.single() is ChatEvent.Done)
    }

    @Test
    fun `frame is posted as multipart image to the turn`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = api.chatFrame("trn_7", byteArrayOf(1, 2, 3))
        assertTrue(result is ApiResult.Success)
        val sent = server.takeRequest()
        assertEquals("/v1/chat/trn_7/frame", sent.path)
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = sent.body.readUtf8()
        assertTrue(body, body.contains("name=\"image\""))
        assertTrue(body, body.contains("image/jpeg"))
    }

    @Test
    fun `frame for a closed turn is a failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":{\"code\":\"not_found\"}}"))
        assertTrue(api.chatFrame("trn_old", byteArrayOf(1)) is ApiResult.Failure)
    }

    @Test
    fun `local answer goes to session history`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"request_id\":\"req_5\"}"))
        val r = api.postLocalMessage(
            "s-9", LocalMessage(LocalMessage.KIND_WHERE_IS, "Где кафе?", "Кафе справа.", Lang.RU, "cafe"),
        )
        assertTrue(r is ApiResult.Success)
        val sent = server.takeRequest()
        assertEquals("/v1/sessions/s-9/messages", sent.path)
        val body = sent.body.readUtf8()
        assertTrue(body, body.contains("\"kind\":\"where_is\""))
        assertTrue(body, body.contains("\"place_id\":\"cafe\""))
    }
}
