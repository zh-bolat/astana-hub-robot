package kz.astanahub.robot.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кадры `WS /v1/live` по docs/api-contract.md. */
class LiveProtocolTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `hello carries lang, place and the real aec flag`() {
        val hello = obj(LiveFrames.hello(Lang.KK, "reception", aec = true))
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals("kk", hello["lang"]!!.jsonPrimitive.content)
        assertEquals("reception", hello["place_id"]!!.jsonPrimitive.content)
        assertEquals(true, hello["aec"]!!.jsonPrimitive.boolean)

        val noPlace = obj(LiveFrames.hello(Lang.RU, null, aec = false))
        assertEquals(JsonNull, noPlace["place_id"])
        assertEquals(false, noPlace["aec"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `client control frames`() {
        assertEquals("visitor_arrived", obj(LiveFrames.visitorArrived())["type"]!!.jsonPrimitive.content)
        assertEquals("interrupt", obj(LiveFrames.interrupt())["type"]!!.jsonPrimitive.content)
        val done = obj(LiveFrames.playbackDone("trn_1"))
        assertEquals("playback_done", done["type"]!!.jsonPrimitive.content)
        assertEquals("trn_1", done["turn_id"]!!.jsonPrimitive.content)
        val state = obj(LiveFrames.state("cafe", navigating = true))
        assertEquals("state", state["type"]!!.jsonPrimitive.content)
        assertEquals("cafe", state["place_id"]!!.jsonPrimitive.content)
        assertEquals(true, state["navigating"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `audio format from ready`() {
        assertEquals(24_000, LiveAudioFormat.parse("pcm_s16le_24000")?.sampleRate)
        assertEquals(16_000, LiveAudioFormat.parse("pcm_s16le_16000")?.sampleRate)
        assertNull(LiveAudioFormat.parse("mp3_44100_128"))
        assertNull(LiveAudioFormat.parse("pcm_s16le_1"))
        assertNull(LiveAudioFormat.parse(null))
        assertEquals(LiveEvent.Ready(LiveAudioFormat(22_050)), LiveEvent.parse("""{"type":"ready","audio_format":"pcm_s16le_22050"}"""))
    }

    @Test
    fun `server events are parsed`() {
        assertEquals(LiveEvent.Listening, LiveEvent.parse("""{"type":"listening"}"""))
        assertEquals(LiveEvent.UserSpeaking, LiveEvent.parse("""{"type":"user_speaking"}"""))
        assertEquals(LiveEvent.UserPartial("Где"), LiveEvent.parse("""{"type":"user_partial","text":" Где "}"""))
        assertEquals(LiveEvent.UserFinal("Где кафе?", Lang.RU), LiveEvent.parse("""{"type":"user_final","text":"Где кафе?","lang":"ru"}"""))
        assertEquals(LiveEvent.Ignored, LiveEvent.parse("""{"type":"ignored"}"""))
        assertEquals(LiveEvent.Thinking, LiveEvent.parse("""{"type":"thinking"}"""))
        assertEquals(LiveEvent.TurnStart("trn_1"), LiveEvent.parse("""{"type":"turn_start","turn_id":"trn_1"}"""))
        assertEquals(LiveEvent.Emotion("happy"), LiveEvent.parse("""{"type":"emotion","name":"happy"}"""))
        assertEquals(LiveEvent.Phrase("Привет!"), LiveEvent.parse("""{"type":"phrase","text":"Привет!"}"""))
        assertEquals(LiveEvent.Action("guide_to", "cafe"), LiveEvent.parse("""{"type":"action","type_":"guide_to","place_id":"cafe"}"""))
        assertEquals(LiveEvent.TurnEnd("trn_1", Lang.EN, true), LiveEvent.parse("""{"type":"turn_end","turn_id":"trn_1","lang":"en","found":true}"""))
        assertEquals(LiveEvent.Interrupted("trn_1"), LiveEvent.parse("""{"type":"interrupted","turn_id":"trn_1"}"""))
        val error = LiveEvent.parse("""{"type":"error","error":{"code":"llm_unavailable","message":"x"}}""") as LiveEvent.Error
        assertEquals(ErrorKind.LLM_UNAVAILABLE, error.error.kind)
        assertNull(LiveEvent.parse("""{"type":"something_new"}"""))
        assertNull(LiveEvent.parse("not json"))
    }

    @Test
    fun `close codes map to errors`() {
        assertNull(LiveEvent.closeError(1000, ""))
        assertEquals(ErrorKind.UNAUTHORIZED, LiveEvent.closeError(1008, "unauthorized")?.kind)
        assertEquals(ErrorKind.RATE_LIMITED, LiveEvent.closeError(1013, "")?.kind)
        assertEquals(ErrorKind.UNKNOWN, LiveEvent.closeError(1011, "boom")?.kind)
    }

    @Test
    fun `live url is built from the api base url`() {
        val url = OkHttpLiveConnection.liveUrl("http://10.0.0.5:8010/v1", Lang.RU, "s1")
        assertTrue(url, url.startsWith("http://10.0.0.5:8010/v1/live?"))
        assertTrue(url, url.contains("session_id=s1") && url.contains("lang=ru"))
    }

    @Test
    fun `mic frame is 20-40 ms of 16 kHz PCM16`() {
        val cfg = LiveConfig()
        assertTrue(cfg.frameMillis in 20..40)
        assertEquals(cfg.frameMillis * 32, cfg.frameBytes)
    }
}
