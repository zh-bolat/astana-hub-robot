package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
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
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatInput
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.robot.HeadController
import kz.astanahub.robot.robot.HeadGateway
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Агентский чат на экране: события `/v1/chat`, действия, запасной путь, режимы экрана, человек и голова. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelAgentTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val langs = mutableListOf<Lang>()
        var stops = 0
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        val conn = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)
        override val connection: Flow<Boolean> = conn

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
            langs += lang
            delay(100)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() { stops++ }
        override fun setLanguage(lang: Lang) = Unit
    }

    private class FakeHead : HeadGateway {
        val calls = mutableListOf<String>()
        override fun startFocusFollow(faceId: Int, onLost: () -> Unit): Boolean { calls += "follow:$faceId"; return true }
        override fun stopFocusFollow() { calls += "stop" }
        override fun resetHead() { calls += "reset" }
        override fun nod() { calls += "nod" }
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var camera: CameraGateway
    private lateinit var robot: RobotGateway
    private lateinit var voice: TestVoice
    private val headGateway = FakeHead()
    private var mode = ChatMode.AGENT
    private val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 8)
    private val requests = mutableListOf<ChatRequest>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        camera = mockk()
        voice = TestVoice()
        robot = mockk(relaxed = true) {
            every { connected } returns MutableStateFlow(true)
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        }
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Что такое Astana Hub?")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
        coEvery { backend.ask(any(), any()) } returns ApiResult.Success(Answer("r", "Ответ ask.", listOf("Ответ ask."), Lang.RU, true))
        coEvery { backend.postLocalMessage(any(), any()) } returns ApiResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun chat(block: suspend kotlinx.coroutines.flow.FlowCollector<ChatEvent>.(ChatRequest) -> Unit) {
        every { backend.chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            requests += req
            flow { block(req) }
        }
    }

    private fun TestScope.vm(
        withMap: Boolean = true,
        screensaverMillis: Long? = null,
        head: HeadController? = HeadController(headGateway, clock = { testScheduler.currentTime }),
    ): MainViewModel {
        val wf = if (withMap) {
            val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
            WayFinder(PlaceRepository(backend, robot, file, dispatcher), robot)
        } else null
        return MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, screensaverMillis = screensaverMillis,
            chatMode = { mode }, presence = presence, head = head,
        ).also { if (screensaverMillis == null) advanceUntilIdle() else runCurrent() }
    }

    private val done = ChatEvent.Done("req", Lang.RU, emptyList())

    // --- события хода ---

    @Test
    fun `phrases are spoken in order, emotion shown and relaxed afterwards`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Turn("t1", "r1"))
            emit(ChatEvent.Emotion("happy"))
            emit(ChatEvent.Phrase("Есть коворкинг на втором этаже."))
            delay(500)
            emit(ChatEvent.Phrase("Проводить вас?"))
            emit(done)
        }
        val vm = vm()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        voice.asr.emit(Transcript("Где тут можно поработать?", isFinal = true))
        advanceTimeBy(50); runCurrent()
        assertEquals(Emotion.HAPPY, vm.state.value.emotion)
        assertEquals("Есть коворкинг на втором этаже.", vm.state.value.subtitle)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("Есть коворкинг на втором этаже.", "Проводить вас?"), voice.spoken)
        assertEquals("Есть коворкинг на втором этаже. Проводить вас?", vm.state.value.answer)
        assertEquals(ChatInput.VOICE, requests.single().input)
        assertEquals("Где тут можно поработать?", requests.single().text)
        advanceUntilIdle()
        assertEquals(Emotion.NEUTRAL, vm.state.value.emotion)
        assertEquals(Phase.IDLE, vm.state.value.phase)
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    @Test
    fun `where_is action - robot computes and speaks the direction, not duplicated in history`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Action("where_is", mapOf("place_id" to "cafe", "map_name" to "Кафе")))
            emit(done)
        }
        val vm = vm()
        vm.submit("Где поесть?")
        advanceUntilIdle()
        assertTrue(voice.spoken.toString(), voice.spoken.joinToString(" ").contains("Кафе"))
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        coVerify(exactly = 0) { backend.postLocalMessage(any(), any()) }
    }

    @Test
    fun `legacy where is answers locally and goes to history`() = runTest(dispatcher) {
        mode = ChatMode.LEGACY
        val vm = vm()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        vm.submit("Где кафе?")
        advanceUntilIdle()
        coVerify(exactly = 0) { backend.chat(any()) }
        coVerify { backend.postLocalMessage(any(), match { it.kind == "where_is" && it.placeId == "cafe" }) }
    }

    @Test
    fun `guide_to action drives the robot, head stops following while driving`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(5_000); NavigationResult.Arrived }
        chat {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Phrase("Пойдёмте!"))
            emit(ChatEvent.Action("guide_to", mapOf("place_id" to "cafe", "map_name" to "Кафе")))
            emit(done)
        }
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(9, greet = false))
        runCurrent()
        vm.submit("Да, проводи")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertEquals("Кафе", vm.state.value.guidingTo)
        assertEquals(listOf("follow:9", "stop"), headGateway.calls)
        advanceUntilIdle()
        coVerify { robot.navigateTo("Кафе", any()) }
        assertEquals(listOf("follow:9", "stop", "follow:9"), headGateway.calls)
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `stop action stops navigation`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(60_000); NavigationResult.Arrived }
        mode = ChatMode.LEGACY
        val vm = vm()
        vm.submit("Проводи до кафе")
        advanceTimeBy(1_000)
        assertEquals("Кафе", vm.state.value.guidingTo)
        mode = ChatMode.AGENT
        chat {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Action("stop", emptyMap()))
            emit(ChatEvent.Phrase("Остановился."))
            emit(done)
        }
        vm.submit("Подожди, остановись пожалуйста сейчас")
        advanceTimeBy(2_000); runCurrent()
        verify { robot.stopNavigation() }
        assertEquals(null, vm.state.value.guidingTo)
        assertTrue(requests.single().navigating)
    }

    @Test
    fun `look action - frame is posted to the turn`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1, 2, 3)
        coEvery { backend.chatFrame(any(), any()) } returns ApiResult.Success(Unit)
        chat {
            emit(ChatEvent.Turn("trn_look", null))
            emit(ChatEvent.Action("look", emptyMap()))
            delay(3_000)
            emit(ChatEvent.Phrase("Вижу стенд стартапов."))
            emit(done)
        }
        val vm = vm()
        vm.lookAround()
        advanceUntilIdle()
        coVerify { backend.chatFrame("trn_look", byteArrayOf(1, 2, 3)) }
        assertEquals(listOf("Вижу стенд стартапов."), voice.spoken)
        assertEquals(ChatInput.BUTTON, requests.single().input)
        coVerify(exactly = 0) { backend.look(any(), any(), any()) }
    }

    @Test
    fun `look action - slow camera gives up after 8 s`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } coAnswers { delay(20_000); byteArrayOf(1) }
        chat {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Action("look", emptyMap()))
            delay(9_000)
            emit(ChatEvent.Phrase("Без кадра не вижу."))
            emit(done)
        }
        vm().lookAround()
        advanceUntilIdle()
        coVerify(exactly = 0) { backend.chatFrame(any(), any()) }
        assertEquals(listOf("Без кадра не вижу."), voice.spoken)
    }

    // --- запасной путь ---

    @Test
    fun `no turn in 4 s - fallback to ask`() = runTest(dispatcher) {
        chat { awaitCancellation() }
        val vm = vm(withMap = false)
        vm.submit("Что такое Astana Hub?")
        advanceTimeBy(3_900); runCurrent()
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        advanceUntilIdle()
        coVerify { backend.ask("Что такое Astana Hub?", any()) }
        assertEquals(listOf("Ответ ask."), voice.spoken)
    }

    @Test
    fun `error event before a reply - fallback to local rules`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Turn("t", null)); emit(ChatEvent.Error(BackendError(ErrorKind.LLM_UNAVAILABLE))) }
        val vm = vm()
        vm.submit("Где кафе?")
        advanceUntilIdle()
        assertTrue(voice.spoken.joinToString(" ").contains("Кафе"))
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        coVerify { backend.postLocalMessage(any(), match { it.kind == "where_is" }) }
    }

    @Test
    fun `offline - local rules right away, no chat request`() = runTest(dispatcher) {
        chat { emit(done) }
        val vm = vm()
        voice.conn.emit(false)
        advanceUntilIdle()
        voice.spoken.clear()
        vm.submit("Где кафе?")
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertTrue(voice.spoken.joinToString(" ").contains("Кафе"))
    }

    @Test
    fun `new question interrupts the stream`() = runTest(dispatcher) {
        var cancelled = 0
        every { backend.chat(any()) } answers {
            requests += firstArg<ChatRequest>()
            if (requests.size == 1) {
                flow<ChatEvent> {
                    emit(ChatEvent.Turn("t1", null))
                    emit(ChatEvent.Phrase("Длинный ответ, часть первая."))
                    awaitCancellation()
                }.onCompletion { if (it != null) cancelled++ }
            } else {
                flow { emit(ChatEvent.Turn("t2", null)); emit(ChatEvent.Phrase("Второй.")); emit(done) }
            }
        }
        val vm = vm()
        vm.submit("Расскажи про хаб")
        advanceTimeBy(500); runCurrent()
        vm.submit("А где кафе?")
        advanceUntilIdle()
        assertEquals(1, cancelled)
        assertEquals("Второй.", vm.state.value.answer)
        assertTrue(voice.stops >= 2)
    }

    // --- режимы экрана, человек, приветствие ---

    @Test
    fun `menu opens by button and returns to the face after 30 s`() = runTest(dispatcher) {
        chat { emit(done) }
        val vm = vm()
        vm.openMenu()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        advanceTimeBy(MainViewModel.MENU_IDLE_MILLIS - 1_000); runCurrent()
        assertEquals(ScreenMode.MENU, vm.state.value.mode)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        vm.openMenu()
        vm.showFace()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
    }

    @Test
    fun `visitor arrives - wakes from sleep, greets through the agent with a new session`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Phrase("Здравствуйте! Чем помочь?"))
            emit(done)
        }
        val vm = vm(screensaverMillis = 60_000)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        presence.emit(PresenceEvent.Arrived(4, greet = true))
        advanceTimeBy(100); runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode)
        assertEquals(Emotion.EXCITED, vm.state.value.emotion)
        advanceTimeBy(2_000); runCurrent()
        val req = requests.single()
        assertEquals(ChatRequest.EVENT_VISITOR_ARRIVED, req.event)
        assertEquals("", req.text)
        assertEquals(listOf("Здравствуйте! Чем помочь?"), voice.spoken)
        assertEquals("follow:4", headGateway.calls.last())
        vm.submit("Привет")
        advanceTimeBy(500); runCurrent()
        assertEquals(req.sessionId, requests[1].sessionId)
        presence.emit(PresenceEvent.Left) // иначе таймер заставки перезапускается бесконечно (человек рядом)
        advanceUntilIdle()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
    }

    @Test
    fun `no phrase from the agent in 2 s - local greeting`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Turn("t", null)); delay(5_000); emit(ChatEvent.Phrase("Поздно")); emit(done) }
        vm()
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        advanceUntilIdle()
        assertEquals(listOf(phrases.get(PhraseKey.GREETING, Lang.RU)), voice.spoken)
    }

    @Test
    fun `recently greeted visitor is not greeted again`() = runTest(dispatcher) {
        chat { emit(done) }
        vm()
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `visitor left - sleep, session closed, head reset - but not while driving`() = runTest(dispatcher) {
        chat { emit(done) }
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(10_000); NavigationResult.Arrived }
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(2, greet = false))
        runCurrent()
        mode = ChatMode.LEGACY
        vm.submit("Проводи до кафе")
        advanceTimeBy(1_000); runCurrent()
        presence.emit(PresenceEvent.Left)
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode) // едем — не спим
        advanceUntilIdle()
        mode = ChatMode.AGENT
        presence.emit(PresenceEvent.Arrived(2, greet = false))
        runCurrent()
        presence.emit(PresenceEvent.Left)
        runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(Emotion.NEUTRAL, vm.state.value.emotion)
        assertEquals("reset", headGateway.calls.last())
    }

    @Test
    fun `thanks makes the face happy, server trouble makes it sad`() = runTest(dispatcher) {
        mode = ChatMode.LEGACY
        coEvery { backend.ask(any(), any()) } coAnswers { delay(500); ApiResult.Failure(BackendError(ErrorKind.LLM_UNAVAILABLE)) }
        val vm = vm(withMap = false)
        vm.submit("Спасибо большое")
        runCurrent()
        assertEquals(Emotion.HAPPY, vm.state.value.emotion)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Emotion.SAD, vm.state.value.emotion)
        advanceUntilIdle()
        assertEquals(Emotion.NEUTRAL, vm.state.value.emotion)
    }

    @Test
    fun `agent language from done switches the screen language`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Turn("t", null)); emit(ChatEvent.Phrase("Hello!")); emit(ChatEvent.Done("r", Lang.EN, emptyList())) }
        val vm = vm()
        voice.asr.emit(Transcript("Hi there, what is this place?", isFinal = true, lang = Lang.EN))
        advanceUntilIdle()
        assertEquals(Lang.EN, requests.single().lang)
        assertEquals(listOf(Lang.EN), voice.langs)
        assertEquals(Lang.EN, vm.state.value.lang)
        assertFalse(vm.state.value.offline)
    }
}
