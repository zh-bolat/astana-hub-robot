package kz.astanahub.robot.ui

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.backend.TurnMetrics
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.voice.SpeechEvent
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

/** Быстрый отклик: «думаю» по концу речи, «хм…» через 1,2 с только при задержке, тайминги хода на сервер. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLatencyTest {

    private class TestVoice : VoiceGateway {
        val spoken = mutableListOf<String>()
        val cachedSpoken = mutableListOf<Pair<String, Long>>()
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 8)
        lateinit var now: () -> Long
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)
        override val utteranceEnded: Flow<Unit> = ended
        override val speechEvents: Flow<SpeechEvent> = events

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            events.emit(SpeechEvent.TTS_FIRST_BYTE)
            delay(200)
            events.emit(SpeechEvent.AUDIO_START)
            spoken += phrases
            delay(500)
            return true
        }

        override suspend fun speakCached(text: String, lang: Lang): Boolean {
            cachedSpoken += text to now()
            delay(400)
            return true
        }

        override fun stopSpeaking() = Unit
        override fun setLanguage(lang: Lang) = Unit
    }

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}" }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: BackendApi
    private lateinit var voice: TestVoice
    private val metrics = mutableListOf<TurnMetrics>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = mockk(relaxed = true)
        voice = TestVoice()
        coEvery { backend.quickQuestions(any()) } returns ApiResult.Success(listOf(QuickQuestion("q1", "Вопрос")))
        val m = slot<TurnMetrics>()
        coEvery { backend.postTurnMetrics(capture(m)) } answers { metrics += m.captured; ApiResult.Success(Unit) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Агент отвечает первой фразой через [firstPhraseAfter] мс. */
    private fun agentReplies(firstPhraseAfter: Long) {
        every { backend.chat(any()) } answers {
            flow {
                delay(firstPhraseAfter / 2)
                emit(ChatEvent.Emotion("happy"))
                delay(firstPhraseAfter - firstPhraseAfter / 2)
                emit(ChatEvent.Phrase("Ответ агента."))
                emit(ChatEvent.Done("req_1", Lang.RU))
            }
        }
    }

    private fun TestScope.vm(): MainViewModel {
        voice.now = { testScheduler.currentTime }
        return MainViewModel(
            backend, voice, mockk<CameraGateway>(), phrases, clock = { testScheduler.currentTime },
            chatMode = { ChatMode.AGENT }, vadSilenceMillis = { 500 },
        ).also { advanceUntilIdle() }
    }

    @Test
    fun `face shows thinking right at the end of speech, before the final text`() = runTest(dispatcher) {
        agentReplies(firstPhraseAfter = 500)
        val vm = vm()
        voice.ended.emit(Unit)
        runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        // финальный текст через 300 мс — ход идёт, лицо всё ещё думает
        advanceTimeBy(300)
        voice.asr.emit(Transcript("Что такое Astana Hub?", isFinal = true))
        runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        advanceUntilIdle()
        assertEquals(listOf("Ответ агента."), voice.spoken)
    }

    @Test
    fun `empty final text after end of speech returns the face to idle`() = runTest(dispatcher) {
        val vm = vm()
        voice.ended.emit(Unit)
        runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        voice.asr.emit(Transcript("", isFinal = true))
        runCurrent()
        assertEquals(Phase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `short utterance after end of speech is not filtered as noise`() = runTest(dispatcher) {
        agentReplies(firstPhraseAfter = 300)
        val vm = vm()
        voice.ended.emit(Unit)
        runCurrent()
        voice.asr.emit(Transcript("Привет", isFinal = true)) // одно слово — во время «занят» отфильтровалось бы
        advanceUntilIdle()
        assertEquals("Привет", vm.state.value.question)
        assertEquals(listOf("Ответ агента."), voice.spoken)
    }

    @Test
    fun `late first phrase - only the thinking face, no filler words`() = runTest(dispatcher) {
        agentReplies(firstPhraseAfter = 3_000)
        val vm = vm()
        voice.asr.emit(Transcript("Расскажи про хаб подробно", isFinal = true))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(Phase.THINKING, vm.state.value.phase)
        assertTrue(voice.cachedSpoken.isEmpty())
        assertTrue(voice.spoken.isEmpty())
        advanceUntilIdle()
        assertTrue(voice.cachedSpoken.isEmpty())
        assertEquals(listOf("Ответ агента."), voice.spoken)
    }

    @Test
    fun `turn timings are posted from the end of speech, without texts`() = runTest(dispatcher) {
        agentReplies(firstPhraseAfter = 1_000)
        vm()
        voice.ended.emit(Unit)
        runCurrent()
        advanceTimeBy(400)
        voice.asr.emit(Transcript("Что такое Astana Hub?", isFinal = true))
        advanceUntilIdle()
        val m = metrics.single()
        assertEquals(TurnMetrics.MODE_AGENT, m.mode)
        assertEquals(400L, m.sttFinalMs)
        assertEquals(900L, m.chatFirstEventMs) // эмоция через 500 мс после финала
        assertEquals(1_400L, m.firstPhraseMs)
        assertEquals(1_400L, m.ttsFirstByteMs)
        assertEquals(1_600L, m.firstAudioMs)
        assertEquals(500, m.vadSilenceMs)
        assertEquals("req_1", m.requestId)
        assertEquals(Lang.RU, m.lang)
        assertFalse(m.fallback)
        assertFalse(m.interrupted)
        // в метриках нет текстов: только числа, флаги, id и язык
        assertFalse(m.toString().contains("Astana"))
    }

    @Test
    fun `button question has no stt timing and fallback is reported`() = runTest(dispatcher) {
        every { backend.chat(any()) } answers { flow { emit(ChatEvent.Error(kz.astanahub.robot.backend.BackendError(kz.astanahub.robot.backend.ErrorKind.LLM_UNAVAILABLE))) } }
        coEvery { backend.ask(any(), any()) } returns ApiResult.Success(
            kz.astanahub.robot.backend.Answer("req_ask", "Ответ.", listOf("Ответ."), Lang.RU, true),
        )
        val vm = vm()
        vm.submitButton("Что такое Astana Hub?")
        advanceUntilIdle()
        val m = metrics.single()
        assertNull(m.sttFinalMs)
        assertTrue(m.fallback)
        assertEquals(TurnMetrics.MODE_ASK, m.mode)
        assertEquals("req_ask", m.requestId)
    }

    @Test
    fun `interrupted turn is reported as interrupted`() = runTest(dispatcher) {
        agentReplies(firstPhraseAfter = 5_000)
        val vm = vm()
        voice.asr.emit(Transcript("Что такое Astana Hub?", isFinal = true))
        advanceTimeBy(500); runCurrent()
        vm.stop()
        advanceUntilIdle()
        assertTrue(metrics.single().interrupted)
    }

    @Test
    fun `chat request is sent right at the final text`() = runTest(dispatcher) {
        val sentAt = mutableListOf<Long>()
        every { backend.chat(any()) } answers {
            sentAt += testScheduler.currentTime
            flow<ChatEvent> { emit(ChatEvent.Phrase("Да.")); emit(ChatEvent.Done("r", Lang.RU)) }
        }
        vm()
        advanceTimeBy(1_000)
        voice.asr.emit(Transcript("Есть ли кофе?", isFinal = true))
        advanceUntilIdle()
        // без ожидания позиции робота и прочего: запрос ушёл в тот же момент
        val start = sentAt.single()
        assertTrue(start in 1_000L..1_001L)
    }
}
