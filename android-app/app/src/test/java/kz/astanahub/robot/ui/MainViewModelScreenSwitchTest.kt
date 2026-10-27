package kz.astanahub.robot.ui

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatAction
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
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

/**
 * MF5 (решение пользователя): лицо ↔ страница приложения меняются только свайпом. Пока открыта страница, ни таймер, ни
 * ответ (плитка, голос, «Что это?»), ни «В начало» / «Назад», ни сон, ни пробуждение, ни поездка экран не переключают.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelScreenSwitchTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
            try {
                delay(3_000)
            } finally {
                activity.value = VoiceActivity.IDLE
            }
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
    private val voice = TestVoice()
    private val chatRequests = mutableListOf<ChatRequest>()
    private var chatMode = ChatMode.AGENT
    private var guide = false
    private lateinit var robot: RobotGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q", "Q")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
        every { backend.chat(any()) } answers {
            chatRequests += firstArg<ChatRequest>()
            flow {
                if (guide) emit(ChatEvent.Action(ChatAction(ChatAction.GUIDE_TO, "cafe"))) else emit(ChatEvent.Phrase("Ответ."))
                emit(ChatEvent.Done("r", Lang.RU))
            }
        }
        robot = mockk(relaxed = true) {
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
            coEvery { navigateTo(any(), any()) } coAnswers { delay(10_000); NavigationResult.Arrived }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(startAsleep: Boolean = false): MainViewModel {
        val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
        val wf = WayFinder(PlaceRepository(backend, robot, file, dispatcher), robot)
        val vm = MainViewModel(
            backend, voice, mockk<CameraGateway>(relaxed = true), phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, chatMode = { chatMode },
            screensaverMillis = SLEEP_MS, startAsleep = startAsleep,
        )
        runCurrent()
        return vm
    }

    private fun MainViewModel.assertPage(why: String) = assertEquals(why, ScreenMode.MENU, state.value.mode)

    @Test
    fun `page open - two minutes idle, a tile, a voice question, what is this, home and back keep the page`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openMenu()
        vm.assertPage("swipe")
        advanceTimeBy(120_000); runCurrent()
        vm.assertPage("2 min without actions - no timer back to the face")

        vm.submitButton("Что такое Astana Hub?")
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.SPEAKING, vm.state.value.phase)
        vm.assertPage("tile answer is spoken - stays on the page")
        advanceTimeBy(4_000); runCurrent()
        vm.assertPage("tile answer done")

        voice.asr.emit(Transcript("Где здесь коворкинг?", isFinal = true))
        advanceTimeBy(500); runCurrent()
        assertEquals(2, chatRequests.size)
        vm.assertPage("voice answer is spoken")
        advanceTimeBy(4_000); runCurrent()

        vm.lookAround()
        advanceTimeBy(500); runCurrent()
        vm.assertPage("what is this")
        advanceTimeBy(4_000); runCurrent()

        vm.goHome()
        vm.back()
        runCurrent()
        vm.assertPage("home and back")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `page open - falls asleep on the page, a tile wakes on the page, swipes still switch`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openMenu()
        advanceTimeBy(SLEEP_MS + 1_000); runCurrent()
        assertTrue(vm.state.value.asleep)
        vm.assertPage("sleep does not switch the screen")
        assertEquals("page reset to the greeting", Screen.HOME, vm.state.value.screen)
        assertNull(vm.state.value.answer)

        vm.submitButton("Мероприятия?")
        advanceTimeBy(500); runCurrent()
        assertFalse(vm.state.value.asleep)
        vm.assertPage("waking by a tile does not switch the screen")

        vm.showFace()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.openMenu()
        vm.assertPage("swipe back")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `trip from the page - the page stays under the trip layer and after the trip`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openMenu()
        guide = true
        vm.submitButton("Проводи в кафе")
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertEquals("Кафе", vm.state.value.guidingTo)
        vm.assertPage("trip")
        advanceTimeBy(10_000); runCurrent()
        assertNull(vm.state.value.guidingTo)
        vm.assertPage("arrived")
        advanceTimeBy(8_000); runCurrent()
        vm.assertPage("after the arrival phrase")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `face - tap wakes on the face, an answer and a double tap keep the face`() = runTest(dispatcher) {
        val vm = createVm(startAsleep = true)
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        vm.wakeByTap()
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        advanceTimeBy(4_000); runCurrent()
        vm.submit("Что такое Astana Hub?")
        advanceTimeBy(500); runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.sleepByTap()
        runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        // Свайп во сне: страница, робот спит; свайп назад — спящее лицо.
        vm.openMenu()
        vm.assertPage("swipe in sleep")
        assertTrue(vm.state.value.asleep)
        vm.showFace()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `legacy - tap wakes on the face, not on the menu`() = runTest(dispatcher) {
        chatMode = ChatMode.LEGACY
        val vm = createVm(startAsleep = true)
        vm.wakeByTap()
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `chat mode changed in diagnostics - the screen stays`() = runTest(dispatcher) {
        val vm = createVm()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        chatMode = ChatMode.LEGACY
        vm.onSettingsChanged()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.openMenu()
        chatMode = ChatMode.AGENT
        vm.onSettingsChanged()
        vm.assertPage("diagnostics")
        vm.viewModelScope.cancel()
    }

    private companion object {
        const val SLEEP_MS = 300_000L
    }
}
