package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingVoiceGatewayTest {

    /** Голос-дублёр: «говорит» [millisPerPhrase] на фразу, считает одновременные озвучки. */
    private class FakeVoice(private val millisPerPhrase: Long = 1_000) : VoiceGateway {
        val spoken = mutableListOf<String>()
        val asr = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
        override val transcripts: Flow<Transcript> = asr
        override val activity = MutableStateFlow(VoiceActivity.IDLE)
        var concurrent = 0
        var maxConcurrent = 0
        var stops = 0
        private var stopRequested = false

        override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            stopRequested = false
            activity.value = VoiceActivity.SPEAKING
            try {
                for (p in phrases) {
                    var left = millisPerPhrase
                    while (left > 0) {
                        if (stopRequested) return false
                        delay(50); left -= 50
                    }
                    spoken += p
                }
                return true
            } finally {
                concurrent--
                activity.value = VoiceActivity.IDLE
            }
        }

        override fun stopSpeaking() {
            stops++
            stopRequested = true
        }

        override fun setLanguage(lang: Lang) = Unit
    }

    private val config = MutableStateFlow(VoiceConfig())
    private val settings = mockk<VoiceSettings> { every { config } returns this@RoutingVoiceGatewayTest.config }
    private val sttEvents = MutableSharedFlow<SttEvent>(extraBufferCapacity = 8)
    private val server = mockk<ServerVoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
        every { sttEvents } returns this@RoutingVoiceGatewayTest.sttEvents
    }
    private val mic = mockk<BuiltinMicControl>(relaxed = true)

    private fun voiceMock() = mockk<VoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
    }


    private fun TestScope.router(
        builtinAsr: VoiceGateway = voiceMock(),
        builtinTts: VoiceGateway = voiceMock(),
        builtinAsrAvailable: Boolean = false,
        health: suspend () -> Boolean = { false },
        scope: CoroutineScope = backgroundScope,
    ) = RoutingVoiceGateway(
        builtinAsr, builtinTts, server, settings, mic, continuousServerAsr = true,
        builtinAsrAvailable = builtinAsrAvailable, healthCheck = health, scope = scope,
    )

    // --- маршрутизация (было до волны 3) ---

    @Test
    fun `defaults - ru and en builtin, kk server`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { server.speakDetailed(any(), any()) } returns ServerSpeakResult.Done
        val tts = voiceMock()
        val r = router(builtinTts = tts)

        r.speak(listOf("Привет."), Lang.RU)
        r.speak(listOf("Hi."), Lang.EN)
        r.speak(listOf("Сәлем."), Lang.KK)

        coVerify { tts.speak(listOf("Привет."), Lang.RU) }
        coVerify { tts.speak(listOf("Hi."), Lang.EN) }
        coVerify { server.speakDetailed(listOf("Сәлем."), Lang.KK) }
        coVerify(exactly = 0) { tts.speak(any(), Lang.KK) }
    }

    @Test
    fun `server TTS unavailable or fake falls back to builtin for remaining phrases`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { server.speakDetailed(any(), any()) } returns
            ServerSpeakResult.Unavailable(listOf("Екі."), BackendError(ErrorKind.SPEECH_UNAVAILABLE, message = "speech provider is fake"))
        val tts = voiceMock()
        coEvery { tts.speak(any(), any()) } returns true

        assertTrue(router(builtinTts = tts).speak(listOf("Бір.", "Екі."), Lang.KK))

        coVerify { tts.speak(listOf("Екі."), Lang.KK) }
    }

    @Test
    fun `per-language override from settings`() = runTest(UnconfinedTestDispatcher()) {
        config.value = VoiceConfig(tts = mapOf(Lang.RU to VoiceSource.SERVER))
        coEvery { server.speakDetailed(any(), any()) } returns ServerSpeakResult.Done
        router().speak(listOf("Привет."), Lang.RU)
        coVerify { server.speakDetailed(listOf("Привет."), Lang.RU) }
    }

    @Test
    fun `server ASR by default mutes builtin and listens continuously`() = runTest(UnconfinedTestDispatcher()) {
        router()
        verify { mic.setBuiltinListening(false) }
        verify { server.setContinuous(true) }
    }

    @Test
    fun `builtin ASR option re-enables builtin listening`() = runTest(UnconfinedTestDispatcher()) {
        router()
        config.value = config.value.copy(asr = VoiceSource.BUILTIN)
        verify { mic.setBuiltinListening(true) }
        verify { server.setContinuous(false) }
    }

    @Test
    fun `stop stops every voice`() = runTest(UnconfinedTestDispatcher()) {
        val asr = voiceMock()
        val tts = voiceMock()
        router(builtinAsr = asr, builtinTts = tts).stopSpeaking()
        verify { tts.stopSpeaking() }
        verify { server.stopSpeaking() }
        verify { asr.stopSpeaking() }
    }

    @Test
    fun `config description`() {
        assertEquals("ASR=server; TTS ru=builtin, kk=server, en=builtin", VoiceConfig().describe())
    }

    // --- волна 3: одна очередь озвучки и «Стоп» ---

    @Test
    fun `speak calls are serialized - never two voices at once`() = runTest(StandardTestDispatcher()) {
        val tts = FakeVoice()
        val r = router(builtinTts = tts)

        val a = async { r.speak(listOf("Раз."), Lang.RU) }
        val b = async { r.speak(listOf("Два."), Lang.EN) }
        advanceTimeBy(1_500)
        assertEquals(listOf("Раз."), tts.spoken)
        assertTrue(a.await() && b.await())

        assertEquals(listOf("Раз.", "Два."), tts.spoken)
        assertEquals(1, tts.maxConcurrent)
    }

    @Test
    fun `stop silences current phrase and drops everything queued`() = runTest(StandardTestDispatcher()) {
        val tts = FakeVoice()
        val r = router(builtinTts = tts)

        val playing = async { r.speak(listOf("Длинный ответ."), Lang.RU) }
        val queued1 = async { r.speak(listOf("Препятствие."), Lang.RU) }
        val queued2 = async { r.speak(listOf("Пришли."), Lang.RU) }
        advanceTimeBy(300)
        r.stopSpeaking()

        assertFalse(playing.await())
        assertFalse(queued1.await())
        assertFalse(queued2.await())
        assertTrue(tts.spoken.isEmpty())

        // После «Стопа» новая озвучка снова звучит.
        assertTrue(r.speak(listOf("Хорошо, стою."), Lang.RU))
        assertEquals(listOf("Хорошо, стою."), tts.spoken)
    }

    // --- волна 3: нет самоперебивания ---

    @Test
    fun `speech guard covers any speech plus 300 ms tail and mutes server ASR`() = runTest(StandardTestDispatcher()) {
        val tts = FakeVoice()
        val r = router(builtinTts = tts)
        runCurrent()
        assertFalse(r.speechGuard.value)

        val job = launch { r.speak(listOf("Фраза."), Lang.RU) }
        runCurrent()
        assertTrue(r.speechGuard.value)
        assertEquals(VoiceActivity.SPEAKING, r.activity.value)
        verify { server.setSpeechActive(true) }

        job.join() // 1 с озвучки
        runCurrent()
        assertTrue("хвост тишины ещё идёт", r.speechGuard.value)
        advanceTimeBy(RoutingVoiceGateway.SPEECH_TAIL_MS + 1)
        assertFalse(r.speechGuard.value)
        verify { server.setSpeechActive(false) }
    }

    @Test
    fun `speech from server voice alone also raises the guard`() = runTest(StandardTestDispatcher()) {
        val serverActivity = MutableStateFlow(VoiceActivity.IDLE)
        every { server.activity } returns serverActivity
        val r = router()
        runCurrent()
        serverActivity.value = VoiceActivity.SPEAKING
        runCurrent()
        assertTrue(r.speechGuard.value)
    }

    @Test
    fun `builtin ASR results are ignored while robot speaks and during the tail`() = runTest(StandardTestDispatcher()) {
        config.value = VoiceConfig(asr = VoiceSource.BUILTIN)
        val voice = FakeVoice()
        val r = router(builtinAsr = voice, builtinTts = voice)
        val heard = mutableListOf<String>()
        backgroundScope.launch { r.transcripts.collect { heard += it.text } }
        runCurrent()

        val speech = launch { r.speak(listOf("Я робот."), Lang.RU) }
        runCurrent()
        voice.asr.emit(Transcript("я робот", isFinal = true)) // эхо собственной речи
        speech.join()
        runCurrent()
        voice.asr.emit(Transcript("робот", isFinal = true)) // хвост эха
        runCurrent()
        assertTrue(heard.isEmpty())

        advanceTimeBy(RoutingVoiceGateway.SPEECH_TAIL_MS + 1)
        voice.asr.emit(Transcript("где кафе", isFinal = true))
        runCurrent()
        assertEquals(listOf("где кафе"), heard)
    }

    // --- офлайн по ТЗ: откат распознавания ---

    @Test
    fun `stt network failure falls back to builtin ASR until health check passes`() = runTest(StandardTestDispatcher()) {
        var healthy = false
        var probes = 0
        val r = router(builtinAsrAvailable = true, health = { probes++; healthy })
        val events = mutableListOf<Boolean>()
        backgroundScope.launch { r.connection.collect { events += it } }
        runCurrent()
        verify(exactly = 1) { mic.setBuiltinListening(false) }

        sttEvents.emit(SttEvent("", null, null, 1_000, 6_000, BackendError(ErrorKind.NETWORK)))
        runCurrent()
        assertTrue(r.sttOffline.value)
        assertEquals(VoiceSource.BUILTIN, r.effectiveAsr())
        verify { mic.setBuiltinListening(true) }
        verify { server.setContinuous(false) }
        assertEquals(listOf(false), events)

        advanceTimeBy(RoutingVoiceGateway.HEALTH_PROBE_MS + 1)
        assertEquals(1, probes)
        assertTrue(r.sttOffline.value)

        healthy = true
        advanceTimeBy(RoutingVoiceGateway.HEALTH_PROBE_MS + 1)
        assertFalse(r.sttOffline.value)
        assertEquals(VoiceSource.SERVER, r.effectiveAsr())
        verify(exactly = 2) { mic.setBuiltinListening(false) }
        assertEquals(listOf(false, true), events)
    }

    @Test
    fun `timeout also counts as lost connection, other errors do not`() = runTest(StandardTestDispatcher()) {
        val r = router(builtinAsrAvailable = true)
        runCurrent()
        sttEvents.emit(SttEvent("", null, null, 0, 0, BackendError(ErrorKind.SPEECH_UNAVAILABLE)))
        runCurrent()
        assertFalse(r.sttOffline.value)
        sttEvents.emit(SttEvent("", null, null, 0, 0, BackendError(ErrorKind.TIMEOUT)))
        runCurrent()
        assertTrue(r.sttOffline.value)
    }

    @Test
    fun `without builtin ASR the server stays but connection loss is reported`() = runTest(StandardTestDispatcher()) {
        val r = router(builtinAsrAvailable = false)
        val events = mutableListOf<Boolean>()
        backgroundScope.launch { r.connection.collect { events += it } }
        runCurrent()
        sttEvents.emit(SttEvent("", null, null, 0, 0, BackendError(ErrorKind.NETWORK)))
        runCurrent()
        assertEquals(VoiceSource.SERVER, r.effectiveAsr())
        assertEquals(listOf(false), events)

        // Удачный STT — связь есть, проверка /health не нужна.
        sttEvents.emit(SttEvent("где кафе", Lang.RU, 0.9, 1_000, 500))
        runCurrent()
        assertEquals(listOf(false, true), events)
    }

    @Test
    fun `session id is forwarded to server STT`() = runTest(UnconfinedTestDispatcher()) {
        router().setSessionId("s-1")
        verify { server.setSessionId("s-1") }
    }
}
