package kz.astanahub.robot.backend

import kotlinx.coroutines.runBlocking
import kz.astanahub.robot.core.Lang
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwitchableBackendApiTest {

    private val a = MockWebServer()
    private val b = MockWebServer()

    @Before fun setUp() { a.start(); b.start() }
    @After fun tearDown() { a.shutdown(); b.shutdown() }

    private fun api(url: String) = SwitchableBackendApi(
        initialUrl = url,
        factory = { RetrofitBackendApi(it, token = "t") },
        fallback = { FakeBackendApi(latencyMillis = 0) },
    )

    private fun quick(text: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"items":[{"id":"1","text":"$text"}]}""")

    @Test
    fun `switching address applies to the next request without restart`() = runBlocking {
        a.enqueue(quick("from A"))
        b.enqueue(quick("from B"))
        val api = api(a.url("/v1").toString())

        assertEquals("from A", (api.quickQuestions(Lang.RU) as ApiResult.Success).value.single().text)
        api.switchTo(b.url("/v1").toString())
        assertEquals("from B", (api.quickQuestions(Lang.RU) as ApiResult.Success).value.single().text)

        assertEquals("/v1/quick-questions?lang=ru", a.takeRequest().path)
        assertEquals("Bearer t", b.takeRequest().getHeader("Authorization"))
        assertEquals(1, a.requestCount)
    }

    @Test
    fun `empty address uses fallback`() = runBlocking {
        val api = api("")
        assertEquals("", api.url.value)
        assertEquals("fake", (api.health() as ApiResult.Success).value)
    }

    @Test
    fun `health parses status and version`() = runBlocking {
        a.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"ok","version":"0.3.0"}"""))
        assertEquals("ok 0.3.0", (api(a.url("/v1").toString()).health() as ApiResult.Success).value)
        assertEquals("/v1/health", a.takeRequest().path)
    }

    @Test
    fun `user typed address is normalized`() {
        val n = { raw: String -> SwitchableBackendApi.normalizeUserUrl(raw, httpForPrivateIp = true) }
        assertEquals("http://10.54.180.166:8000/v1", n("10.54.180.166:8000"))
        assertEquals("http://10.54.180.166:8000/v1", n(" http://10.54.180.166:8000/ "))
        assertEquals("http://192.168.1.5:8000/v1", n("http://192.168.1.5:8000/v1/"))
        assertEquals("https://robot.example.kz/api/v1", n("https://robot.example.kz/api/v1"))
        assertEquals("https://robot.example.kz/v1", n("robot.example.kz"))
        assertEquals("", n(""))
        assertTrue(n("::::").isEmpty())
        // По умолчанию (release) схема https даже для приватного IP.
        assertEquals("https://10.54.180.166:8000/v1", SwitchableBackendApi.normalizeUserUrl("10.54.180.166:8000"))
    }
}
