package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Экраны главного окна: приветствие → ответ / частые вопросы, «Назад», «В начало». */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelNavigationTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<List<String>>()
        var stops = 0
        override val transcripts: Flow<Transcript> = MutableSharedFlow()
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
            delay(10)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() { stops++ }
        override fun setLanguage(lang: Lang) = Unit
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var voice: TestVoice
    private val questions = (1..7).map { QuickQuestion("q$it", "Вопрос $it") }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk()
        voice = TestVoice()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(questions)
        coEvery { backend.ask(any(), any()) } answers {
            ApiResult.Success(Answer("req", "Ответ на «${firstArg<String>()}»", listOf("Ответ."), Lang.RU, true))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(): MainViewModel =
        MainViewModel(backend, voice, mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime })
            .also { advanceUntilIdle() }

    @Test
    fun `starts on home without back button`() = runTest(dispatcher) {
        val s = createVm().state.value
        assertEquals(Screen.HOME, s.screen)
        assertFalse(s.canGoBack)
    }

    @Test
    fun `question goes to answer screen, back returns home`() = runTest(dispatcher) {
        val vm = createVm()
        vm.submit("Где кафе?")
        advanceUntilIdle()
        assertEquals(Screen.ANSWER, vm.state.value.screen)
        assertEquals("Ответ на «Где кафе?»", vm.state.value.answer)
        assertTrue(vm.state.value.canGoBack)

        vm.back()
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertNull(vm.state.value.answer)
    }

    @Test
    fun `faq pages by three, back returns to previous page`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openFaq()
        var s = vm.state.value
        assertEquals(Screen.FAQ, s.screen)
        assertEquals(3, s.faqPageCount)
        assertEquals(questions.take(3), s.faqPageItems)

        vm.faqNextPage()
        s = vm.state.value
        assertEquals(1, s.faqPage)
        assertEquals(questions.subList(3, 6), s.faqPageItems)

        vm.back()
        assertEquals(0, vm.state.value.faqPage)
        assertEquals(Screen.FAQ, vm.state.value.screen)
        vm.back()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun `last faq page has the remainder and next wraps to the first`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openFaq()
        vm.faqNextPage()
        vm.faqNextPage()
        assertEquals(listOf(questions[6]), vm.state.value.faqPageItems)
        vm.faqNextPage()
        assertEquals(0, vm.state.value.faqPage)
    }

    @Test
    fun `faq row opens inline with the answer and closes on second tap`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openFaq()
        vm.faqToggle(questions[1])
        advanceUntilIdle()
        var s = vm.state.value
        assertEquals(Screen.FAQ, s.screen)
        assertEquals("q2", s.faqOpenId)
        assertEquals("Вопрос 2", s.question)
        assertEquals("Ответ на «Вопрос 2»", s.answer)

        vm.faqToggle(questions[1])
        s = vm.state.value
        assertNull(s.faqOpenId)
        assertNull(s.answer)
        assertEquals(Screen.FAQ, s.screen)
    }

    @Test
    fun `voice question from faq goes to answer, back returns to the same faq page`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openFaq()
        vm.faqNextPage()
        vm.submit("Где туалет?")
        advanceUntilIdle()
        assertEquals(Screen.ANSWER, vm.state.value.screen)
        vm.back()
        assertEquals(Screen.FAQ, vm.state.value.screen)
        assertEquals(1, vm.state.value.faqPage)
    }

    @Test
    fun `back from second answer shows the first answer without speaking it again`() = runTest(dispatcher) {
        val vm = createVm()
        vm.submit("Первый")
        advanceUntilIdle()
        vm.submit("Второй")
        advanceUntilIdle()
        val spokenBefore = voice.spoken.size

        vm.back()
        advanceUntilIdle()
        assertEquals(Screen.ANSWER, vm.state.value.screen)
        assertEquals("Первый", vm.state.value.question)
        assertEquals("Ответ на «Первый»", vm.state.value.answer)
        assertEquals(spokenBefore, voice.spoken.size)
    }

    @Test
    fun `home clears history and stops speech`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openFaq()
        vm.submit("Вопрос")
        advanceUntilIdle()
        val stops = voice.stops
        vm.goHome()
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertTrue(voice.stops > stops)
        vm.back() // истории нет — остаёмся на приветствии
        assertEquals(Screen.HOME, vm.state.value.screen)
    }
}
