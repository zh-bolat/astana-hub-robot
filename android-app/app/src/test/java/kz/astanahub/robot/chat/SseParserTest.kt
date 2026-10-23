package kz.astanahub.robot.chat

import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            event: turn
            data: {"turn_id": "trn_1", "request_id": "req_1"}

            event: emotion
            data: {"name": "happy"}

            event: phrase
            data: {"text": "Есть коворкинг на втором этаже."}

            event: action
            data: {"name": "where_is", "args": {"place_id": "coworking_2f", "map_name": "coworking"}}

            event: action
            data: {"name": "look"}

            event: done
            data: {"request_id": "req_1", "lang": "ru", "tools": ["find_place"]}

        """.trimIndent()
        assertEquals(
            listOf(
                ChatEvent.Turn("trn_1", "req_1"),
                ChatEvent.Emotion("happy"),
                ChatEvent.Phrase("Есть коворкинг на втором этаже."),
                ChatEvent.Action("where_is", mapOf("place_id" to "coworking_2f", "map_name" to "coworking")),
                ChatEvent.Action("look", emptyMap()),
                ChatEvent.Done("req_1", Lang.RU, listOf("find_place")),
            ),
            events(stream + "\n"),
        )
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
    fun `action args keep only primitive values`() {
        val a = events("event: action\ndata: {\"name\": \"guide_to\", \"args\": {\"place_id\": \"cafe\", \"map_name\": null, \"x\": {\"y\": 1}}}\n\n")
            .single() as ChatEvent.Action
        assertEquals(mapOf("place_id" to "cafe"), a.args)
        assertEquals("cafe", a.placeId)
        assertNull(a.mapName)
    }
}
