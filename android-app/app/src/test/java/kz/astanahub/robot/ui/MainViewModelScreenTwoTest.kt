package kz.astanahub.robot.ui

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.ui.screen.ChatLog
import kz.astanahub.robot.ui.screen.ChatRole
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M3, экран 2: источники ответа (`citations`) в состоянии и чат из настоящих ходов ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelScreenTwoTest {

    private class TestVoice : VoiceGateway {
        override val transcripts: Flow<Transcript> = MutableSharedFlow()
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            delay(200)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() {
            activity.value = VoiceActivity.IDLE
        }

        override fun setLanguage(lang: Lang) = Unit
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private val cited = listOf(Citation("document:hub_space#2", "document", "Hub Space — как стать участником", "https://astanahub.com"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q", "Q")))
        every { backend.chat(any()) } answers {
            val q = firstArg<kz.astanahub.robot.chat.ChatRequest>().question
            flow {
                emit(ChatEvent.Phrase("Ответ на «$q»."))
                emit(ChatEvent.Done("r", Lang.RU, citations = if (q.contains("Hub")) cited else emptyList()))
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(): MainViewModel {
        val vm = MainViewModel(
            backend, TestVoice(), mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            chatMode = { ChatMode.AGENT }, screensaverMillis = 180_000L,
        )
        runCurrent()
        return vm
    }

    @Test
    fun `citations of the answer reach the state, a new question starts without them`() = runTest(dispatcher) {
        val vm = createVm()
        vm.submit("Что такое Hub Space?")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(cited, vm.state.value.citations)
        vm.submit("Где кафе?")
        runCurrent()
        assertTrue("new turn has no citations yet", vm.state.value.citations.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.state.value.citations.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `back from diagnostics - screen 2 stays open unless the chat mode really changed`() = runTest(dispatcher) {
        var mode = ChatMode.AGENT
        val vm = MainViewModel(
            backend, TestVoice(), mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            chatMode = { mode }, screensaverMillis = 180_000L,
        )
        runCurrent()
        vm.onSettingsChanged() // onResume при старте
        vm.openMenu()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        vm.onSettingsChanged() // вернулись из диагностики, ничего не меняли
        assertEquals("screen 2 stays", ScreenMode.MENU, vm.state.value.mode)
        mode = ChatMode.LEGACY
        vm.onSettingsChanged()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        mode = ChatMode.AGENT
        vm.onSettingsChanged() // legacy → agent: «бодрый» экран — лицо
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `chat log follows real turns, citations under the robot answer, new visitor - clean chat`() = runTest(dispatcher) {
        val vm = createVm()
        val log = ChatLog()
        val job = backgroundScope.launchCollect(vm, log)
        vm.submit("Что такое Hub Space?")
        advanceTimeBy(5_000)
        runCurrent()
        vm.submit("Спасибо")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(
            listOf(ChatRole.VISITOR, ChatRole.ROBOT, ChatRole.VISITOR, ChatRole.ROBOT),
            log.items.map { it.role },
        )
        assertEquals("Ответ на «Что такое Hub Space?».", log.items[1].text)
        assertEquals(cited, log.items[1].citations)
        assertTrue(log.items[3].citations.isEmpty())
        // Уснул (бездействие) — новый разговор: чат с чистого листа.
        val before = vm.state.value.conversation
        vm.sleepNow()
        runCurrent()
        assertTrue(vm.state.value.conversation != before)
        assertTrue(log.items.isEmpty())
        job.cancel()
        vm.viewModelScope.cancel()
    }

    private fun CoroutineScope.launchCollect(vm: MainViewModel, log: ChatLog) =
        launch(start = CoroutineStart.UNDISPATCHED) { vm.state.collect { log.update(it) } }
}
