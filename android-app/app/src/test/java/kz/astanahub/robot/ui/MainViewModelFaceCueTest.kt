package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
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
import org.junit.Before
import org.junit.Test

/** Реакции лица (MainViewModel.faceCues) на настоящие события: ошибки, «не понял», приветствие, итог поездки. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelFaceCueTest {

    private class TestVoice : VoiceGateway {
        override val transcripts: Flow<Transcript> = MutableSharedFlow()
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            delay(10)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() = Unit
        override fun setLanguage(lang: Lang) = Unit
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var camera: CameraGateway
    private val voice = TestVoice()
    private val cues = mutableListOf<FaceCue>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        camera = mockk()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q", "Q")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(
        robot: RobotGateway? = null,
        presence: Flow<PresenceEvent>? = null,
    ): MainViewModel {
        val wf = robot?.let {
            val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
            WayFinder(PlaceRepository(backend, it, file, dispatcher), it)
        }
        val vm = MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, presence = presence,
        )
        backgroundScope.launch { vm.faceCues.collect { cues += it } }
        advanceUntilIdle()
        return vm
    }

    private fun robot(result: suspend () -> NavigationResult): RobotGateway = mockk(relaxed = true) {
        coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
        coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        coEvery { navigateTo(any(), any()) } coAnswers { result() }
    }

    @Test
    fun `network failure shows the error face`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val vm = createVm()
        vm.submit("Что такое Astana Hub?")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.ERROR), cues)
    }

    @Test
    fun `bad request means not heard - confused face`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.BAD_REQUEST))
        val vm = createVm()
        vm.submit("ммм")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.CONFUSED), cues)
    }

    @Test
    fun `answer not found in the knowledge base is confused, found answer is quiet`() = runTest(dispatcher) {
        coEvery { backend.ask("Сколько звёзд на небе?", any()) } returns
            ApiResult.Success(Answer("r1", "Не знаю, спросите на ресепшене.", listOf("Не знаю."), Lang.RU, found = false))
        coEvery { backend.ask("Что такое Astana Hub?", any()) } returns
            ApiResult.Success(Answer("r2", "Технопарк.", listOf("Технопарк."), Lang.RU, found = true))
        val vm = createVm()
        vm.submit("Что такое Astana Hub?")
        advanceUntilIdle()
        assertEquals(emptyList<FaceCue>(), cues)
        vm.submit("Сколько звёзд на небе?")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.CONFUSED), cues)
    }

    @Test
    fun `camera without a frame shows the error face`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns null
        val vm = createVm()
        vm.lookAround()
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.ERROR), cues)
    }

    @Test
    fun `arrival is happy`() = runTest(dispatcher) {
        val vm = createVm(robot { delay(1_000); NavigationResult.Arrived })
        vm.submit("Проводи в кафе")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.ARRIVED), cues)
    }

    @Test
    fun `failed trip is the error face`() = runTest(dispatcher) {
        val vm = createVm(robot { NavigationResult.Failed(-1, "blocked") })
        vm.submit("Проводи в кафе")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.TRIP_FAILED), cues)
    }

    @Test
    fun `stop during the trip is acknowledged`() = runTest(dispatcher) {
        val never = CompletableDeferred<NavigationResult>()
        val vm = createVm(robot { never.await() })
        vm.submit("Проводи в кафе")
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        vm.stop()
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.TRIP_CANCELLED), cues)
    }

    @Test
    fun `unknown place to guide to is confused`() = runTest(dispatcher) {
        val vm = createVm(robot { NavigationResult.Arrived })
        vm.submit("Проводи в бассейн")
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.CONFUSED), cues)
    }

    @Test
    fun `visitor arriving is greeted`() = runTest(dispatcher) {
        val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
        createVm(presence = presence)
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        advanceUntilIdle()
        assertEquals(listOf(FaceCue.GREET), cues)
    }

    @Test
    fun `trip results map to reactions`() {
        assertEquals(FaceCue.ARRIVED, MainViewModel.tripCue(NavigationResult.Arrived))
        assertEquals(FaceCue.ARRIVED, MainViewModel.tripCue(NavigationResult.AlreadyThere))
        assertEquals(FaceCue.TRIP_CANCELLED, MainViewModel.tripCue(NavigationResult.Cancelled))
        assertEquals(FaceCue.CONFUSED, MainViewModel.tripCue(NavigationResult.UnknownPlace))
        assertEquals(FaceCue.TRIP_FAILED, MainViewModel.tripCue(NavigationResult.NotLocalized))
        assertEquals(FaceCue.TRIP_FAILED, MainViewModel.tripCue(NavigationResult.Failed(1, null)))
    }
}
