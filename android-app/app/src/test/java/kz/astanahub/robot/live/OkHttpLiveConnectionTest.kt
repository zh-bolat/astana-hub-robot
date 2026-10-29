package kz.astanahub.robot.live

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** OkHttpLiveConnection против MockWebServer (настоящий WebSocket). */
class OkHttpLiveConnectionTest {

    private lateinit var server: MockWebServer
    private val fromClient = LinkedBlockingQueue<Any>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Сервер отвечает на закрытие клиента (иначе MockWebServer.shutdown ждёт сокет). */
    private open class ServerListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun connection() = OkHttpLiveConnection(OkHttpClient(), server.url("/v1").toString(), "tok", Lang.KK, "s1")

    private fun receive(conn: LiveConnection): LiveEvent = runBlocking { withTimeout(5_000) { conn.events.receive() } }

    @Test
    fun `token, url, client frames and server events with binary audio`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ServerListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"ready","audio_format":"pcm_s16le_24000"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                fromClient += text
                if (text.contains("hello")) {
                    webSocket.send("""{"type":"turn_start","turn_id":"t1"}""")
                    webSocket.send(byteArrayOf(1, 2, 3, 4).toByteString())
                    webSocket.send("""{"type":"turn_end","turn_id":"t1","lang":"kk","found":true}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fromClient += bytes.toByteArray()
            }
        }))
        val conn = connection()
        assertEquals(LiveEvent.Ready(LiveAudioFormat(24_000)), receive(conn))
        assertTrue(conn.sendText(LiveFrames.hello(Lang.KK, null, aec = false)))
        assertTrue(conn.sendAudio(ByteArray(1280) { 5 }))
        assertEquals(LiveEvent.TurnStart("t1"), receive(conn))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (receive(conn) as LiveEvent.Audio).pcm)
        assertEquals(LiveEvent.TurnEnd("t1", Lang.KK, true), receive(conn))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("/v1/live?session_id=s1&lang=kk", request.path)
        val hello = fromClient.poll(5, TimeUnit.SECONDS) as String
        assertTrue(hello, hello.contains("\"type\":\"hello\""))
        val audio = fromClient.poll(5, TimeUnit.SECONDS) as ByteArray
        assertEquals(1280, audio.size)
        conn.close()
    }

    @Test
    fun `server closes with an error code - Closed with error`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ServerListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1011, "internal")
            }
        }))
        val conn = connection()
        val event = receive(conn) as LiveEvent.Closed
        assertEquals(ErrorKind.UNKNOWN, event.error?.kind)
    }

    @Test
    fun `server without live - 404 - Closed NOT_CONFIGURED`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val conn = connection()
        val event = receive(conn) as LiveEvent.Closed
        assertEquals(ErrorKind.NOT_CONFIGURED, event.error?.kind)
    }

    @Test
    fun `close by the robot - no Closed event, nothing more is sent`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerListener()))
        val conn = connection()
        conn.close()
        assertEquals(false, conn.sendText(LiveFrames.interrupt()))
        assertTrue(runBlocking { withTimeout(5_000) { conn.events.receiveCatching().isClosed } })
    }
}
