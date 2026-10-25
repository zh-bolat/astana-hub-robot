package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
    private val connections = mutableListOf<FakeLiveConnection>()
    private val sinks = mutableListOf<FakeAudioSink>()
    private val metrics = mutableListOf<TurnMetrics>()
    private val chatRequests = mutableListOf<ChatRequest>()
    private var liveAvailable = true

    private val conn: FakeLiveConnection get() = connections.last()

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
            chatMode = { mode }, presence = presence, liveFactory = factory(),
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
        assertTrue(conn.framesOfType("state").first().contains("\"navigating\":true"))
        assertFalse(voice.spoken.any { it.contains("Провожаю") || it.contains("Иду") })
        advanceTimeBy(2_000); runCurrent()
        assertTrue(conn.framesOfType("state").last().contains("\"navigating\":false"))
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
