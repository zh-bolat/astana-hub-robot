package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingVoiceGatewayTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val config = MutableStateFlow(VoiceConfig())
    private val settings = mockk<VoiceSettings> { every { config } returns this@RoutingVoiceGatewayTest.config }
    private val builtinAsr = voiceMock()
    private val builtinTts = voiceMock()
    private val server = mockk<ServerVoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
    }
    private val mic = mockk<BuiltinMicControl>(relaxed = true)

    private fun voiceMock() = mockk<VoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun router() = RoutingVoiceGateway(builtinAsr, builtinTts, server, settings, mic, continuousServerAsr = true)

    @Test
    fun `defaults - ru and en builtin, kk server`() = runTest(dispatcher) {
        coEvery { server.speakDetailed(any(), any()) } returns ServerSpeakResult.Done
        val r = router()

        r.speak(listOf("Привет."), Lang.RU)
        r.speak(listOf("Hi."), Lang.EN)
        r.speak(listOf("Сәлем."), Lang.KK)

        coVerify { builtinTts.speak(listOf("Привет."), Lang.RU) }
        coVerify { builtinTts.speak(listOf("Hi."), Lang.EN) }
        coVerify { server.speakDetailed(listOf("Сәлем."), Lang.KK) }
        coVerify(exactly = 0) { builtinTts.speak(any(), Lang.KK) }
    }

    @Test
    fun `server TTS unavailable or fake falls back to builtin for remaining phrases`() = runTest(dispatcher) {
        coEvery { server.speakDetailed(any(), any()) } returns
            ServerSpeakResult.Unavailable(listOf("Екі."), BackendError(ErrorKind.SPEECH_UNAVAILABLE, message = "speech provider is fake"))
        coEvery { builtinTts.speak(any(), any()) } returns true

        assertTrue(router().speak(listOf("Бір.", "Екі."), Lang.KK))

        coVerify { builtinTts.speak(listOf("Екі."), Lang.KK) }
    }

    @Test
    fun `per-language override from settings`() = runTest(dispatcher) {
        config.value = VoiceConfig(tts = mapOf(Lang.RU to VoiceSource.SERVER))
        coEvery { server.speakDetailed(any(), any()) } returns ServerSpeakResult.Done
        router().speak(listOf("Привет."), Lang.RU)
        coVerify { server.speakDetailed(listOf("Привет."), Lang.RU) }
    }

    @Test
    fun `server ASR by default mutes builtin and listens continuously`() = runTest(dispatcher) {
        router()
        verify { mic.setBuiltinListening(false) }
        verify { server.setContinuous(true) }
    }

    @Test
    fun `builtin ASR option re-enables builtin listening`() = runTest(dispatcher) {
        router()
        config.value = config.value.copy(asr = VoiceSource.BUILTIN)
        verify { mic.setBuiltinListening(true) }
        verify { server.setContinuous(false) }
    }

    @Test
    fun `stop stops every voice`() = runTest(dispatcher) {
        router().stopSpeaking()
        verify { builtinTts.stopSpeaking() }
        verify { server.stopSpeaking() }
        verify { builtinAsr.stopSpeaking() }
    }

    @Test
    fun `config description`() {
        assertEquals("ASR=server; TTS ru=builtin, kk=server, en=builtin", VoiceConfig().describe())
    }
}
