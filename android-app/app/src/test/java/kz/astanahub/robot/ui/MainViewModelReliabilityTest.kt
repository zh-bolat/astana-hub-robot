package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.AskContext
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.InMemoryQuickQuestionCache
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Волна 3: фильтр реплик, «нет связи», офлайн-кнопки, гонка навигации, session_id / place_id. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelReliabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()
        var stops = 0
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        val conn = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val connection: Flow<Boolean> = conn
        override val activity = MutableStateFlow(VoiceActivity.IDLE)
        var speakMillis = 10L

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            try {
                spoken += phrases
                delay(speakMillis)
                return true
            } finally {
                activity.value = VoiceActivity.IDLE
            }
        }

        override fun stopSpeaking() { stops++ }
        override fun setLanguage(lang: Lang) = Unit
        override fun setSessionId(id: String) { sessionIds += id }
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private val camera = mockk<CameraGateway>()
    private lateinit var voice: TestVoice

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk()
        voice = TestVoice()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Q")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun answer(text: String) = ApiResult.Success(Answer("req", text, listOf(text), Lang.RU, true))

    private fun robot(): RobotGateway = mockk(relaxed = true) {
        coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0), MapPlace("Ресепшен", 3.0, 0.0))
        coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        every { connected } returns MutableStateFlow(true)
    }

    private fun TestScope.vm(
        robot: RobotGateway? = null,
        cache: InMemoryQuickQuestionCache? = null,
    ): MainViewModel {
        val wf = robot?.let {
            WayFinder(PlaceRepository(backend, it, java.io.File(tmp.root, "places.json"), dispatcher), it)
        }
        return MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, quickCache = cache,
        ).also { advanceUntilIdle() }
    }

    // --- 2. посторонние реплики ---

    @Test
    fun `while thinking only stop or a substantial new phrase gets through`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } coAnswers { delay(5_000); answer("Ответ.") }
        val vm = vm()
        vm.submit("Что такое Astana Hub?")
        advanceTimeBy(100)
        assertEquals(Phase.THINKING, vm.state.value.phase)

        voice.asr.emit(Transcript("угу", isFinal = true))
        voice.asr.emit(Transcript("да", isFinal = true))
        voice.asr.emit(Transcript("ага понятно", isFinal = true))
        voice.asr.emit(Transcript("…", isFinal = false))
        advanceTimeBy(100)
        assertEquals("Что такое Astana Hub?", vm.state.value.question)
        assertEquals(null, vm.state.value.partialQuestion)

        voice.asr.emit(Transcript("А как попасть на мероприятие?", isFinal = true))
        advanceUntilIdle()
        assertEquals("А как попасть на мероприятие?", vm.state.value.question)
        coVerify(exactly = 2) { backend.ask(any(), any()) }
    }

    @Test
    fun `voice stop command cancels the request in any language`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } coAnswers { delay(5_000); answer("Ответ.") }
        val vm = vm()
        vm.submit("Расскажи про хаб")
        advanceTimeBy(100)
        voice.asr.emit(Transcript("Тоқта!", isFinal = true))
        advanceUntilIdle()
        assertEquals(Phase.IDLE, vm.state.value.phase)
        assertEquals(null, vm.state.value.answer)
        assertTrue(voice.stops >= 2)
        coVerify(exactly = 1) { backend.ask(any(), any()) }
    }

    @Test
    fun `while navigating short replies are ignored and stop halts the robot`() = runTest(dispatcher) {
        val robot = robot()
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(60_000); NavigationResult.Arrived }
        val vm = vm(robot)
        vm.submit("Проводи до кафе")
        advanceTimeBy(1_000)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)

        voice.asr.emit(Transcript("ага", isFinal = true))
        advanceTimeBy(100)
        assertEquals("Кафе", vm.state.value.guidingTo)

        voice.asr.emit(Transcript("stop", isFinal = true))
        advanceUntilIdle()
        verify { robot.stopNavigation() }
        assertEquals("Хорошо, стою.", vm.state.value.answer)
    }

    @Test
    fun `when idle any final phrase is a question`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Да.")
        vm()
        voice.asr.emit(Transcript("Wi-Fi?", isFinal = true))
        advanceUntilIdle()
        coVerify { backend.ask("Wi-Fi?", any()) }
    }

    // --- 3. фразы навигации внутри поездки ---

    @Test
    fun `obstacle phrase is not queued repeatedly and dies with the trip`() = runTest(dispatcher) {
        val robot = robot()
        voice.speakMillis = 2_000
        coEvery { robot.navigateTo(any(), any()) } coAnswers {
            val onEvent = secondArg<(NavEvent) -> Unit>()
            repeat(5) { onEvent(NavEvent.OBSTACLE); delay(100) }
            awaitCancellation()
        }
        val vm = vm(robot)
        vm.submit("Проводи до кафе")
        advanceTimeBy(3_000)
        assertEquals(1, voice.spoken.count { it == "Позвольте пройти, пожалуйста." || it.startsWith("Позвольте") })

        vm.stop()
        advanceUntilIdle()
        assertEquals(1, voice.spoken.count { it.startsWith("Позвольте") })
    }

    // --- 4. офлайн ---

    @Test
    fun `lost STT connection says no-connection once per minute and sets the badge`() = runTest(dispatcher) {
        val vm = vm()
        voice.conn.emit(false)
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertEquals(listOf("NO_CONNECTION:ru"), voice.spoken)

        advanceTimeBy(30_000)
        voice.conn.emit(false)
        advanceUntilIdle()
        assertEquals(1, voice.spoken.size)

        advanceTimeBy(MainViewModel.NO_CONNECTION_REPEAT_MILLIS)
        voice.conn.emit(false)
        advanceUntilIdle()
        assertEquals(2, voice.spoken.size)

        voice.conn.emit(true)
        advanceUntilIdle()
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `no-connection phrase uses the current language`() = runTest(dispatcher) {
        val vm = vm()
        vm.selectLanguage(Lang.KK)
        advanceUntilIdle()
        voice.conn.emit(false)
        advanceUntilIdle()
        assertEquals(listOf("NO_CONNECTION:kk"), voice.spoken)
    }

    @Test
    fun `quick questions are cached and shown offline together with place buttons`() = runTest(dispatcher) {
        val cache = InMemoryQuickQuestionCache()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Что такое Astana Hub?")))
        vm(cache = cache)
        assertEquals(listOf(QuickQuestion("q1", "Что такое Astana Hub?")), cache.load(Lang.RU))

        // Сети нет совсем: места — только точки карты робота (кеша мест с сервера ещё нет).
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        coEvery { backend.places(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val offline = vm(robot = robot(), cache = cache)
        val texts = offline.state.value.quickQuestions.map { it.text }
        assertEquals(listOf("Где Кафе?", "Где Ресепшен?", "Проводите: Кафе", "Проводите: Ресепшен", "Что такое Astana Hub?"), texts)
    }

    @Test
    fun `quick questions reload from server once connection is back`() = runTest(dispatcher) {
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val vm = vm()
        assertEquals(MainViewModel.OFFLINE_QUICK_QUESTIONS.getValue(Lang.RU), vm.state.value.quickQuestions)

        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Онлайн")))
        voice.conn.emit(true)
        advanceUntilIdle()
        assertEquals(listOf(QuickQuestion("q1", "Онлайн")), vm.state.value.quickQuestions)
    }

    @Test
    fun `where is still works offline`() = runTest(dispatcher) {
        coEvery { backend.places(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val vm = vm(robot = robot())
        vm.submit("Где кафе?")
        advanceUntilIdle()
        assertTrue(vm.state.value.answer!!, vm.state.value.answer!!.startsWith("Кафе"))
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    // --- 6. гонка stop → start навигации ---

    @Test
    fun `new guide during a trip waits for stop confirmation before starting`() = runTest(dispatcher) {
        val robot = robot()
        val stopConfirmed = CompletableDeferred<Boolean>()
        coEvery { robot.navigateTo(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { robot.stopNavigationAndWait(any()) } coAnswers { stopConfirmed.await() }
        val vm = vm(robot)

        vm.submit("Проводи до кафе")
        advanceTimeBy(1_000)
        vm.submit("Проводи до ресепшена")
        advanceTimeBy(1_000)
        coVerify(exactly = 1) { robot.navigateTo(any(), any()) }

        stopConfirmed.complete(true)
        advanceTimeBy(1_000)
        coVerifyOrder {
            robot.navigateTo("Кафе", any())
            robot.stopNavigationAndWait(RobotGateway.STOP_CONFIRM_TIMEOUT_MS)
            robot.navigateTo("Ресепшен", any())
        }
        assertEquals("Ресепшен", vm.state.value.guidingTo)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertFalse("прежняя поездка не говорит «стою»", voice.spoken.contains("Хорошо, стою."))
        vm.stop()
        advanceUntilIdle()
    }

    // --- 7. session_id и place_id ---

    @Test
    fun `stt session id follows the screen session and ask gets nearest place id`() = runTest(dispatcher) {
        val contexts = mutableListOf<AskContext>()
        coEvery { backend.ask(any(), any()) } answers { contexts += secondArg<AskContext>(); answer("Ок.") }
        val vm = vm(robot())

        vm.submit("Что такое Astana Hub?")
        advanceUntilIdle()
        assertEquals(contexts[0].sessionId, voice.sessionIds.last())
        assertEquals("reception", contexts[0].placeId)

        advanceTimeBy(MainViewModel.SESSION_IDLE_MILLIS + 1)
        vm.submit("А Tech Garden?")
        advanceUntilIdle()
        assertTrue(contexts[1].sessionId != contexts[0].sessionId)
        assertEquals(contexts[1].sessionId, voice.sessionIds.last())
    }
}
