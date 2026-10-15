package kz.astanahub.robot.backend

import kotlinx.coroutines.runBlocking
import kz.astanahub.robot.core.Lang
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** runBlocking, а не runTest: withTimeout в runTest работает на виртуальном времени и срабатывает сразу. */
class RetrofitBackendApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RetrofitBackendApi
    private val ctx = AskContext(sessionId = "s-1", lang = Lang.RU, placeId = "reception")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = RetrofitBackendApi(server.url("/v1").toString(), token = "secret-token")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `ask parses answer and sends contract request`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"request_id":"req_1","answer":"Регистрация на 2 этаже. Возьмите удостоверение.",
                   "phrases":["Регистрация на 2 этаже.","Возьмите удостоверение."],
                   "lang":"ru","found":true,"sources":["fact:1"],"extra_field":42}""",
            ),
        )

        val result = api.ask("Где регистрация?", ctx)

        val answer = (result as ApiResult.Success).value
        assertEquals("req_1", answer.requestId)
        assertEquals(listOf("Регистрация на 2 этаже.", "Возьмите удостоверение."), answer.phrases)
        assertEquals(Lang.RU, answer.lang)
        assertTrue(answer.found)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/ask", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"session_id\":\"s-1\""))
        assertTrue(body, body.contains("\"place_id\":\"reception\""))
        assertTrue(body, body.contains("\"lang\":\"ru\""))
    }

    @Test
    fun `ask without phrases falls back to full answer`() = runBlocking {
        server.enqueue(json(200, """{"answer":"Не знаю, обратитесь на ресепшен.","found":false}"""))

        val answer = (api.ask("?", ctx) as ApiResult.Success).value

        assertEquals(listOf("Не знаю, обратитесь на ресепшен."), answer.phrases)
        assertFalse(answer.found)
    }

    @Test
    fun `null lang is not sent`() = runBlocking {
        server.enqueue(json(200, """{"answer":"ok"}"""))
        api.ask("hi", ctx.copy(lang = null, placeId = null))
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("\"lang\""))
    }

    @Test
    fun `error envelope is mapped by code`() = runBlocking {
        server.enqueue(
            json(503, """{"error":{"code":"llm_unavailable","message":"down"},"request_id":"req_9"}"""),
        )

        val error = (api.ask("q", ctx) as ApiResult.Failure).error

        assertEquals(ErrorKind.LLM_UNAVAILABLE, error.kind)
        assertEquals(503, error.httpStatus)
        assertEquals("llm_unavailable", error.serverCode)
        assertEquals("req_9", error.requestId)
    }

    @Test
    fun `429 carries Retry-After`() = runBlocking {
        server.enqueue(
            json(429, """{"error":{"code":"rate_limited","message":"slow down"}}""").setHeader("Retry-After", "3"),
        )

        val error = (api.ask("q", ctx) as ApiResult.Failure).error

        assertEquals(ErrorKind.RATE_LIMITED, error.kind)
        assertEquals(3L, error.retryAfterSeconds)
    }

    @Test
    fun `non json error falls back to http status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>nope</html>"))
        assertEquals(ErrorKind.UNAUTHORIZED, (api.ask("q", ctx) as ApiResult.Failure).error.kind)

        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertEquals(ErrorKind.UNKNOWN, (api.ask("q", ctx) as ApiResult.Failure).error.kind)
    }

    @Test
    fun `malformed success body is UNKNOWN, not a crash`() = runBlocking {
        server.enqueue(json(200, """{"unexpected":true}"""))
        assertEquals(ErrorKind.UNKNOWN, (api.ask("q", ctx) as ApiResult.Failure).error.kind)
    }

    @Test
    fun `connection failure is NETWORK`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ErrorKind.NETWORK, (api.ask("q", ctx) as ApiResult.Failure).error.kind)
    }

    @Test
    fun `slow server is TIMEOUT`() = runBlocking {
        val fast = RetrofitBackendApi(
            server.url("/v1/").toString(),
            token = "",
            timeouts = BackendTimeouts(askMillis = 200, lookMillis = 200, referenceMillis = 200),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(ErrorKind.TIMEOUT, (fast.ask("q", ctx) as ApiResult.Failure).error.kind)
    }

    @Test
    fun `look sends multipart image and meta`() = runBlocking {
        server.enqueue(json(200, """{"answer":"Это коворкинг.","phrases":["Это коворкинг."],"lang":"ru"}"""))

        val result = api.look(byteArrayOf(1, 2, 3), "Что это?", ctx)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/v1/look", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"image\"; filename=\"frame.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body.contains("\"question\":\"Что это?\""))
    }

    @Test
    fun `quick questions and places`() = runBlocking {
        server.enqueue(json(200, """{"items":[{"id":"wifi","text":"What is the Wi-Fi password?"}]}"""))
        server.enqueue(
            json(
                200,
                """{"places":[{"id":"cafe","map_name":"Кафе","names":{"ru":"Кафе","en":"Cafe"},
                   "synonyms":["кафешка"],"description":{"ru":"1 этаж"}}]}""",
            ),
        )

        val quick = (api.quickQuestions(Lang.EN) as ApiResult.Success).value
        assertEquals(listOf(QuickQuestion("wifi", "What is the Wi-Fi password?")), quick)
        assertEquals("/v1/quick-questions?lang=en", server.takeRequest().path)

        val places = (api.places() as ApiResult.Success).value.places!!
        assertEquals("Кафе", places.single().mapName)
        assertEquals(listOf("кафешка"), places.single().synonyms)
        assertEquals("/v1/places", server.takeRequest().path)
    }

    @Test
    fun `places sends If-None-Match and understands 304`() = runBlocking {
        server.enqueue(json(200, """{"places":[]}""").setHeader("ETag", "\"v2\""))
        val first = (api.places() as ApiResult.Success).value
        assertEquals("\"v2\"", first.etag)
        assertFalse(first.notModified)
        assertEquals(null, server.takeRequest().getHeader("If-None-Match"))

        server.enqueue(MockResponse().setResponseCode(304))
        val second = (api.places(first.etag) as ApiResult.Success).value
        assertTrue(second.notModified)
        assertEquals(null, second.places)
        assertEquals("\"v2\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `stt sends wav and meta, parses result`() = runBlocking {
        server.enqueue(json(200, """{"request_id":"req_s","text":" Асхана қай жерде? ","lang":"kk","confidence":0.92}"""))
        val wav = kz.astanahub.robot.audio.WavEncoder.encode(ShortArray(160))

        val r = (api.stt(wav, null, "s-1") as ApiResult.Success).value

        assertEquals("Асхана қай жерде?", r.text)
        assertEquals(Lang.KK, r.lang)
        assertEquals(0.92, r.confidence!!, 1e-9)
        val request = server.takeRequest()
        assertEquals("/v1/stt", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"audio\"; filename=\"speech.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("RIFF"))
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body, body.contains("\"session_id\":\"s-1\""))
        assertFalse(body, body.contains("\"lang\""))
    }

    @Test
    fun `stt silence gives empty text`() = runBlocking {
        server.enqueue(json(200, """{"text":""}"""))
        val r = (api.stt(ByteArray(44), Lang.RU, "s") as ApiResult.Success).value
        assertEquals("", r.text)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"lang\":\"ru\""))
    }

    @Test
    fun `tts returns mp3 bytes`() = runBlocking {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2, 3)
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg")
                .setHeader("X-Request-Id", "req_t").setBody(Buffer().write(mp3)),
        )

        val bytes = (api.tts("Асхана бірінші қабатта.", Lang.KK) as ApiResult.Success).value

        assertTrue(mp3.contentEquals(bytes))
        val request = server.takeRequest()
        assertEquals("/v1/tts", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"text\":\"Асхана бірінші қабатта.\""))
        assertTrue(body, body.contains("\"lang\":\"kk\""))
    }

    @Test
    fun `fake speech provider header is treated as unavailable`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg")
                .setHeader("X-Speech-Provider", "fake").setBody(Buffer().write(ByteArray(64))),
        )
        val error = (api.tts("Привет.", Lang.RU) as ApiResult.Failure).error
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, error.kind)
        assertEquals(200, error.httpStatus)
    }

    @Test
    fun `real speech provider header passes`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("X-Speech-Provider", "yandex").setBody(Buffer().write(byteArrayOf(1, 2))),
        )
        assertTrue(api.tts("x", Lang.RU) is ApiResult.Success)
    }

    @Test
    fun `speech provider down maps to SPEECH_UNAVAILABLE`() = runBlocking {
        server.enqueue(json(503, """{"error":{"code":"speech_unavailable","message":"provider down"}}"""))
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, (api.tts("x", Lang.RU) as ApiResult.Failure).error.kind)
        server.enqueue(json(503, """{"error":{"code":"speech_unavailable","message":"provider down"}}"""))
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, (api.stt(ByteArray(44), null, "s") as ApiResult.Failure).error.kind)
    }

    @Test
    fun `base url is normalized`() {
        assertEquals("http://10.0.2.2:8000/v1/", RetrofitBackendApi.normalizeBaseUrl("http://10.0.2.2:8000/v1"))
        assertEquals("http://h/v1/", RetrofitBackendApi.normalizeBaseUrl(" http://h/v1/ "))
    }
}
