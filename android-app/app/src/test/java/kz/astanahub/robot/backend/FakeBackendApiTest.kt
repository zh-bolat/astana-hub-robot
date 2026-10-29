package kz.astanahub.robot.backend

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kz.astanahub.robot.chat.ChatAction
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Агент-заглушка эмулятора ведёт себя как настоящий в сценариях экрана: «где» → «да» → поездка, источники. */
class FakeBackendApiTest {

    private val api = FakeBackendApi(latencyMillis = 0)
    private fun ask(q: String) = runBlocking { api.chat(ChatRequest("s", q, Lang.RU)).toList() }
    private fun actions(events: List<ChatEvent>) = events.filterIsInstance<ChatEvent.Action>().map { it.action }

    @Test
    fun `yes after where_is drives to that place, yes without an offer is just an answer`() {
        assertTrue(actions(ask("Да")).isEmpty())
        assertEquals(listOf(ChatAction(ChatAction.WHERE_IS, "cafe")), actions(ask("Где кафе?")))
        assertEquals(listOf(ChatAction(ChatAction.GUIDE_TO, "cafe")), actions(ask("Да, давайте")))
        // Предложение одно — второе «да» уже не поездка.
        assertTrue(actions(ask("Да")).isEmpty())
    }

    @Test
    fun `hub answers carry sources, others do not`() {
        val hub = ask("Что такое Astana Hub?").filterIsInstance<ChatEvent.Done>().single()
        assertTrue(hub.citations.isNotEmpty())
        val other = ask("Как дела?").filterIsInstance<ChatEvent.Done>().single()
        assertTrue(other.citations.isEmpty())
    }
}
