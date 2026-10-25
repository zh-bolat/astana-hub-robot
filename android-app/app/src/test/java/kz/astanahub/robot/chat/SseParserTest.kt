package kz.astanahub.robot.chat

import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SseParserTest {

    private fun parse(text: String): List<SseMessage> {
        val parser = SseParser()
        return text.split("\n").mapNotNull(parser::feed)
    }

    private fun events(text: String): List<ChatEvent> = parse(text).mapNotNull(ChatEventDecoder::decode)

    @Test
    fun `full chat stream decodes every event type in order`() {
        val stream = """
            event: emotion
            data: {"name": "happy"}

            event: action
            data: {"type": "guide_to", "place_id": "cafe"}

            event: phrase
            data: {"text": "Провожаю вас в кафе, идите за мной."}

            event: done
            data: {"request_id": "req_1", "lang": "ru", "found": true, "sources": ["place:cafe"], "actions": [{"type": "guide_to", "place_id": "cafe"}]}

        """.trimIndent()
        assertEquals(
            listOf(
                ChatEvent.Emotion("happy"),
                ChatEvent.Action(ChatAction("guide_to", "cafe")),
                ChatEvent.Phrase("Провожаю вас в кафе, идите за мной."),
                ChatEvent.Done("req_1", Lang.RU, found = true, sources = listOf("place:cafe"), actions = listOf(ChatAction("guide_to", "cafe"))),
            ),
            events(stream + "\n"),
        )
    }

    @Test
    fun `done without optional fields has defaults, found false is kept`() {
        assertEquals(ChatEvent.Done(null, null), events("event: done\ndata: {}\n\n").single())
        val d = events("event: done\ndata: {\"lang\": \"kk\", \"found\": false, \"actions\": []}\n\n").single() as ChatEvent.Done
        assertEquals(Lang.KK, d.lang)
        assertEquals(false, d.found)
        assertTrue(d.actions.isEmpty())
    }

    @Test
    fun `old turn event is ignored`() {
        assertTrue(events("event: turn\ndata: {\"turn_id\": \"t\"}\n\n").isEmpty())
    }

    @Test
    fun `error event uses the common error format`() {
        val e = events("event: error\ndata: {\"error\": {\"code\": \"llm_unavailable\", \"message\": \"x\"}, \"request_id\": \"req_9\"}\n\n")
            .single() as ChatEvent.Error
        assertEquals(ErrorKind.LLM_UNAVAILABLE, e.error.kind)
        assertEquals("llm_unavailable", e.error.serverCode)
        assertEquals("req_9", e.error.requestId)
    }

    @Test
    fun `comments, CRLF and multi-line data are handled`() {
        val msgs = parse(": keep-alive\r\nevent: phrase\r\ndata: {\"text\":\r\ndata: \"Привет\"}\r\n\r\n")
        assertEquals(listOf(SseMessage("phrase", "{\"text\":\n\"Привет\"}")), msgs)
        assertEquals(ChatEvent.Phrase("Привет"), ChatEventDecoder.decode(msgs.single()))
    }

    @Test
    fun `unknown events, broken json and empty phrases are skipped`() {
        assertTrue(events("event: ping\ndata: {}\n\nevent: phrase\ndata: {oops\n\nevent: phrase\ndata: {\"text\": \"  \"}\n\n").isEmpty())
    }

    @Test
    fun `incomplete event at end of stream is dropped`() {
        val parser = SseParser()
        assertNull(parser.feed("event: phrase"))
        assertNull(parser.feed("data: {\"text\": \"обрыв\"}"))
        // поток закрылся без пустой строки — событие не отдаём
    }

    @Test
    fun `action without type is skipped, look has no place`() {
        assertTrue(events("event: action\ndata: {\"place_id\": \"cafe\"}\n\n").isEmpty())
        val a = events("event: action\ndata: {\"type\": \"look\", \"place_id\": null}\n\n").single() as ChatEvent.Action
        assertEquals(ChatAction.LOOK, a.type)
        assertNull(a.placeId)
    }

    @Test
    fun `done with ignored true - noise, no phrases`() {
        val d = events(
            "event: done\ndata: {\"request_id\": \"r\", \"lang\": \"ru\", \"found\": false, \"sources\": [], \"actions\": [], \"ignored\": true}\n\n",
        ).single() as ChatEvent.Done
        assertTrue(d.ignored)
        assertEquals(false, d.found)
        assertEquals(false, (events("event: done\ndata: {}\n\n").single() as ChatEvent.Done).ignored)
    }

    @Test
    fun `json mode body - ignored, answer and error`() {
        val ignored = ChatEventDecoder.decodeJsonBody(
            """{"request_id": "r", "lang": "ru", "found": false, "sources": [], "actions": [], "ignored": true, "answer": ""}""",
        )
        assertEquals(1, ignored.size)
        assertTrue((ignored.single() as ChatEvent.Done).ignored)

        val answer = ChatEventDecoder.decodeJsonBody(
            """{"request_id": "r", "lang": "kk", "answer": "Кафе бірінші қабатта.", "actions": [{"type": "where_is", "place_id": "cafe"}]}""",
        )
        assertEquals(ChatEvent.Phrase("Кафе бірінші қабатта."), answer[0])
        val done = answer.last() as ChatEvent.Done
        assertFalse(done.ignored)
        assertEquals("cafe", done.actions.single().placeId)

        val error = ChatEventDecoder.decodeJsonBody("""{"error": {"code": "llm_unavailable", "message": "x"}}""").single()
        assertTrue(error is ChatEvent.Error)
    }
}
