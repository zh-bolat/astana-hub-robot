package kz.astanahub.robot.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kz.astanahub.robot.core.Lang
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveSttTest {

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

    @Test
    fun `stream url carries session and language`() {
        val url = OkHttpLiveSttSession.streamUrl("http://10.0.2.2:8010/v1", Lang.KK, "s 1")
        assertEquals("http://10.0.2.2:8010/v1/stt/stream?session_id=s%201&lang=kk", url)
    }

    @Test
    fun `parses server events`() {
        assertEquals(LiveSttEvent.Ready("elevenlabs-realtime"), OkHttpLiveSttSession.parseEvent("""{"type":"ready","provider":"elevenlabs-realtime"}"""))
        assertEquals(LiveSttEvent.Partial("Где"), OkHttpLiveSttSession.parseEvent("""{"type":"partial","text":" Где "}"""))
        val final = OkHttpLiveSttSession.parseEvent("""{"type":"final","request_id":"req_1","text":"Где кафе?","lang":"ru"}""")
        assertEquals(LiveSttEvent.Final(SttResult("req_1", "Где кафе?", Lang.RU, null)), final)
        val error = OkHttpLiveSttSession.parseEvent("""{"type":"error","code":"speech_unavailable","message":"x"}""")
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, (error as LiveSttEvent.Failed).error.kind)
        assertEquals(null, OkHttpLiveSttSession.parseEvent("not json"))
    }

    @Test
    fun `streams audio and receives partial then final`() = runBlocking {
        val received = mutableListOf<Int>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"ready","provider":"elevenlabs-realtime"}""")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received += bytes.size
                    webSocket.send("""{"type":"partial","text":"Где кафе"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"end\"")) {
                        webSocket.send("""{"type":"final","request_id":"req_1","text":"Где кафе?","lang":"ru"}""")
                        webSocket.close(1000, null)
                    }
                }
            }),
        )
        val session = api.openLiveStt(Lang.RU, "s-1")
        val events = mutableListOf<LiveSttEvent>()
        withTimeout(5_000) {
            events += session.events.receive() // ready
            session.sendPcm(ByteArray(3200))
            events += session.events.receive() // partial
            session.end()
            for (e in session.events) events += e
        }
        assertEquals(listOf(3200), received)
        assertTrue(events[0] is LiveSttEvent.Ready)
        assertEquals(LiveSttEvent.Partial("Где кафе"), events[1])
        assertEquals("Где кафе?", (events.last() as LiveSttEvent.Final).result.text)
        val request = server.takeRequest()
        assertEquals("/v1/stt/stream?session_id=s-1&lang=ru", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun `old server without endpoint fails before ready`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val session = api.openLiveStt(null, "s-1")
        val event = withTimeout(5_000) { session.events.receive() }
        assertEquals(ErrorKind.NOT_CONFIGURED, (event as LiveSttEvent.Failed).error.kind)
    }
}
