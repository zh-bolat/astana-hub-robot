package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import kz.astanahub.robot.chat.ChatAction
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MF4, шумный зал: после запуска робот спит; будит только тап по лицу (голос и подошедший — нет), двойной тап — сон,
 * сам засыпает через 5 мин без разговора, поездку сон не прерывает; плитка во сне будит и отвечает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSleepTest {

    private class TestVoice : VoiceGateway {
        var stops = 0
        val spoken = mutableListOf<String>()
        val paused = mutableListOf<Boolean>()
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
            stops++
            activity.value = VoiceActivity.IDLE
        }

        override fun setLanguage(lang: Lang) = Unit

        override fun setListeningPaused(paused: Boolean) {
            this.paused += paused
        }
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private val voice = TestVoice()
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
        startAsleep: Boolean = true,
    ): MainViewModel {
        val wf = robot?.let {
            val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
            WayFinder(PlaceRepository(backend, it, file, dispatcher), it)
        }
        val vm = MainViewModel(
            backend, voice, mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, presence = presence, chatMode = { ChatMode.AGENT },
            screensaverMillis = SLEEP_MS, startAsleep = startAsleep,
        )
        backgroundScope.launch { vm.faceCues.collect { cues += it } }
        runCurrent()
        return vm
    }

    @Test
    fun `starts asleep - voice and a visitor do not wake, listening paused, nothing goes to the server`() = runTest(dispatcher) {
        val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
        val vm = createVm(presence = presence)
        assertTrue(vm.state.value.asleep)
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(true, voice.paused.last())
        voice.asr.emit(Transcript("Где тут кафе?", isFinal = true))
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        advanceTimeBy(5_000); runCurrent()
        assertTrue(vm.state.value.asleep)
        assertTrue(chatRequests.isEmpty())
        assertTrue(voice.spoken.isEmpty())
        assertTrue(cues.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `one tap wakes and greets, double tap puts it to sleep and cuts the speech`() = runTest(dispatcher) {
        val vm = createVm()
        vm.wakeByTap()
        runCurrent()
        assertFalse(vm.state.value.asleep)
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        assertEquals(false, voice.paused.last())
        assertEquals(listOf(FaceCue.GREET), cues)
        assertEquals(ChatRequest.EVENT_VISITOR_ARRIVED, chatRequests.single().event)
        advanceTimeBy(500); runCurrent()
        assertTrue("greeting is playing", vm.robotSpeaking)
        val stops = voice.stops
        vm.sleepByTap()
        runCurrent()
        assertTrue(vm.state.value.asleep)
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertTrue(voice.stops > stops)
        assertFalse(vm.robotSpeaking)
        assertEquals(true, voice.paused.last())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `falls asleep after 5 minutes without conversation, a visitor nearby does not keep it awake`() = runTest(dispatcher) {
        val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
        val vm = createVm(presence = presence, startAsleep = false)
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        runCurrent()
        advanceTimeBy(SLEEP_MS - 1_000); runCurrent()
        assertFalse(vm.state.value.asleep)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(vm.state.value.asleep)
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a conversation restarts the 5 minutes`() = runTest(dispatcher) {
        val vm = createVm(startAsleep = false)
        advanceTimeBy(SLEEP_MS - 60_000); runCurrent()
        vm.submit("Что такое Astana Hub?")
        advanceTimeBy(4_000); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertFalse("talked a minute ago", vm.state.value.asleep)
        advanceTimeBy(SLEEP_MS); runCurrent()
        assertTrue(vm.state.value.asleep)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `double tap in a trip - the trip goes on, falls asleep after it`() = runTest(dispatcher) {
        val robot: RobotGateway = mockk(relaxed = true) {
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
            coEvery { navigateTo(any(), any()) } coAnswers { delay(10_000); NavigationResult.Arrived }
        }
        val vm = createVm(robot = robot, startAsleep = false)
        every { backend.chat(any()) } answers {
            flow {
                emit(ChatEvent.Action(ChatAction(ChatAction.GUIDE_TO, "cafe")))
                emit(ChatEvent.Done("r", Lang.RU))
            }
        }
        vm.submit("Проводи в кафе")
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        vm.sleepByTap()
        runCurrent()
        assertFalse("trip is not interrupted", vm.state.value.asleep)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        verify(exactly = 0) { robot.stopNavigation() }
        advanceTimeBy(10_000); runCurrent()
        // Приехали, договорил «Мы пришли» — уснул.
        advanceTimeBy(8_000); runCurrent()
        assertTrue(vm.state.value.asleep)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `swipe to the app in sleep does not wake, back and stop neither, a tile wakes and answers`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openMenu()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        assertTrue(vm.state.value.asleep)
        vm.back()
        vm.stop()
        vm.onTouch()
        runCurrent()
        assertTrue(vm.state.value.asleep)
        assertTrue(chatRequests.isEmpty())
        vm.submitButton("Мероприятия в хабе?", display = "Мероприятия")
        advanceTimeBy(500); runCurrent()
        assertFalse(vm.state.value.asleep)
        assertEquals("MF5: the answer stays on the page", ScreenMode.MENU, vm.state.value.mode)
        assertEquals("Мероприятия в хабе?", chatRequests.single().question)
        assertEquals(listOf("Здравствуйте!"), voice.spoken)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `menu opened in sleep goes back to the sleeping face`() = runTest(dispatcher) {
        val vm = createVm()
        vm.openMenu()
        vm.showFace()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertTrue(vm.state.value.asleep)
        vm.viewModelScope.cancel()
    }

    private companion object {
        const val SLEEP_MS = 300_000L
    }
}
