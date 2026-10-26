package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.ui.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Маппинг состояния MainViewModel (текущий ход) в чат экрана 2. */
class ChatLogTest {

    private val log = ChatLog()
    private var s = MainUiState()

    private fun step(change: MainUiState.() -> MainUiState): Boolean {
        s = s.change()
        return log.update(s)
    }

    private fun texts() = log.items.map { (if (it.role == ChatRole.VISITOR) "V:" else "R:") + it.text }

    @Test
    fun `question and growing answer - one visitor and one robot line`() {
        step { copy(question = "Где кафе?") }
        step { copy(answer = "Кафе на втором этаже.") }
        step { copy(answer = "Кафе на втором этаже. Проводить?") }
        assertEquals(listOf("V:Где кафе?", "R:Кафе на втором этаже. Проводить?"), texts())
    }

    @Test
    fun `ids are stable while the answer grows - list keys do not jump`() {
        step { copy(question = "Q") }
        step { copy(answer = "A.") }
        val id = log.items[1].id
        step { copy(answer = "A. B.") }
        assertEquals(id, log.items[1].id)
    }

    @Test
    fun `same question again is a new turn`() {
        step { copy(question = "Q", answer = "A") }
        step { copy(answer = null) } // тот же вопрос заново: ответ сброшен
        step { copy(answer = "A2") }
        assertEquals(listOf("V:Q", "R:A", "V:Q", "R:A2"), texts())
    }

    @Test
    fun `answer that does not continue the previous one is a new robot line`() {
        step { copy(question = "Где кафе?", answer = "Кафе справа, 15 м.") }
        step { copy(answer = "Иду к: Кафе") } // «Проводить» — старт поездки
        assertEquals(listOf("V:Где кафе?", "R:Кафе справа, 15 м.", "R:Иду к: Кафе"), texts())
    }

    @Test
    fun `greeting without a question is a robot line`() {
        step { copy(answer = "Здравствуйте! Чем помочь?") }
        assertEquals(listOf("R:Здравствуйте! Чем помочь?"), texts())
    }

    @Test
    fun `noise retracted by the server - the visitor line goes, the previous turn stays without duplicates`() {
        step { copy(question = "Q1", answer = "A1") }
        step { copy(question = "ну", answer = null) }
        assertEquals(listOf("V:Q1", "R:A1", "V:ну"), texts())
        // MainViewModel: question/answer → null, затем возврат прежнего хода — коллектор может увидеть оба шага…
        step { copy(question = null, answer = null) }
        step { copy(question = "Q1", answer = "A1") }
        assertEquals(listOf("V:Q1", "R:A1"), texts())
    }

    @Test
    fun `noise retracted - conflated in one update`() {
        step { copy(question = "Q1", answer = "A1") }
        step { copy(question = "ну", answer = null) }
        // …или сразу итог (StateFlow склеил два обновления).
        step { copy(question = "Q1", answer = "A1") }
        assertEquals(listOf("V:Q1", "R:A1"), texts())
    }

    @Test
    fun `back from the FAQ returns an earlier turn - no duplicates`() {
        step { copy(question = "Где кафе?", answer = "Справа.") }
        step { copy(question = "Что такое Hub?", answer = null) } // раскрыли вопрос в частых
        step { copy(answer = "Технопарк.") }
        step { copy(question = "Где кафе?", answer = "Справа.") } // «Назад» вернул прежний ход
        assertEquals(listOf("V:Где кафе?", "R:Справа.", "V:Что такое Hub?", "R:Технопарк."), texts())
        // Тот же вопрос по-настоящему заново — новая пара.
        step { copy(answer = null) }
        step { copy(answer = "Справа.") }
        assertEquals(listOf("V:Где кафе?", "R:Справа.", "V:Что такое Hub?", "R:Технопарк.", "V:Где кафе?", "R:Справа."), texts())
    }

    @Test
    fun `home closes the turn but keeps the chat, next greeting does not overwrite the old answer`() {
        step { copy(question = "Q", answer = "A") }
        step { copy(question = null, answer = null) }
        assertEquals(listOf("V:Q", "R:A"), texts())
        step { copy(answer = "Здравствуйте!") }
        assertEquals(listOf("V:Q", "R:A", "R:Здравствуйте!"), texts())
    }

    @Test
    fun `citations attach to the robot line of the current turn and survive the next turn`() {
        val c = listOf(Citation("document:hub_space#2", "document", "Hub Space", "https://astanahub.com"))
        step { copy(question = "Что такое Hub Space?", answer = "Это коворкинг.") }
        step { copy(citations = c) }
        assertEquals(c, log.items[1].citations)
        step { copy(question = "Спасибо", answer = null, citations = emptyList()) }
        step { copy(answer = "Пожалуйста!") }
        assertEquals(c, log.items[1].citations)
        assertTrue(log.items[3].citations.isEmpty())
    }

    @Test
    fun `new conversation - clean chat`() {
        step { copy(question = "Q", answer = "A") }
        assertTrue(step { copy(conversation = conversation + 1, question = null, answer = null) })
        assertTrue(log.items.isEmpty())
    }

    @Test
    fun `new conversation snapshot that still carries the old turn - the old turn stays out`() {
        // goIdle: session_id меняется раньше сброса вопроса/ответа — коллектор может увидеть промежуточный снимок.
        step { copy(question = "Q30", answer = "A30") }
        step { copy(conversation = conversation + 1) }
        assertTrue(log.items.isEmpty())
        step { copy(question = null, answer = null) }
        assertTrue(log.items.isEmpty())
        // Новый посетитель спрашивает — в чате только его разговор.
        step { copy(question = "Где кафе?") }
        step { copy(answer = "Справа.") }
        assertEquals(listOf("V:Где кафе?", "R:Справа."), texts())
    }

    @Test
    fun `new conversation with the new question in the same snapshot - the question is shown`() {
        step { copy(question = "Q", answer = "A") }
        // touchSession: новый session_id и новый вопрос склеились в один снимок.
        step { copy(conversation = conversation + 1, question = "Новый вопрос", answer = null) }
        assertEquals(listOf("V:Новый вопрос"), texts())
        // Тот же вопрос, что и в прошлом разговоре, — тоже новый (ответа ещё нет).
        step { copy(conversation = conversation + 1, question = "Новый вопрос", answer = null) }
        assertEquals(listOf("V:Новый вопрос"), texts())
    }

    @Test
    fun `no change - same snapshot object, no update reported`() {
        step { copy(question = "Q", answer = "A") }
        val before = log.items
        assertFalse(step { copy(phase = kz.astanahub.robot.ui.Phase.SPEAKING, subtitle = "A") })
        assertTrue(before === log.items)
    }

    @Test
    fun `history is capped - oldest lines go first`() {
        val small = ChatLog(maxItems = 4)
        var st = MainUiState()
        repeat(5) { i ->
            st = st.copy(question = "Q$i", answer = null)
            small.update(st)
            st = st.copy(answer = "A$i")
            small.update(st)
        }
        assertEquals(listOf("Q3", "A3", "Q4", "A4"), small.items.map { it.text })
    }
}
