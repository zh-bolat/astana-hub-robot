package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import kz.astanahub.robot.backend.TurnMetrics
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.live.FakeAudioSink
import kz.astanahub.robot.live.FakeLiveConnection
import kz.astanahub.robot.live.FakeLiveMicrophone
import kz.astanahub.robot.live.LiveEvent
import kz.astanahub.robot.live.LiveSession
import kz.astanahub.robot.live.LiveSessionFactory
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavJournal
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

/** Живой режим на экране: события WS /v1/live → лицо и экран, действия, сбой → agent и возврат, сон. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLiveTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val pausedHistory = mutableListOf<Boolean>()
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
            delay(100)
            activity.value = VoiceActivity.IDLE
            return true
        }

        override fun stopSpeaking() = Unit
        override fun setLanguage(lang: Lang) = Unit
        override fun setListeningPaused(paused: Boolean) {
            pausedHistory += paused
        }

        val paused: Boolean get() = pausedHistory.lastOrNull() ?: false
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var camera: CameraGateway
    private lateinit var robot: RobotGateway
    private lateinit var voice: TestVoice
    private var mode = ChatMode.LIVE
    private val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 8)
    private val foreground = MutableStateFlow(true)
    private val connections = mutableListOf<FakeLiveConnection>()
    private val sinks = mutableListOf<FakeAudioSink>()
    private val metrics = mutableListOf<TurnMetrics>()
    private val chatRequests = mutableListOf<ChatRequest>()
    private var liveAvailable = true

    private val conn: FakeLiveConnection get() = connections.last()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        NavJournal.clear()
        backend = mockk(relaxed = true)
        camera = mockk()
        voice = TestVoice()
        robot = mockk(relaxed = true) {
            every { connected } returns MutableStateFlow(true)
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
            coEvery { navigateTo(any(), any()) } coAnswers { delay(1_000); NavigationResult.Arrived }
        }
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Что такое Astana Hub?")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
        coEvery { backend.postLocalMessage(any(), any()) } returns ApiResult.Success(Unit)
        coEvery { backend.postTurnMetrics(any()) } coAnswers { metrics += firstArg<TurnMetrics>(); ApiResult.Success(Unit) }
        every { backend.chat(any()) } answers {
            chatRequests += firstArg<ChatRequest>()
            flow {
                emit(ChatEvent.Phrase("Ответ агента."))
                emit(ChatEvent.Done("req", Lang.RU))
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pcm(ms: Int) = ByteArray(ms * 32) { 1 }

    private fun TestScope.factory() = LiveSessionFactory { scope, _, _ ->
        val c = FakeLiveConnection()
        val sink = FakeAudioSink({ testScheduler.currentTime })
        LiveSession(
            parent = scope,
            connect = { if (liveAvailable) c.also { connections += it } else null },
            microphone = FakeLiveMicrophone(frames = 0),
            sink = sink.also { sinks += it },
            clock = { testScheduler.currentTime },
            confined = dispatcher,
            io = dispatcher,
        )
    }

    private fun TestScope.vm(screensaverMillis: Long? = null): MainViewModel {
        val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
        val wf = WayFinder(PlaceRepository(backend, robot, file, dispatcher), robot)
        return MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, screensaverMillis = screensaverMillis,
            chatMode = { mode }, presence = presence, liveFactory = factory(), foreground = foreground,
        ).also { runCurrent(); advanceTimeBy(100); runCurrent() }
    }

    @Test
    fun `default agent mode does not open live`() = runTest(dispatcher) {
        mode = ChatMode.AGENT
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        advanceTimeBy(500); runCurrent()
        assertTrue(connections.isEmpty())
        assertFalse(vm.liveOpen)
        assertEquals(1, chatRequests.size) // приветствие агентом через /v1/chat
    }

    @Test
    fun `visitor arrives - live session with hello and visitor_arrived, ordinary listening paused`() = runTest(dispatcher) {
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        runCurrent()
        assertTrue(vm.liveOpen)
        assertEquals(1, connections.size)
        assertTrue(conn.texts.first().contains("\"type\":\"hello\""))
        assertEquals(1, conn.framesOfType("visitor_arrived").size)
        assertTrue(voice.paused)
        assertTrue("no greeting via /v1/chat in live", chatRequests.isEmpty())
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `full turn - hearing, thinking, subtitles, speaking, playback_done and live metrics`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserSpeaking)
        conn.server(LiveEvent.UserPartial("Где здесь"))
        runCurrent()
        assertEquals("Где здесь", vm.state.value.partialQuestion)
        assertEquals(Phase.LISTENING, vm.state.value.phase)
        conn.server(LiveEvent.UserFinal("Где здесь кофе?", Lang.RU))
        runCurrent()
        assertEquals("Где здесь кофе?", vm.state.value.question)
        assertNull(vm.state.value.partialQuestion)
        conn.server(LiveEvent.Thinking)
        runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        advanceTimeBy(300)
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Emotion("happy"))
        conn.server(LiveEvent.Phrase("Кафе на первом этаже."))
        conn.server(LiveEvent.Audio(pcm(300)))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        runCurrent()
        assertEquals(Emotion.HAPPY, vm.state.value.emotion)
        assertEquals("Кафе на первом этаже.", vm.state.value.subtitle)
        assertEquals(Phase.SPEAKING, vm.state.value.phase)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, conn.framesOfType("playback_done").size)
        assertTrue(vm.state.value.phase != Phase.SPEAKING)
        assertEquals("Кафе на первом этаже.", vm.state.value.answer)
        assertTrue("robot does not speak itself in live", voice.spoken.isEmpty())
        advanceTimeBy(100); runCurrent()
        val m = metrics.single()
        assertEquals(TurnMetrics.MODE_LIVE, m.mode)
        assertEquals("t1", m.requestId)
        assertEquals(300L, m.firstAudioMs)
        assertEquals(0L, m.chatFirstEventMs) // thinking пришёл сразу за user_final
        assertEquals(300L, m.firstPhraseMs)
        assertNull(m.vadSilenceMs)
        assertFalse(m.interrupted)
    }

    @Test
    fun `guide_to starts navigation only after playback_done, without its own start phrase`() = runTest(dispatcher) {
        vm()
        conn.server(LiveEvent.UserFinal("Проводи в кафе", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        conn.server(LiveEvent.Phrase("Провожаю, идите за мной."))
        conn.server(LiveEvent.Audio(pcm(500)))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(300); runCurrent()
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, conn.framesOfType("playback_done").size)
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertTrue(navStates().first().contains("\"navigating\":true"))
        assertFalse(voice.spoken.any { it.contains("Провожаю") || it.contains("Иду") })
        advanceTimeBy(2_000); runCurrent()
        assertTrue(navStates().last().contains("\"navigating\":false"))
    }

    /** Кадры `state` про поездку (без `state.directions`). */
    private fun navStates() = conn.framesOfType("state").filter { it.contains("\"navigating\"") }

    /** Кадры `state.directions` (фразы «где X» для сервера). */
    private fun directionFrames() = conn.framesOfType("state").filter { it.contains("\"directions\"") }

    @Test
    fun `live ready - robot sends where-is phrases for every base place on its map, keyed by place_id`() = runTest(dispatcher) {
        coEvery { robot.getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 15.0), MapPlace("Склад", 2.0, 2.0))
        vm()
        val frame = directionFrames().single()
        assertEquals(
            """{"type":"state","directions":{"cafe":{"ru":"Кафе — справа от вас, примерно в 15 метрах.",""" +
                """"kk":"Кафе сіздің оң жағыңызда, шамамен 15 метр жерде.","en":"Cafe is on your right, about 15 meters away."}}}""",
            frame,
        )
        // После hello (сервер уже завёл сессию), без «проводить» — его добавит сервер.
        assertTrue(conn.texts.indexOf(frame) > conn.texts.indexOfFirst { it.contains("\"type\":\"hello\"") })
        assertFalse(frame.contains("провод", ignoreCase = true) || frame.contains("take you", ignoreCase = true))
        assertTrue(NavJournal.texts().toString(), NavJournal.texts().contains("live directions → сервер: мест 1, причина=start"))
    }

    @Test
    fun `live directions - fresh set after every trip, whatever the outcome`() = runTest(dispatcher) {
        vm()
        assertEquals(1, directionFrames().size)
        coEvery { robot.getPosition() } returns RobotPose(0.0, 12.0, 0.0) // доехали до кафе
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        advanceTimeBy(2_000); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertEquals(2, directionFrames().size)
        assertTrue(directionFrames().last(), directionFrames().last().contains("Кафе совсем рядом"))
        assertTrue(NavJournal.texts().contains("live directions → сервер: мест 1, причина=after_trip"))

        // Поездка не удалась — фразы всё равно обновляются.
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(500); NavigationResult.Failed(-1, "busy") }
        coEvery { robot.getPosition() } returns RobotPose(0.0, 6.0, 0.0)
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, directionFrames().size)
        assertTrue(directionFrames().last(), directionFrames().last().contains("примерно в 6 метрах"))
    }

    @Test
    fun `live directions - no robot position, no frame, and the session keeps working`() = runTest(dispatcher) {
        coEvery { robot.getPosition() } returns null
        val vm = vm()
        assertTrue(directionFrames().isEmpty())
        assertTrue(vm.liveOpen)
        assertTrue(NavJournal.texts().contains("live directions (start): нет позиции робота — не шлю"))
        // getPosition падает (RobotApi) — тоже без кадра и без сбоя сессии.
        coEvery { robot.getPosition() } throws IllegalStateException("RobotApi died")
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        advanceTimeBy(2_000); runCurrent()
        assertTrue(directionFrames().isEmpty())
        assertTrue(vm.liveOpen)
        assertTrue(conn.framesOfType("hello").size == 1 && !conn.closed)
    }

    @Test
    fun `live directions - periodic check sends only when the robot moved at least half a meter or turned 20 degrees`() = runTest(dispatcher) {
        val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
        val vm = MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime },
            wayFinder = WayFinder(PlaceRepository(backend, robot, file, dispatcher), robot), robot = robot,
            chatMode = { mode }, presence = presence, liveFactory = factory(), foreground = foreground,
            liveDirectionsPeriodMillis = 15_000,
        )
        runCurrent()
        assertEquals(1, directionFrames().size) // start
        coEvery { robot.getPosition() } returns RobotPose(0.3, 0.0, Math.toRadians(10.0))
        advanceTimeBy(15_001); runCurrent()
        assertEquals("small shift - nothing new", 1, directionFrames().size)
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, Math.toRadians(25.0))
        advanceTimeBy(15_000); runCurrent()
        assertEquals("turned 25 degrees", 2, directionFrames().size)
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.6, Math.toRadians(25.0))
        advanceTimeBy(15_000); runCurrent()
        assertEquals("moved 0.6 m", 3, directionFrames().size)
        assertTrue(NavJournal.texts().contains("live directions → сервер: мест 1, причина=moved"))
        advanceTimeBy(15_000); runCurrent()
        assertEquals("standing still", 3, directionFrames().size)
        // Приложение ушло с экрана — live закрыт, проверки остановлены.
        foreground.value = false
        runCurrent()
        assertFalse(vm.liveOpen)
        coEvery { robot.getPosition() } returns RobotPose(5.0, 5.0, 0.0)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, directionFrames().size)
    }

    @Test
    fun `turn_end without an answer in the knowledge base makes the face confused`() = runTest(dispatcher) {
        val vm = vm()
        val cues = mutableListOf<kz.astanahub.robot.ui.face.FaceCue>()
        backgroundScope.launch { vm.faceCues.collect { cues += it } }
        runCurrent()
        conn.server(LiveEvent.UserFinal("Сколько звёзд на небе?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Phrase("Не знаю, спросите на ресепшене."))
        conn.server(LiveEvent.Audio(pcm(300)))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, false))
        runCurrent()
        assertEquals(listOf(kz.astanahub.robot.ui.face.FaceCue.CONFUSED), cues)
    }

    /** Ход сервера, как его шлёт настоящий сервер: turn_start → phrase → звук → turn_end. */
    private fun serverTurn(turnId: String, phrase: String, audioMs: Int = 300) {
        conn.server(LiveEvent.TurnStart(turnId))
        conn.server(LiveEvent.Phrase(phrase))
        conn.server(LiveEvent.Audio(pcm(audioMs)))
        conn.server(LiveEvent.TurnEnd(turnId, Lang.RU, true))
    }

    @Test
    fun `real order - guide_to with turn_id after playback_done starts navigation at once`() = runTest(dispatcher) {
        vm()
        conn.server(LiveEvent.UserFinal("Проводи в кафе", Lang.RU))
        serverTurn("t1", "Провожаю, идите за мной.")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, conn.framesOfType("playback_done").size)
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        // Сервер шлёт guide_to ПОСЛЕ playback_done этого хода.
        conn.server(LiveEvent.Action("guide_to", "cafe", "t1"))
        runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertTrue(NavJournal.texts().any { it.contains("turn=t1") && it.contains("еду сразу") })
        assertFalse("no own start phrase - the server said it", voice.spoken.any { it.contains("Провожу") })
    }

    @Test
    fun `real order - guide_to without turn_id after playback_done (old server) starts navigation at once`() = runTest(dispatcher) {
        vm()
        conn.server(LiveEvent.UserFinal("Проводи в кафе", Lang.RU))
        serverTurn("t1", "Провожаю, идите за мной.")
        advanceTimeBy(1_000); runCurrent()
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertTrue(NavJournal.texts().any { it.contains("без turn_id") })
    }

    @Test
    fun `guide_to without turn_id while audio plays waits for playback_done`() = runTest(dispatcher) {
        vm()
        conn.server(LiveEvent.UserFinal("Проводи в кафе", Lang.RU))
        conn.server(LiveEvent.TurnStart(null))
        conn.server(LiveEvent.Phrase("Провожаю, идите за мной."))
        conn.server(LiveEvent.Audio(pcm(600)))
        advanceTimeBy(300); runCurrent()
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        conn.server(LiveEvent.TurnEnd(null, Lang.RU, true))
        runCurrent()
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        advanceTimeBy(1_500); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
    }

    @Test
    fun `guide_to of an unknown turn while nothing plays starts at once`() = runTest(dispatcher) {
        vm()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t9"))
        runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
    }

    @Test
    fun `repeated guide_to to the same place during the trip is ignored`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(10_000); NavigationResult.Arrived }
        vm()
        serverTurn("t1", "Провожаю.")
        advanceTimeBy(1_000); runCurrent()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t1"))
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        serverTurn("t2", "Идём, идём.")
        advanceTimeBy(1_000); runCurrent()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t2"))
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        verify(exactly = 0) { robot.stopNavigation() }
        assertTrue(NavJournal.texts().any { it.contains("turn=t2") && it.contains("игнор") })
    }

    @Test
    fun `local yes to the guide offer - drives at once, server guide_to for the same yes is ignored`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { delay(5_000); NavigationResult.Arrived }
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Где кафе?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("where_is", "cafe", "t1"))
        conn.server(LiveEvent.Phrase("Кафе впереди. Проводить вас?"))
        conn.server(LiveEvent.Audio(pcm(300)))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(1_000); runCurrent()
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        // Посетитель: «да» — едем сразу, сервер ещё ничего не прислал.
        conn.server(LiveEvent.UserFinal("Да, давай", Lang.RU))
        runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        assertNull(vm.state.value.guideTarget)
        assertTrue("no local voice on start in live", voice.spoken.isEmpty())
        // Сервер ответил на то же «да» своим ходом и guide_to — дубликат.
        serverTurn("t2", "Провожаю, идите за мной.")
        advanceTimeBy(1_000); runCurrent()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t2"))
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo(any(), any()) }
        assertTrue(vm.state.value.phase != Phase.NAVIGATING)
    }

    @Test
    fun `local yes after a short trip already ended - server guide_to for it still ignored`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } returns NavigationResult.AlreadyThere
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Где кафе?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("where_is", "cafe", "t1"))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(500); runCurrent()
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        conn.server(LiveEvent.UserFinal("иә", Lang.KK))
        advanceTimeBy(1_000); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        conn.server(LiveEvent.Action("guide_to", "cafe", "t2"))
        advanceTimeBy(1_000); runCurrent()
        coVerify(exactly = 1) { robot.navigateTo(any(), any()) }
    }

    @Test
    fun `yes without a guide offer on screen is an ordinary utterance`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("да", Lang.RU))
        runCurrent()
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        assertEquals("да", vm.state.value.question)
    }

    @Test
    fun `navigating phase is reset after the trip and the arrival phrase`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t9"))
        runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(voice.spoken.any { it.contains("пришли") })
        assertTrue("phase after trip: ${vm.state.value.phase}", vm.state.value.phase != Phase.NAVIGATING)
        assertNull(vm.state.value.guidingTo)
        assertTrue(NavJournal.texts().any { it.contains("итог приехали") })
    }

    @Test
    fun `trip without any result from the robot ends by timeout, phase reset, stopNavigation sent`() = runTest(dispatcher) {
        coEvery { robot.navigateTo(any(), any()) } coAnswers { awaitCancellation() }
        val vm = vm()
        conn.server(LiveEvent.Action("guide_to", "cafe", "t9"))
        runCurrent()
        assertEquals(Phase.NAVIGATING, vm.state.value.phase)
        advanceTimeBy(MainViewModel.MAX_TRIP_MILLIS + 1_000); runCurrent()
        verify { robot.stopNavigation() }
        assertTrue(vm.state.value.phase != Phase.NAVIGATING)
        assertNull(vm.state.value.guidingTo)
        assertTrue(NavJournal.texts().any { it.contains("итог ошибка") })
    }

    @Test
    fun `app leaves the screen - live closed and listening paused, back on screen - live again`() = runTest(dispatcher) {
        mode = ChatMode.AGENT
        val vm = vm()
        assertFalse(voice.paused)
        foreground.value = false
        runCurrent()
        assertTrue("ordinary listening paused in background", voice.paused)
        foreground.value = true
        runCurrent()
        assertFalse(voice.paused)

        mode = ChatMode.LIVE
        vm.onSettingsChanged()
        runCurrent()
        assertTrue(vm.liveOpen)
        val first = conn
        foreground.value = false
        runCurrent()
        assertFalse(vm.liveOpen)
        assertTrue(first.closed)
        assertTrue(voice.paused)
        // В фоне подошедший посетитель не будит live и не получает приветствие.
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        advanceTimeBy(500); runCurrent()
        assertFalse(vm.liveOpen)
        assertTrue(chatRequests.isEmpty())
        foreground.value = true
        runCurrent()
        assertTrue(vm.liveOpen)
        assertEquals(2, connections.size)
        assertTrue("live owns the microphone", voice.paused)
        presence.emit(PresenceEvent.Left)
        runCurrent()
    }

    @Test
    fun `interrupted turn drops its guide_to and clears subtitles`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Проводи в кафе", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("guide_to", "cafe"))
        conn.server(LiveEvent.Phrase("Провожаю…"))
        conn.server(LiveEvent.Audio(pcm(800)))
        advanceTimeBy(200); runCurrent()
        conn.server(LiveEvent.Interrupted("t1"))
        runCurrent()
        assertNull(vm.state.value.subtitle)
        assertTrue(vm.state.value.phase != Phase.SPEAKING)
        advanceTimeBy(3_000); runCurrent()
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
        assertTrue(metrics.single().interrupted)
    }

    @Test
    fun `ignored - no reaction, screen stays as it was`() = runTest(dispatcher) {
        val vm = vm()
        val before = vm.state.value
        conn.server(LiveEvent.UserFinal("ну", Lang.RU))
        conn.server(LiveEvent.Ignored)
        runCurrent()
        val after = vm.state.value
        assertEquals(before.screen, after.screen)
        assertNull(after.question)
        assertEquals(Emotion.NEUTRAL, after.emotion)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(metrics.isEmpty())
        assertTrue(chatRequests.isEmpty())
    }

    @Test
    fun `ignored after thinking - face stops thinking`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("ну", Lang.RU))
        conn.server(LiveEvent.Thinking)
        runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        conn.server(LiveEvent.Ignored)
        runCurrent()
        assertTrue(vm.state.value.phase != Phase.THINKING)
    }

    @Test
    fun `live failed, robot slept and woke within the pause - retry is rescheduled`() = runTest(dispatcher) {
        val vm = vm(screensaverMillis = 10_000)
        conn.server(LiveEvent.Closed(BackendError(ErrorKind.NETWORK)))
        runCurrent()
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        runCurrent()
        presence.emit(PresenceEvent.Left)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        advanceTimeBy(1_000)
        presence.emit(PresenceEvent.Arrived(2, greet = false))
        runCurrent()
        assertFalse(vm.liveOpen)
        advanceTimeBy(MainViewModel.LIVE_RETRY_MILLIS); runCurrent()
        assertTrue(vm.liveOpen)
        assertEquals(2, connections.size)
        presence.emit(PresenceEvent.Left) // иначе таймер заставки перезапускается бесконечно (человек рядом)
        advanceTimeBy(10_001); runCurrent()
    }

    @Test
    fun `where_is - direction shown on screen, robot says nothing itself`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Где кафе?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("where_is", "cafe"))
        advanceTimeBy(100); runCurrent()
        assertTrue(vm.state.value.wayHint.orEmpty(), vm.state.value.wayHint.orEmpty().contains("Кафе"))
        assertEquals("cafe", vm.state.value.guideTarget?.id)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `connection drop - pending question goes to agent, live comes back in 30 s`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Что такое Astana Hub?", Lang.RU))
        runCurrent()
        conn.server(LiveEvent.Closed(BackendError(ErrorKind.NETWORK)))
        runCurrent()
        assertFalse(vm.liveOpen)
        assertFalse("ordinary listening resumed", voice.paused)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Что такое Astana Hub?", chatRequests.single().question)
        assertEquals(listOf("Ответ агента."), voice.spoken)
        assertEquals(1, connections.size)
        advanceTimeBy(MainViewModel.LIVE_RETRY_MILLIS); runCurrent()
        assertEquals(2, connections.size)
        assertTrue(vm.liveOpen)
        assertTrue(voice.paused)
        assertTrue("no visitor_arrived on reconnect", conn.framesOfType("visitor_arrived").isEmpty())
    }

    @Test
    fun `live unavailable on arrival - greeting through agent, retry later`() = runTest(dispatcher) {
        liveAvailable = false
        val vm = vm()
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        advanceTimeBy(500); runCurrent()
        assertFalse(vm.liveOpen)
        assertEquals("visitor_arrived", chatRequests.single().event)
        liveAvailable = true
        advanceTimeBy(MainViewModel.LIVE_RETRY_MILLIS); runCurrent()
        assertTrue(vm.liveOpen)
    }

    @Test
    fun `server error during a playing answer - audio stops, fallback`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Расскажи", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Audio(pcm(800)))
        advanceTimeBy(200); runCurrent()
        assertEquals(Phase.SPEAKING, vm.state.value.phase)
        conn.server(LiveEvent.Error(BackendError(ErrorKind.LLM_UNAVAILABLE)))
        runCurrent()
        assertFalse(vm.liveOpen)
        assertTrue(conn.closed)
        assertFalse(sinks.last().playing)
        assertTrue(vm.state.value.phase != Phase.SPEAKING)
        assertTrue("answer had started - not repeated through agent", chatRequests.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertTrue(metrics.single().fallback)
    }

    @Test
    fun `visitor leaves - screen stays awake, idle timer then sleeps and closes the live session`() = runTest(dispatcher) {
        val vm = vm(screensaverMillis = 10_000)
        presence.emit(PresenceEvent.Arrived(1, greet = true))
        runCurrent()
        val c = conn
        conn.server(LiveEvent.Listening)
        runCurrent()
        presence.emit(PresenceEvent.Left)
        runCurrent()
        assertEquals(ScreenMode.FACE, vm.state.value.mode) // уход посетителя сам по себе не усыпляет
        assertTrue(vm.liveOpen)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertTrue(c.closed)
        assertFalse(vm.liveOpen)
        // Во сне повтор не открывает live.
        advanceTimeBy(MainViewModel.LIVE_RETRY_MILLIS * 2); runCurrent()
        assertEquals(1, connections.size)
    }

    @Test
    fun `stop button - interrupt frame and silence`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Audio(pcm(800)))
        advanceTimeBy(200); runCurrent()
        vm.stop()
        runCurrent()
        assertEquals(1, conn.framesOfType("interrupt").size)
        assertFalse(sinks.last().playing)
    }

    @Test
    fun `switching chat mode away from live closes the session`() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.liveOpen)
        mode = ChatMode.AGENT
        vm.onSettingsChanged()
        runCurrent()
        assertFalse(vm.liveOpen)
        assertTrue(conn.closed)
        assertFalse(voice.paused)
    }

    @Test
    fun `look - frame goes to look, description shown, spoken locally only if server does not voice it`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1, 2, 3)
        coEvery { backend.look(any(), any(), any()) } returns ApiResult.Success(Answer("r", "Это коворкинг.", listOf("Это коворкинг."), Lang.RU, true))
        val vm = vm()
        conn.server(LiveEvent.UserFinal("Что это?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("look", null))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(100); runCurrent()
        coVerify { backend.look(any(), "Что это?", any()) }
        assertEquals("Это коворкинг.", vm.state.value.answer)
        // Сервер продолжил ход сам — робот молчит.
        conn.server(LiveEvent.TurnStart("t2"))
        conn.server(LiveEvent.Phrase("Это коворкинг."))
        advanceTimeBy(MainViewModel.LIVE_LOOK_SPEAK_FALLBACK_MILLIS + 500); runCurrent()
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `look - server stays silent, robot voices the description itself`() = runTest(dispatcher) {
        coEvery { camera.captureJpeg(any()) } returns byteArrayOf(1, 2, 3)
        coEvery { backend.look(any(), any(), any()) } returns ApiResult.Success(Answer("r", "Это коворкинг.", listOf("Это коворкинг."), Lang.RU, true))
        vm()
        conn.server(LiveEvent.UserFinal("Что это?", Lang.RU))
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Action("look", null))
        conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(MainViewModel.LIVE_LOOK_SPEAK_FALLBACK_MILLIS + 500); runCurrent()
        assertEquals(listOf("Это коворкинг."), voice.spoken)
    }

    @Test
    fun `text question while live is open goes to the agent and stops live audio`() = runTest(dispatcher) {
        val vm = vm()
        conn.server(LiveEvent.TurnStart("t1"))
        conn.server(LiveEvent.Audio(pcm(800)))
        advanceTimeBy(200); runCurrent()
        vm.submitButton("Что такое Astana Hub?")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, conn.framesOfType("interrupt").size)
        assertEquals("Что такое Astana Hub?", chatRequests.single().question)
        assertTrue(vm.liveOpen)
        advanceUntilIdle()
    }
}
