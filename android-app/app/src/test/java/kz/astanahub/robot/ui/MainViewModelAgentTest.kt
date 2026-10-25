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
import kz.astanahub.robot.chat.ChatAction
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

        /** Сколько непрерывных озвучек (speakStream) открыто за ход. */
        var streams = 0

        override suspend fun speakStream(
            phrases: kotlinx.coroutines.channels.ReceiveChannel<String>,
            lang: Lang,
            listener: kz.astanahub.robot.voice.SpeechListener,
        ): Boolean {
            streams++
            return super.speakStream(phrases, lang, listener)
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

    private val done = ChatEvent.Done("req", Lang.RU)

    // --- события хода ---

    @Test
    fun `phrases are spoken in order, emotion shown and relaxed afterwards`() = runTest(dispatcher) {
        chat {
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
        assertEquals("Где тут можно поработать?", requests.single().question)
        assertEquals(null, requests.single().event)
        advanceUntilIdle()
        assertEquals(Emotion.NEUTRAL, vm.state.value.emotion)
        assertEquals(Phase.IDLE, vm.state.value.phase)
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    @Test
    fun `agent phrases of one turn go to the voice as one continuous stream`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Phrase("Раз."))
            delay(50)
            emit(ChatEvent.Phrase("Два."))
            delay(300) // первая и вторая уже доиграли — пауза в потоке не рвёт озвучку
            emit(ChatEvent.Phrase("Три."))
            emit(done)
        }
        val vm = vm()
        vm.submit("Расскажи")
        advanceUntilIdle()
        assertEquals(listOf("Раз.", "Два.", "Три."), voice.spoken)
        assertEquals(1, voice.streams)
        assertEquals("Раз. Два. Три.", vm.state.value.answer)
    }

    @Test
    fun `where_is action - robot speaks the direction before the agent phrase, not duplicated in history`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Action(ChatAction("where_is", "cafe")))
            emit(ChatEvent.Phrase("Могу проводить."))
            emit(done.copy(actions = listOf(ChatAction("where_is", "cafe"))))
        }
        val vm = vm()
        vm.submit("Где поесть?")
        advanceUntilIdle()
        assertEquals(voice.spoken.toString(), "Могу проводить.", voice.spoken.last())
        assertTrue(voice.spoken.toString(), voice.spoken.first().contains("Кафе"))
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        coVerify(exactly = 0) { backend.postLocalMessage(any(), any()) }
    }

    @Test
    fun `yes after the robot offered to guide - drives at once, even while the robot is still speaking`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Action(ChatAction("where_is", "cafe")))
            emit(ChatEvent.Phrase("Кафе справа. Проводить вас туда?"))
            delay(3_000) // ответ ещё идёт — робот «занят», короткие реплики обычно отбрасываются
            emit(done.copy(actions = listOf(ChatAction("where_is", "cafe"))))
        }
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(1_000); NavigationResult.Arrived }
        val vm = vm()
        vm.submit("Где поесть?")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        voice.asr.emit(Transcript("Да, проводи", isFinal = true))
        runCurrent()
        coVerify(exactly = 1) { robot.navigateTo(any(), any()) }
        advanceUntilIdle()
        coVerify(exactly = 1) { backend.chat(any()) } // «да» не ушло на сервер — поездка началась локально
    }

    @Test
    fun `short command while the robot speaks is not dropped`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Phrase("Добро пожаловать в Astana Hub, рад вас видеть!"))
            delay(3_000)
            emit(done)
        }
        val vm = vm()
        vm.submit("Привет")
        advanceTimeBy(500); runCurrent()
        voice.asr.emit(Transcript("Где кафе", isFinal = true)) // 2 слова, но это команда роботу
        advanceUntilIdle()
        coVerify(exactly = 2) { backend.chat(any()) }
    }

    @Test
    fun `where_is for a place the robot does not know - silent, only the agent phrase`() = runTest(dispatcher) {
        chat {
            emit(ChatEvent.Action(ChatAction("where_is", "mars")))
            emit(ChatEvent.Phrase("Это на третьем этаже."))
            emit(done)
        }
        vm().submit("Где Марс?")
        advanceUntilIdle()
        assertEquals(listOf("Это на третьем этаже."), voice.spoken)
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
    fun `guide_to action - robot drives only after the last phrase, head stops following while driving`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(5_000); NavigationResult.Arrived }
        chat {
            emit(ChatEvent.Action(ChatAction("guide_to", "cafe")))
            emit(ChatEvent.Phrase("Провожаю вас в кафе."))
            emit(ChatEvent.Phrase("Идите за мной."))
            emit(done)
        }
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(9, greet = false))
        runCurrent()
        vm.submit("Да, проводи")
        advanceTimeBy(150); runCurrent()
        // первая фраза доиграла, вторая ещё звучит — не едем
        assertEquals(listOf("Провожаю вас в кафе.", "Идите за мной."), voice.spoken)
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertEquals("Кафе", vm.state.value.guidingTo)
        // агент уже сказал «Провожаю» — своя фраза старта не звучит
        assertEquals(listOf("Провожаю вас в кафе.", "Идите за мной."), voice.spoken)
        assertEquals(listOf("follow:9", "stop"), headGateway.calls)
        advanceUntilIdle()
        coVerify { robot.navigateTo("Кафе", any()) }
        assertEquals(listOf("follow:9", "stop", "follow:9"), headGateway.calls)
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `look action - frame goes to v1 look with the visitor question, description spoken after agent phrases`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1, 2, 3)
        coEvery { backend.look(any(), any(), any()) } coAnswers {
            delay(1_000); ApiResult.Success(Answer("r", "Вижу стенд стартапов.", listOf("Вижу стенд стартапов."), Lang.RU, true))
        }
        chat {
            emit(ChatEvent.Emotion("thinking"))
            emit(ChatEvent.Action(ChatAction("look")))
            emit(ChatEvent.Phrase("Сейчас посмотрю."))
            emit(done)
        }
        val vm = vm()
        vm.lookAround()
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.LOOKING, vm.state.value.phase)
        advanceUntilIdle()
        coVerify { backend.look(byteArrayOf(1, 2, 3), "Что это?", any()) }
        assertEquals(listOf("Сейчас посмотрю.", "Вижу стенд стартапов."), voice.spoken)
        assertEquals("Сейчас посмотрю. Вижу стенд стартапов.", vm.state.value.answer)
        assertEquals("Что это?", requests.single().question)
    }

    @Test
    fun `look action - no camera or look failure gives a ready phrase`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns null
        chat {
            emit(ChatEvent.Action(ChatAction("look")))
            emit(ChatEvent.Phrase("Сейчас посмотрю."))
            emit(done)
        }
        val vm = vm()
        vm.lookAround()
        advanceUntilIdle()
        assertEquals(listOf("Сейчас посмотрю.", phrases.get(PhraseKey.CAMERA_UNAVAILABLE, Lang.RU)), voice.spoken)
        coVerify(exactly = 0) { backend.look(any(), any(), any()) }

        voice.spoken.clear()
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1)
        coEvery { backend.look(any(), any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.LLM_UNAVAILABLE))
        vm.submit("Что ты видишь перед собой?")
        advanceUntilIdle()
        assertEquals(listOf("Сейчас посмотрю.", phrases.get(PhraseKey.NO_CONNECTION, Lang.RU)), voice.spoken)
    }

    // --- запасной путь ---

    @Test
    fun `no phrase or action in 8 s - fallback to ask`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Emotion("thinking")); awaitCancellation() }
        val vm = vm(withMap = false)
        vm.submit("Что такое Astana Hub?")
        advanceTimeBy(7_900); runCurrent()
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        advanceUntilIdle()
        coVerify { backend.ask("Что такое Astana Hub?", any()) }
        assertEquals(listOf("Ответ ask."), voice.spoken)
    }

    @Test
    fun `error event before a reply - fallback to local rules`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Error(BackendError(ErrorKind.LLM_UNAVAILABLE))) }
        val vm = vm()
        vm.submit("Где кафе?")
        advanceUntilIdle()
        assertTrue(voice.spoken.joinToString(" ").contains("Кафе"))
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        coVerify { backend.postLocalMessage(any(), match { it.kind == "where_is" }) }
    }

    @Test
    fun `server without chat (404) - fallback now and ask directly afterwards`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Error(BackendError(ErrorKind.UNKNOWN, httpStatus = 404, serverCode = "not_found"))) }
        val vm = vm(withMap = false)
        vm.submit("Что такое Astana Hub?")
        advanceUntilIdle()
        vm.submit("Во сколько открывается хаб?")
        advanceUntilIdle()
        assertEquals(1, requests.size)
        coVerify(exactly = 2) { backend.ask(any(), any()) }
    }

    @Test
    fun `offline - local rules right away, no chat request`()= runTest(dispatcher) {
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
                    emit(ChatEvent.Phrase("Длинный ответ, часть первая."))
                    awaitCancellation()
                }.onCompletion { if (it != null) cancelled++ }
            } else {
                flow { emit(ChatEvent.Phrase("Второй.")); emit(done) }
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
        assertEquals("", req.question)
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
        chat { delay(5_000); emit(ChatEvent.Phrase("Поздно")); emit(done) }
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
    fun `visitor left - head reset, screen stays awake - but not while driving`() = runTest(dispatcher) {
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
        assertEquals(ScreenMode.FACE, vm.state.value.mode) // не усыпляет: мешало входу в диагностику
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
        chat { emit(ChatEvent.Phrase("Hello!")); emit(ChatEvent.Done("r", Lang.EN)) }
        val vm = vm()
        voice.asr.emit(Transcript("Hi there, what is this place?", isFinal = true, lang = Lang.EN))
        advanceUntilIdle()
        assertEquals(Lang.EN, requests.single().lang)
        assertEquals(listOf(Lang.EN), voice.langs)
        assertEquals(Lang.EN, vm.state.value.lang)
        assertFalse(vm.state.value.offline)
    }
}
