package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.NoiseStats
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Шум вместо реплики: фильтр во всех фазах (и в покое), «да» только после вопроса робота, место одним словом,
 * сон без человека рядом не будится голосом, `done.ignored` — тишина без запасного пути.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelNoiseTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 16)
        val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val pausedHistory = mutableListOf<Boolean>()
        override val transcripts: Flow<Transcript> = asr
        override val utteranceEnded: Flow<Unit> = ended
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
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var robot: RobotGateway
    private lateinit var voice: TestVoice
    private val presence = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 8)
    private val requests = mutableListOf<ChatRequest>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        NoiseStats.reset()
        backend = mockk(relaxed = true)
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
        chat { emit(ChatEvent.Phrase("Ответ агента.")); emit(ChatEvent.Done("req", Lang.RU)) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun chat(block: suspend FlowCollector<ChatEvent>.(ChatRequest) -> Unit) {
        every { backend.chat(any()) } answers {
            val req = firstArg<ChatRequest>()
            requests += req
            flow { block(req) }
        }
    }

    private fun TestScope.vm(screensaverMillis: Long? = null, withPresence: Boolean = true): MainViewModel {
        val file = java.io.File.createTempFile("places", ".json").apply { delete(); deleteOnExit() }
        val wf = WayFinder(PlaceRepository(backend, robot, file, dispatcher), robot)
        return MainViewModel(
            backend, voice, mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            wayFinder = wf, robot = robot, screensaverMillis = screensaverMillis,
            chatMode = { ChatMode.AGENT }, presence = if (withPresence) presence else null,
        ).also { if (screensaverMillis == null) advanceUntilIdle() else runCurrent() }
    }

    private suspend fun TestScope.say(text: String) {
        voice.asr.emit(Transcript(text, isFinal = true))
        advanceTimeBy(50); runCurrent()
    }

    @Test
    fun `noise in idle is not sent and the face does not react`() = runTest(dispatcher) {
        val vm = vm()
        listOf("Ну…", "Смеётся.", "М-м-м, не-не-не, да ну его!", "Да, я.", "(смеётся)", "[музыка]", "угу", "Слушай").forEach {
            say(it)
            assertEquals(it, Phase.IDLE, vm.state.value.phase)
            assertEquals(it, Emotion.NEUTRAL, vm.state.value.emotion)
            assertNull(it, vm.state.value.question)
        }
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertTrue(voice.spoken.isEmpty())
        assertEquals(8, NoiseStats.filteredUtterances)
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    @Test
    fun `noise partial does not wake, show subtitles or start thinking`() = runTest(dispatcher) {
        val vm = vm()
        voice.asr.emit(Transcript("Ну…", isFinal = false))
        runCurrent()
        assertNull(vm.state.value.partialQuestion)
        voice.ended.emit(Unit)
        runCurrent()
        assertEquals(Phase.IDLE, vm.state.value.phase)
        voice.asr.emit(Transcript("Ну…", isFinal = true))
        advanceUntilIdle()
        assertEquals(Phase.IDLE, vm.state.value.phase)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `meaningful short replies and questions are sent`() = runTest(dispatcher) {
        vm()
        listOf("Привет", "Спасибо", "Рахмет", "Hello", "Где кафе?").forEach {
            say(it)
            advanceUntilIdle()
        }
        assertEquals(listOf("Привет", "Спасибо", "Рахмет", "Hello", "Где кафе?"), requests.map { it.question })
    }

    @Test
    fun `place name in one word is sent`() = runTest(dispatcher) {
        vm()
        say("Коворкинг")
        advanceUntilIdle()
        assertEquals(listOf("Коворкинг"), requests.map { it.question })
    }

    @Test
    fun `yes is sent only right after the robot asked a question`() = runTest(dispatcher) {
        chat {
            if (it.question == "Где кафе?") emit(ChatEvent.Phrase("Кафе на первом этаже. Проводить вас?"))
            else emit(ChatEvent.Phrase("Хорошо."))
            emit(ChatEvent.Done("req", Lang.RU))
        }
        vm()
        say("да") // без вопроса робота — шум
        advanceUntilIdle()
        assertTrue(requests.isEmpty())

        say("Где кафе?")
        advanceTimeBy(2_000); runCurrent()
        say("Да")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Где кафе?", "Да"), requests.map { it.question })

        // ответ робота без вопроса — следующее «да» снова шум
        say("Иә")
        advanceUntilIdle()
        assertEquals(2, requests.size)

        // вопрос робота устарел (> 30 с) — «да» шум
        say("Где кафе?")
        advanceTimeBy(40_000); runCurrent()
        say("да")
        advanceUntilIdle()
        assertEquals(listOf("Где кафе?", "Да", "Где кафе?"), requests.map { it.question })
    }

    @Test
    fun `asleep - voice does not wake, listening paused, a person does not wake either`() = runTest(dispatcher) {
        val vm = vm(screensaverMillis = 60_000)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(true, voice.pausedHistory.last())

        voice.asr.emit(Transcript("Где тут кафе", isFinal = false))
        runCurrent()
        voice.ended.emit(Unit)
        say("Где тут кафе? Слушай, пойдём обедать")
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(Phase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.partialQuestion)
        assertTrue(requests.isEmpty())
        assertEquals(1, NoiseStats.ignoredWhileAsleep)

        // MF4: подошёл человек (PersonApi) — робот спит дальше, голос по-прежнему не слушается
        presence.emit(PresenceEvent.Arrived(1, greet = false))
        runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(true, voice.pausedHistory.last())
        say("Где кафе?")
        advanceTimeBy(2_000); runCurrent()
        assertTrue(requests.isEmpty())
        // явное действие на странице (плитка) — проснулся, голос снова слушается
        vm.wake()
        runCurrent()
        assertEquals(false, voice.pausedHistory.last())
        say("Где кафе?")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Где кафе?"), requests.map { it.question })
        advanceUntilIdle()
    }

    @Test
    fun `asleep - touch wakes and voice is heard again`() = runTest(dispatcher) {
        val vm = vm(screensaverMillis = 60_000)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        vm.wake()
        runCurrent()
        assertEquals(false, voice.pausedHistory.last())
        say("Где кафе?")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Где кафе?"), requests.map { it.question })
    }

    @Test
    fun `without PersonApi voice does not wake from sleep either`() = runTest(dispatcher) {
        val vm = vm(screensaverMillis = 60_000, withPresence = false)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertEquals(true, voice.pausedHistory.last())
        say("Где кафе?")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ScreenMode.SLEEP, vm.state.value.mode)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `server ignored the utterance - silence, no fallback, face without reaction`() = runTest(dispatcher) {
        chat { emit(ChatEvent.Done("req", Lang.RU, found = false, ignored = true)) }
        val vm = vm()
        say("Ну что ж, посмотрим потом") // локальный фильтр пропускает, сервер — шум
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertTrue(voice.spoken.isEmpty())
        coVerify(exactly = 0) { backend.ask(any(), any()) }
        assertNull(vm.state.value.question)
        assertNull(vm.state.value.answer)
        assertEquals(Emotion.NEUTRAL, vm.state.value.emotion)
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }
}
