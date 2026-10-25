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

/** `/v1/chat` поверх MockWebServer: SSE, ошибки до потока, обрыв, история. runBlocking — реальное время. */
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

    private val request = ChatRequest(sessionId = "s-1", question = "Где тут поработать?", lang = Lang.RU, placeId = "reception")

    @Test
    fun `chat streams events and sends contract request`() = runBlocking {
        server.enqueue(
            sse(
                "event: emotion\ndata: {\"name\":\"thinking\"}\n\n" +
                    "event: action\ndata: {\"type\":\"where_is\",\"place_id\":\"coworking\"}\n\n" +
                    "event: phrase\ndata: {\"text\":\"Есть коворкинг.\"}\n\n" +
                    "event: done\ndata: {\"request_id\":\"req_1\",\"lang\":\"ru\",\"found\":true,\"sources\":[]," +
                    "\"actions\":[{\"type\":\"where_is\",\"place_id\":\"coworking\"}]}\n\n",
            ),
        )
        val events = withTimeout(5_000) { api.chat(request).toList() }
        assertEquals(
            listOf(
                ChatEvent.Emotion("thinking"),
                ChatEvent.Action(ChatAction("where_is", "coworking")),
                ChatEvent.Phrase("Есть коворкинг."),
                ChatEvent.Done("req_1", Lang.RU, actions = listOf(ChatAction("where_is", "coworking"))),
            ),
            events,
        )
        val sent = server.takeRequest()
        assertEquals("/v1/chat", sent.path)
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals("Bearer secret-token", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertEquals(
            "{\"question\":\"Где тут поработать?\",\"lang\":\"ru\",\"session_id\":\"s-1\",\"place_id\":\"reception\"}",
            body,
        )
    }

    @Test
    fun `noise - sse done with ignored and json mode with ignored`() = runBlocking {
        server.enqueue(sse("event: done\ndata: {\"request_id\":\"r1\",\"lang\":\"ru\",\"found\":false,\"actions\":[],\"ignored\":true}\n\n"))
        val sse = withTimeout(5_000) { api.chat(request).toList() }
        assertTrue((sse.single() as ChatEvent.Done).ignored)

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"r2\",\"lang\":\"ru\",\"answer\":\"\",\"found\":false,\"sources\":[],\"actions\":[],\"ignored\":true}"),
        )
        val json = withTimeout(5_000) { api.chat(request).toList() }
        val done = json.single() as ChatEvent.Done
        assertTrue(done.ignored)
        assertEquals("r2", done.requestId)
    }

    @Test
    fun `visitor arrived goes with empty text and event`() = runBlocking {
        server.enqueue(sse("event: phrase\ndata: {\"text\":\"Сәлем!\"}\n\nevent: done\ndata: {}\n\n"))
        api.chat(ChatRequest("s-2", "", Lang.KK, event = ChatRequest.EVENT_VISITOR_ARRIVED)).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"question\":\"\""))
        assertTrue(body, body.contains("\"lang\":\"kk\""))
        assertTrue(body, !body.contains("place_id"))
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
        server.enqueue(sse("event: emotion\ndata: {\"name\":\"happy\"}\n\nevent: phrase\ndata: {\"text\":\"Раз.\"}\n\n"))
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
        // Сервер прислал первую фразу и «висит» — посетитель перебил.
        server.enqueue(
            sse("event: phrase\ndata: {\"text\":\"Раз.\"}\n\n" + "event: phrase\ndata: {\"text\":\"…\"}\n\n".repeat(50))
                .throttleBody(40, 1, TimeUnit.SECONDS),
        )
        val first = withTimeout(5_000) { api.chat(request).first() }
        assertEquals(ChatEvent.Phrase("Раз."), first)
        // first() отменяет сбор после первого события: следующий запрос проходит, соединение не висит.
        server.enqueue(sse("event: done\ndata: {}\n\n"))
        val next = withTimeout(5_000) { api.chat(request).toList() }
        assertTrue(next.single() is ChatEvent.Done)
    }

    @Test
    fun `server error event in the stream keeps the common error format`() = runBlocking {
        server.enqueue(sse("event: error\ndata: {\"error\":{\"code\":\"timeout\",\"message\":\"agent\"},\"request_id\":\"req_t\"}\n\n"))
        val e = api.chat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.TIMEOUT, e.error.kind)
        assertEquals("req_t", e.error.requestId)
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
