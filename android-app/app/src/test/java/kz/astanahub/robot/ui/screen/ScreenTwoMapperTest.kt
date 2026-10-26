package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.Screen
import kz.astanahub.robot.ui.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Состояние MainViewModel → экран 2: «Стоп», «Проводить», субтитры, частые вопросы — правила прежнего экрана. */
class ScreenTwoMapperTest {

    private val cafe = Place("cafe", "Кафе", mapOf("ru" to "Кафе"), emptyList(), emptyMap())
    private val questions = (1..7).map { QuickQuestion("q$it", "Вопрос $it") }

    @Test
    fun `screen 2 is open in menu mode, but never during a trip`() {
        assertTrue(ScreenTwoMapper.menuOpen(MainUiState(mode = ScreenMode.MENU)))
        assertFalse(ScreenTwoMapper.menuOpen(MainUiState(mode = ScreenMode.MENU, guidingTo = "Кафе")))
        assertFalse(ScreenTwoMapper.menuOpen(MainUiState(mode = ScreenMode.FACE)))
        assertFalse(ScreenTwoMapper.menuOpen(MainUiState(mode = ScreenMode.SLEEP)))
    }

    @Test
    fun `busy while thinking, looking or speaking - Stop instead of Ask by voice`() {
        assertTrue(ScreenTwoMapper.busy(MainUiState(phase = Phase.THINKING)))
        assertTrue(ScreenTwoMapper.busy(MainUiState(phase = Phase.LOOKING)))
        assertTrue(ScreenTwoMapper.busy(MainUiState(phase = Phase.SPEAKING)))
        assertFalse(ScreenTwoMapper.busy(MainUiState(phase = Phase.IDLE)))
        assertFalse(ScreenTwoMapper.busy(MainUiState(phase = Phase.LISTENING)))
    }

    @Test
    fun `live subtitles go to the last robot line while speaking`() {
        val chat = listOf(
            ChatItem(1, ChatRole.VISITOR, "Q"),
            ChatItem(2, ChatRole.ROBOT, "A"),
            ChatItem(3, ChatRole.VISITOR, "Q2"),
            ChatItem(4, ChatRole.ROBOT, "A2"),
        )
        assertEquals(4L, ScreenTwoMapper.speakingId(MainUiState(phase = Phase.SPEAKING), chat))
        assertNull(ScreenTwoMapper.speakingId(MainUiState(phase = Phase.IDLE), chat))
        assertNull(ScreenTwoMapper.speakingId(MainUiState(phase = Phase.SPEAKING), emptyList()))
    }

    @Test
    fun `guide offer only when idle, not travelling and not in the FAQ`() {
        val offered = MainUiState(guideTarget = cafe, screen = Screen.ANSWER)
        assertEquals(cafe, ScreenTwoMapper.guideOffer(offered))
        assertNull(ScreenTwoMapper.guideOffer(offered.copy(phase = Phase.SPEAKING)))
        assertNull(ScreenTwoMapper.guideOffer(offered.copy(guidingTo = "Кафе")))
        assertNull(ScreenTwoMapper.guideOffer(offered.copy(screen = Screen.FAQ)))
        assertNull(ScreenTwoMapper.guideOffer(MainUiState()))
    }

    @Test
    fun `hold on the face opens admin only when allowed and not during a trip`() {
        // Ревью M2-r2, M7: PIN-диалог поверх едущего робота не нужен.
        assertTrue(ScreenTwoMapper.faceHoldEnabled(MainUiState(), diagnostics = true))
        assertFalse(ScreenTwoMapper.faceHoldEnabled(MainUiState(guidingTo = "Кафе"), diagnostics = true))
        assertFalse(ScreenTwoMapper.faceHoldEnabled(MainUiState(), diagnostics = false))
    }

    @Test
    fun `partial text of the visitor, blank is nothing`() {
        assertEquals("Где ка", ScreenTwoMapper.partial(MainUiState(partialQuestion = "Где ка")))
        assertNull(ScreenTwoMapper.partial(MainUiState(partialQuestion = "  ")))
    }

    @Test
    fun `faq - only on its screen, answer only for the open question, pages`() {
        val status: (Phase) -> String = { it.name }
        val more: (Int, Int) -> String = { p, c -> "Ещё ($p/$c)" }
        assertNull(ScreenTwoMapper.faq(MainUiState(screen = Screen.HOME, quickQuestions = questions), status, more))

        val open = MainUiState(screen = Screen.FAQ, quickQuestions = questions, faqOpenId = "q2", phase = Phase.THINKING)
        val pending = ScreenTwoMapper.faq(open, status, more)!!
        assertEquals(listOf("q1", "q2", "q3"), pending.items.map { it.id })
        assertEquals("q2", pending.openId)
        assertNull(pending.answer)
        assertEquals("THINKING", pending.pending)
        assertEquals("Ещё (1/3)", pending.more)

        val answered = ScreenTwoMapper.faq(
            open.copy(phase = Phase.IDLE, question = "Вопрос 2", answer = "Ответ 2", guideTarget = cafe), status, more,
        )!!
        assertEquals("Ответ 2", answered.answer)
        assertTrue(answered.guide)
        // Ответ на другой вопрос в раскрытый не попадает.
        assertNull(ScreenTwoMapper.faq(open.copy(question = "Другое", answer = "x"), status, more)!!.answer)
        // Одна страница — без «Ещё».
        assertNull(ScreenTwoMapper.faq(MainUiState(screen = Screen.FAQ, quickQuestions = questions.take(3)), status, more)!!.more)
    }
}
