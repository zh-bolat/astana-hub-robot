package kz.astanahub.robot.backend

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.astanahub.robot.core.Lang
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `POST /v1/tts?stream=true` и `POST /v1/metrics/turn` по контракту (MockWebServer). */
class StreamingAndMetricsApiTest {

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

    private fun readAll(stream: TtsStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        stream.close()
        return out.toByteArray()
    }

    @Test
    fun `tts stream reads chunked mp3 and asks with stream=true`() = runBlocking {
        val mp3 = ByteArray(5_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/mpeg").setHeader("X-Speech-Provider", "elevenlabs")
                .setChunkedBody(Buffer().write(mp3), 512),
        )
        val stream = (api.ttsStream("Привет.", Lang.RU) as ApiResult.Success).value
        assertArrayEquals(mp3, readAll(stream))
        assertFalse(stream.fromCache)
        val request = server.takeRequest()
        assertEquals("/v1/tts?stream=true", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"lang\":\"ru\""))
    }

    @Test
    fun `tts stream from server cache is marked`() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Cache", "hit").setBody(Buffer().write(ByteArray(10))))
        assertTrue((api.ttsStream("x", Lang.RU) as ApiResult.Success).value.fromCache)
    }

    @Test
    fun `tts stream with fake provider or 503 is a failure`() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Speech-Provider", "fake").setBody(Buffer().write(ByteArray(64))))
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, (api.ttsStream("x", Lang.RU) as ApiResult.Failure).error.kind)
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"speech_unavailable","message":"down"}}"""),
        )
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, (api.ttsStream("x", Lang.RU) as ApiResult.Failure).error.kind)
    }

    @Test
    fun `connection dropped mid stream surfaces as IOException while reading`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody(Buffer().write(ByteArray(20_000)), 1024)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val stream = (api.ttsStream("x", Lang.RU) as ApiResult.Success).value
        var broken = false
        try {
            readAll(stream)
        } catch (e: IOException) {
            broken = true
        }
        assertTrue(broken)
    }

    @Test
    fun `turn metrics are posted as snake_case json without texts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val r = api.postTurnMetrics(
            TurnMetrics(
                sessionId = "s-1", requestId = "req_1", lang = Lang.RU, mode = TurnMetrics.MODE_AGENT,
                sttFinalMs = 420, chatFirstEventMs = 1350, firstPhraseMs = 1400, ttsFirstByteMs = 1650,
                firstAudioMs = 1820, vadSilenceMs = 500, fallback = false, interrupted = false,
            ),
        )
        assertTrue(r is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/v1/metrics/turn", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val json = RetrofitBackendApi.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("s-1", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("req_1", json["request_id"]!!.jsonPrimitive.content)
        assertEquals("agent", json["mode"]!!.jsonPrimitive.content)
        assertEquals(420, json["stt_final_ms"]!!.jsonPrimitive.int)
        assertEquals(1820, json["first_audio_ms"]!!.jsonPrimitive.int)
        assertEquals(500, json["vad_silence_ms"]!!.jsonPrimitive.int)
        assertEquals(
            setOf(
                "session_id", "request_id", "lang", "mode", "stt_final_ms", "chat_first_event_ms", "first_phrase_ms",
                "tts_first_byte_ms", "first_audio_ms", "vad_silence_ms", "fallback", "interrupted",
            ),
            json.keys,
        )
    }

    @Test
    fun `metrics error is returned, not thrown`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(api.postTurnMetrics(TurnMetrics(sessionId = "s", mode = TurnMetrics.MODE_LOCAL)) is ApiResult.Failure)
    }
}
