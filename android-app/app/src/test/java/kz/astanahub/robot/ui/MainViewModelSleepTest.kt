package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
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
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import kz.astanahub.robot.ui.face.FaceCue
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M2: сон через 3 мин бездействия, пробуждение голосом (VAD) / человеком, тап в поездке обрывает только речь. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSleepTest {

    private class TestVoice : VoiceGateway {
        var stops = 0
        override val transcripts: Flow<Transcript> = MutableSharedFlow()
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            delay(3_000)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() {
            stops++
        }

        override fun setLanguage(lang: Lang) = Unit
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private val voice = TestVoice()
    private val visitorVoice = MutableStateFlow(false)
    private val chatRequests = mutableListOf<ChatRequest>()
    private val cues = mutableListOf<FaceCue>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q", "Q")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
        every { backend.chat(any()) } answers {
            chatRequests += firstArg<ChatRequest>()
            flow {
                emit(ChatEvent.Phrase("Здравствуйте!"))
                emit(ChatEvent.Done("r", Lang.RU))
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(
        presence: Flow<PresenceEvent>? = null,
        robot: RobotGateway? = null,
    ): MainViewModel {
        val wf = robot?.let {
            val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
            WayFinder(PlaceRepository(backend, it, file, dispatcher), it)
        }
        val vm = MainViewModel(
            backend, voice, mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, presence = presence, chatMode = { ChatMode.AGENT },
            screensaverMillis = SLEEP_MS, visitorVoice = visitorVoice,
        )
        backgroundScope.launch { vm.faceCues.collect { cues += it } }
        runCurrent()
        return vm
    }

    @Test
    fun `falls asleep after 3 minutes of inactivity, not before`() = runTest(dispatcher) {
        val vm = createVm()
        advanceTimeBy(SLEEP_MS - 1_000)
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `visitor voice wakes the robot at once when there is no PersonApi`() = runTest(dispatcher) {
        val vm = createVm(presence = null)
        vm.sleepNow()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        visitorVoice.value = true
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `with PersonApi distant talk does not wake, voice of a visitor nearby does`() = runTest(dispatcher) {
        val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
        val vm = createVm(presence = presence)
        vm.sleepNow()
        visitorVoice.value = true
        runCurrent()
        assertEquals("talk far away — keep sleeping", ScreenMode.SLEEP, vm.state.value.mode)
        visitorVoice.value = false
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        runCurrent()
        vm.sleepNow()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        visitorVoice.value = true
        runCurrent()
        assertEquals("visitor nearby speaks — wake", ScreenMode.FACE, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a visitor walking up wakes the robot from sleep and is greeted`() = runTest(dispatcher) {
        val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
        val vm = createVm(presence = presence)
        advanceTimeBy(SLEEP_MS + 1_000)
        runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        assertEquals(listOf(FaceCue.GREET), cues)
        assertTrue("greeting through the agent", chatRequests.any { it.event == ChatRequest.EVENT_VISITOR_ARRIVED })
        // Человек стоит рядом — по бездействию не засыпаем.
        advanceTimeBy(SLEEP_MS * 2)
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        // Таймер бездействия с человеком рядом перезапускается бесконечно — останавливаем ViewModel.
        vm.viewModelScope.cancel()
    }

    @Test
    fun `tap in a trip while speaking cuts only the speech, the trip goes on`() = runTest(dispatcher) {
        val never = CompletableDeferred<NavigationResult>()
        val robot: RobotGateway = mockk(relaxed = true) {
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
            coEvery { navigateTo(any(), any()) } coAnswers { never.await() }
        }
        val vm = createVm(robot = robot)
        every { backend.chat(any()) } answers {
            flow {
                emit(ChatEvent.Action(kz.astanahub.robot.chat.ChatAction(kz.astanahub.robot.chat.ChatAction.GUIDE_TO, "cafe")))
                emit(ChatEvent.Done("r", Lang.RU))
            }
        }
        vm.submit("Проводи в кафе")
        advanceTimeBy(500)
        runCurrent()
        assertEquals("cafe trip started", Phase.NAVIGATING, vm.state.value.phase)
        val stopsBefore = voice.stops
        vm.interruptSpeech()
        runCurrent()
        assertTrue(voice.stops > stopsBefore)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        verify(exactly = 0) { robot.stopNavigation() }
        coVerify(exactly = 0) { robot.stopNavigationAndWait(any()) }
        assertTrue(vm.state.value.guidingTo != null)
        vm.viewModelScope.cancel()
    }

    private companion object {
        const val SLEEP_MS = 180_000L
    }
}
