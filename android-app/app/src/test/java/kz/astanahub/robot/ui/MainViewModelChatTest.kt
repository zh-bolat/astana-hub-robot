package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ChatAction
import kz.astanahub.robot.backend.ChatActionType
import kz.astanahub.robot.backend.ChatAnswer
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.LocalMessage
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.places.WayPhrases
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavigationResult
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Агентский чат: вопросы уходят в /v1/chat, робот выполняет действия из ответа, локальные ответы — в память агента. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelChatTest {

    @get:Rule val tmp = TemporaryFolder()

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        override val transcripts: Flow<Transcript> = MutableSharedFlow()
        override val activity = MutableStateFlow(VoiceActivity.IDLE)

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            activity.value = VoiceActivity.SPEAKING
            spoken += phrases
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
    private lateinit var robot: RobotGateway
    private lateinit var voice: TestVoice
    private val recorded = mutableListOf<LocalMessage>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        voice = TestVoice()
        camera = mockk()
        backend = mockk()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Q")))
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, null, false))
        val message = slot<LocalMessage>()
        coEvery { backend.recordLocal(capture(message)) } answers { recorded += message.captured; ApiResult.Success(Unit) }
        robot = mockk(relaxed = true) {
            coEvery { getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0), MapPlace("Ресепшен", 3.0, 0.0))
            coEvery { getPosition() } returns RobotPose(0.0, 0.0, 0.0)
            every { connected } returns MutableStateFlow(true)
            coEvery { navigateTo(any(), any()) } returns NavigationResult.Arrived
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): MainViewModel {
        val wf = WayFinder(PlaceRepository(backend, robot, java.io.File(tmp.root, "places.json"), dispatcher), robot)
        return MainViewModel(
            backend, voice, camera, phrases, clock = { testScheduler.currentTime }, wayFinder = wf, robot = robot,
        ).also { advanceUntilIdle() }
    }

    private fun chat(text: String, vararg actions: ChatAction) =
        ApiResult.Success(ChatAnswer(Answer("req", text, listOf(text), Lang.RU, true), actions.toList()))

    @Test
    fun `questions go to chat, not ask`() = runTest(dispatcher) {
        coEvery { backend.chat(any(), any()) } returns chat("Пароль на табличке у ресепшена.")
        val vm = vm()

        vm.submit("Какой пароль от Wi-Fi?")
        advanceUntilIdle()

        assertEquals("Пароль на табличке у ресепшена.", vm.state.value.answer)
        assertEquals(listOf("Пароль на табличке у ресепшена."), voice.spoken)
        coVerify(exactly = 0) { backend.ask(any(), any()) }
    }

    @Test
    fun `guide_to action drives after the answer without a second start phrase`() = runTest(dispatcher) {
        coEvery { backend.chat(any(), any()) } returns chat(
            "Провожаю вас в кафе, идите за мной.",
            ChatAction(ChatActionType.GUIDE_TO, "cafe"),
        )
        val vm = vm()

        vm.submit("Мне нужно в кафе, отведёшь?")
        advanceUntilIdle()

        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
        assertEquals("Провожаю вас в кафе, идите за мной.", voice.spoken.first())
        assertTrue(voice.spoken.none { it == WayPhrases.guideStart("Кафе", Lang.RU) })
        assertEquals(WayPhrases.guideArrived("Кафе", Lang.RU), voice.spoken.last())
    }

    @Test
    fun `where_is action offers the guide button`() = runTest(dispatcher) {
        coEvery { backend.chat(any(), any()) } returns chat(
            "Кафе на первом этаже справа.",
            ChatAction(ChatActionType.WHERE_IS, "cafe"),
        )
        val vm = vm()

        vm.submit("Мне бы перекусить")
        advanceUntilIdle()

        assertEquals("cafe", vm.state.value.guideTarget?.id)
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
    }

    @Test
    fun `place unknown to the robot is skipped for where_is and reported for guide_to`() = runTest(dispatcher) {
        coEvery { backend.chat("Покажи склад", any()) } returns chat("Склад там.", ChatAction(ChatActionType.WHERE_IS, "storage"))
        coEvery { backend.chat("Отведи на склад", any()) } returns chat("Веду.", ChatAction(ChatActionType.GUIDE_TO, "storage"))
        val vm = vm()

        vm.submit("Покажи склад")
        advanceUntilIdle()
        assertNull(vm.state.value.guideTarget)

        vm.submit("Отведи на склад")
        advanceUntilIdle()
        assertEquals(WayPhrases.unknownPlace(Lang.RU), voice.spoken.last())
        coVerify(exactly = 0) { robot.navigateTo(any(), any()) }
    }

    @Test
    fun `look action takes a frame and asks look with the visitor question`() = runTest(dispatcher) {
        val jpeg = byteArrayOf(1, 2, 3)
        coEvery { camera.captureJpeg(any()) } returns jpeg
        coEvery { backend.chat(any(), any()) } returns chat("Сейчас посмотрю.", ChatAction(ChatActionType.LOOK, null))
        coEvery { backend.look(any(), any(), any()) } returns
            ApiResult.Success(Answer("req2", "Это переговорная.", listOf("Это переговорная."), Lang.RU, true))
        val vm = vm()

        vm.submit("Скажи, в какой комнате мы стоим и что тут обычно бывает")
        advanceUntilIdle()

        coVerify { backend.look(jpeg, "Скажи, в какой комнате мы стоим и что тут обычно бывает", any()) }
        assertEquals(listOf("Сейчас посмотрю.", "Это переговорная."), voice.spoken)
    }

    @Test
    fun `guide with an unknown place asks the agent - it remembers the conversation`() = runTest(dispatcher) {
        coEvery { backend.chat("Проводи туда", any()) } returns chat("Провожаю.", ChatAction(ChatActionType.GUIDE_TO, "cafe"))
        val vm = vm()

        vm.submit("Проводи туда")
        advanceUntilIdle()

        coVerify { backend.chat("Проводи туда", any()) }
        coVerify(exactly = 1) { robot.navigateTo("Кафе", any()) }
    }

    @Test
    fun `guide with an unknown place and no server says the place is unknown`() = runTest(dispatcher) {
        coEvery { backend.chat(any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val vm = vm()

        vm.submit("Проводи туда")
        advanceUntilIdle()

        assertEquals(listOf(WayPhrases.unknownPlace(Lang.RU)), voice.spoken)
    }

    @Test
    fun `local answers are recorded for the agent memory`() = runTest(dispatcher) {
        val vm = vm()

        vm.submit("Где кафе?")
        advanceUntilIdle()
        vm.submit("Проводи в кафе")
        advanceUntilIdle()

        assertEquals(listOf("where_is", "guide_to"), recorded.map { it.kind })
        assertEquals(listOf("Где кафе?", "Проводи в кафе"), recorded.map { it.question })
        assertEquals("cafe", recorded[0].placeId)
        assertEquals(WayPhrases.guideStart("Кафе", Lang.RU), recorded[1].answer)
        coVerify(exactly = 0) { backend.chat(any(), any()) }
    }

    @Test
    fun `failed recording does not break the answer`() = runTest(dispatcher) {
        coEvery { backend.recordLocal(any()) } throws IllegalStateException("boom")
        val vm = vm()

        vm.submit("Где кафе?")
        advanceUntilIdle()

        assertTrue(voice.spoken.isNotEmpty())
        assertEquals("cafe", vm.state.value.guideTarget?.id)
    }
}
