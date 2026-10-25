package kz.astanahub.robot.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    /** Голос-дублёр: запоминает, что «сказано», и позволяет «произнести» фразу посетителя. */
    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<List<String>>()
        val languages = mutableListOf<Lang>()
        var stops = 0
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
            delay(10)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() { stops++ }
        override fun setLanguage(lang: Lang) { languages += lang }
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var camera: CameraGateway
    private lateinit var voice: TestVoice

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk()
        camera = mockk()
        voice = TestVoice()
        coEvery { backend.quickQuestions(any()) } answers {
            ApiResult.Success(listOf(QuickQuestion("q1", "Q-${firstArg<Lang>().code}")))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createVm(): MainViewModel =
        MainViewModel(backend, voice, camera, phrases, clock = { testScheduler.currentTime })
            .also { advanceUntilIdle() }

    private fun answer(text: String, phrases: List<String> = listOf(text), lang: Lang? = Lang.RU, found: Boolean = true) =
        ApiResult.Success(Answer("req", text, phrases, lang, found))

    private fun failure(kind: ErrorKind, retryAfter: Long? = null) =
        ApiResult.Failure(BackendError(kind, retryAfterSeconds = retryAfter))

    @Test
    fun `quick questions are loaded on start`() = runTest(dispatcher) {
        val vm = createVm()
        assertEquals(listOf(QuickQuestion("q1", "Q-ru")), vm.state.value.quickQuestions)
    }

    @Test
    fun `quick questions fall back to offline list`() = runTest(dispatcher) {
        coEvery { backend.quickQuestions(any()) } returns failure(ErrorKind.NETWORK)
        val vm = createVm()
        assertEquals(MainViewModel.OFFLINE_QUICK_QUESTIONS.getValue(Lang.RU), vm.state.value.quickQuestions)
    }

    @Test
    fun `successful ask shows and speaks answer, phases go thinking - speaking - idle`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Раз. Два.", listOf("Раз.", "Два."))
        val vm = createVm()

        vm.state.test {
            assertEquals(Phase.IDLE, awaitItem().phase)
            vm.submit("  Где регистрация?  ")
            var s = awaitItem()
            while (s.phase != Phase.THINKING) s = awaitItem()
            assertEquals("Где регистрация?", s.question)
            while (s.phase != Phase.SPEAKING) s = awaitItem()
            assertEquals("Раз. Два.", s.answer)
            while (s.phase != Phase.IDLE) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(listOf("Раз.", "Два.")), voice.spoken)
        assertFalse(vm.state.value.offline)
        coVerify { backend.ask("Где регистрация?", match { it.lang == null }) }
    }

    @Test
    fun `server unavailable gives local no-connection phrase`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns failure(ErrorKind.LLM_UNAVAILABLE)
        val vm = createVm()

        vm.submit("Что такое Astana Hub?")
        advanceUntilIdle()

        assertEquals("NO_CONNECTION:ru", vm.state.value.answer)
        assertTrue(vm.state.value.offline)
        assertEquals(listOf(listOf("NO_CONNECTION:ru")), voice.spoken)
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `bad request asks to repeat`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns failure(ErrorKind.BAD_REQUEST)
        val vm = createVm()

        vm.submit("ээ")
        advanceUntilIdle()

        assertEquals("NOT_HEARD:ru", vm.state.value.answer)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `rate limit - waits silently with a thinking face and retries once`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returnsMany listOf(
            failure(ErrorKind.RATE_LIMITED, retryAfter = 2),
            answer("Готово."),
        )
        val vm = createVm()

        vm.submit("Вопрос")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        advanceUntilIdle()

        assertEquals(listOf(listOf("Готово.")), voice.spoken)
        assertEquals("Готово.", vm.state.value.answer)
        coVerify(exactly = 2) { backend.ask(any(), any()) }
    }

    @Test
    fun `look takes a photo and calls look endpoint`() = runTest(dispatcher) {
        val jpeg = byteArrayOf(1, 2, 3)
        coEvery { camera.captureJpeg(any()) } returns jpeg
        coEvery { backend.look(any(), any(), any()) } returns answer("Это коворкинг.")
        val vm = createVm()

        vm.lookAround()
        advanceUntilIdle()

        coVerify { backend.look(jpeg, "Что это?", any()) }
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        assertEquals("Это коворкинг.", vm.state.value.answer)
    }

    @Test
    fun `payload too large recompresses once`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1)
        coEvery { backend.look(any(), any(), any()) } returnsMany listOf(
            failure(ErrorKind.PAYLOAD_TOO_LARGE),
            answer("Ок."),
        )
        val vm = createVm()

        vm.submit("Что это?")
        advanceUntilIdle()

        coVerify { camera.captureJpeg(50) }
        assertEquals("Ок.", vm.state.value.answer)
    }

    @Test
    fun `camera unavailable is reported without calling backend`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns null
        val vm = createVm()

        vm.submit("What is this?")
        advanceUntilIdle()

        assertEquals("CAMERA_UNAVAILABLE:ru", vm.state.value.answer)
        coVerify(exactly = 0) { backend.look(any(), any(), any()) }
    }

    @Test
    fun `explicit language is sent to server and reloads quick questions`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Hi.", lang = Lang.EN)
        val vm = createVm()

        vm.selectLanguage(Lang.EN)
        advanceUntilIdle()
        vm.submit("Where is the cafe?")
        advanceUntilIdle()

        assertEquals(Lang.EN, vm.state.value.lang)
        assertEquals(listOf(QuickQuestion("q1", "Q-en")), vm.state.value.quickQuestions)
        assertEquals(listOf(Lang.RU, Lang.EN), voice.languages) // RU — подсказка при старте
        coVerify { backend.ask("Where is the cafe?", match { it.lang == Lang.EN }) }
    }

    @Test
    fun `answer language is adopted when visitor did not choose one`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Сәлем.", lang = Lang.KK)
        val vm = createVm()

        vm.submit("Сәлем")
        advanceUntilIdle()

        assertEquals(Lang.KK, vm.state.value.lang)
        assertEquals(listOf(QuickQuestion("q1", "Q-kk")), vm.state.value.quickQuestions)
    }

    @Test
    fun `final ASR transcript triggers a question, partial only updates subtitle`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Ответ.")
        val vm = createVm()

        voice.asr.emit(Transcript("Где ка", isFinal = false))
        advanceUntilIdle()
        assertEquals("Где ка", vm.state.value.partialQuestion)
        coVerify(exactly = 0) { backend.ask(any(), any()) }

        voice.asr.emit(Transcript("Где кафе?", isFinal = true))
        advanceUntilIdle()
        assertNull(vm.state.value.partialQuestion)
        assertEquals("Где кафе?", vm.state.value.question)
        coVerify { backend.ask("Где кафе?", any()) }
    }

    @Test
    fun `new question interrupts current speech`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Ответ.")
        val vm = createVm()

        vm.submit("Первый")
        vm.submit("Второй")
        advanceUntilIdle()

        assertTrue(voice.stops >= 2)
        assertEquals("Второй", vm.state.value.question)
        coVerify(exactly = 0) { backend.ask("Первый", any()) }
    }

    @Test
    fun `repeated tap on the same question while thinking sends one request`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } coAnswers { delay(1_000); answer("Ответ.") }
        val vm = createVm()

        repeat(6) { vm.submit("Где кафе?"); testScheduler.advanceTimeBy(100) }
        assertTrue(vm.state.value.processing)
        advanceUntilIdle()

        coVerify(exactly = 1) { backend.ask("Где кафе?", any()) }
        assertEquals("Ответ.", vm.state.value.answer)
        assertFalse(vm.state.value.processing)
    }

    private fun TestScope.createVmWithMap(
        robot: kz.astanahub.robot.robot.RobotGateway,
        screensaverMillis: Long? = null,
    ): MainViewModel {
        coEvery { backend.places(any()) } returns ApiResult.Success(
            kz.astanahub.robot.backend.PlacesResult(kz.astanahub.robot.backend.FakeBackendApi.FAKE_PLACES, null, false),
        )
        val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
        val wf = kz.astanahub.robot.places.WayFinder(kz.astanahub.robot.places.PlaceRepository(backend, robot, file, dispatcher), robot)
        return MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, screensaverMillis = screensaverMillis,
        ).also { if (screensaverMillis == null) advanceUntilIdle() else testScheduler.runCurrent() }
    }

    private fun mapRobot(): kz.astanahub.robot.robot.RobotGateway = mockk(relaxed = true) {
        coEvery { getPlaces() } returns listOf(kz.astanahub.robot.robot.MapPlace("Кафе", 0.0, 12.0))
        coEvery { getPosition() } returns kz.astanahub.robot.robot.RobotPose(0.0, 0.0, 0.0)
    }

    @Test
    fun `where is answered locally without server and offers guiding`() = runTest(dispatcher) {
        val robot = mapRobot()
        val vm = createVmWithMap(robot)

        vm.submit("Асхана қайда?")
        advanceUntilIdle()

        assertTrue(vm.state.value.answer!!, vm.state.value.answer!!.startsWith("Кафе"))
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    @Test
    fun `unknown place falls back to server ask`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Банкомат на 1 этаже.")
        val vm = createVmWithMap(mapRobot())

        vm.submit("Где банкомат?")
        advanceUntilIdle()

        coVerify { backend.ask("Где банкомат?", any()) }
        assertEquals("Банкомат на 1 этаже.", vm.state.value.answer)
    }

    @Test
    fun `guide button navigates and announces arrival`() = runTest(dispatcher) {
        val robot = mapRobot()
        coEvery { robot.navigateTo("Кафе", any()) } coAnswers { delay(5_000); kz.astanahub.robot.robot.NavigationResult.Arrived }
        val vm = createVmWithMap(robot)
        vm.submit("Где кафе?")
        advanceUntilIdle()

        vm.guideToOffered()
        testScheduler.advanceTimeBy(1_000)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertEquals("Кафе", vm.state.value.guidingTo)
        advanceUntilIdle()

        assertEquals("Мы пришли. Это Кафе.", vm.state.value.answer)
        assertNull(vm.state.value.guidingTo)
        assertTrue(voice.spoken.flatten().contains("Идите за мной. Провожу: Кафе."))
    }

    @Test
    fun `stop cancels guiding`() = runTest(dispatcher) {
        val robot = mapRobot()
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(60_000); kz.astanahub.robot.robot.NavigationResult.Arrived }
        val vm = createVmWithMap(robot)

        vm.submit("Проводи меня до кафе")
        testScheduler.advanceTimeBy(1_000)
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        vm.stop()
        advanceUntilIdle()

        io.mockk.verify { robot.stopNavigation() }
        assertEquals("Хорошо, стою.", vm.state.value.answer)
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `screensaver after inactivity resets session and wakes on touch`() = runTest(dispatcher) {
        coEvery { backend.ask(any(), any()) } returns answer("Ответ.", lang = Lang.EN)
        val vm = createVmWithMap(mapRobot(), screensaverMillis = 60_000)
        vm.submit("Hello")
        testScheduler.advanceTimeBy(5_000)
        assertEquals(Lang.EN, vm.state.value.lang)
        assertFalse(vm.state.value.screensaver)

        testScheduler.advanceTimeBy(61_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.screensaver)
        assertNull(vm.state.value.answer)
        assertEquals(Lang.RU, vm.state.value.lang)

        vm.wake()
        assertFalse(vm.state.value.screensaver)
    }

    @Test
    fun `session id changes after idle timeout`() = runTest(dispatcher) {
        val ids = mutableListOf<String>()
        coEvery { backend.ask(any(), any()) } answers {
            ids += secondArg<kz.astanahub.robot.backend.AskContext>().sessionId
            answer("Ок.")
        }
        val vm = createVm()

        vm.submit("1"); advanceUntilIdle()
        vm.submit("2"); advanceUntilIdle()
        testScheduler.advanceTimeBy(MainViewModel.SESSION_IDLE_MILLIS + 1)
        vm.submit("3"); advanceUntilIdle()

        assertEquals(ids[0], ids[1])
        assertTrue(ids[1] != ids[2])
    }

    @Test
    fun `phrase mapping follows contract table`() {
        assertEquals(PhraseKey.NOT_HEARD, MainViewModel.phraseFor(BackendError(ErrorKind.BAD_REQUEST)))
        listOf(ErrorKind.UNAUTHORIZED, ErrorKind.LLM_UNAVAILABLE, ErrorKind.TIMEOUT, ErrorKind.NETWORK, ErrorKind.NOT_CONFIGURED)
            .forEach { assertEquals(PhraseKey.NO_CONNECTION, MainViewModel.phraseFor(BackendError(it))) }
    }
}
