package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.audio.Mp3Player
import kz.astanahub.robot.audio.VadEvent
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.SttResult
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Серверный ASR не пишет, пока робот говорит, и бросает начатую запись при старте озвучки. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerVoiceGatewayTest {

    // live STT (WS /v1/stt/stream) не поддерживается: тесты проверяют путь запись → /v1/stt
    private val backend = mockk<BackendApi> { every { openLiveStt(any(), any()) } returns null; every { supportsLiveStt } returns false }
    private val recorder = mockk<MicRecorder> { every { hasPermission() } returns true }
    private val player = mockk<Mp3Player>(relaxed = true)
    private var recordings = 0
    private var cancelledRecordings = 0

    private fun TestScope.gateway() = ServerVoiceGateway(backend, recorder, player, scope = backgroundScope)

    private fun speech() = MicRecorder.Result.Speech(ShortArray(16_000), VadEvent.SPEECH_ENDED)

    @Test
    fun `recording in progress is cancelled when robot starts speaking`() = runTest(StandardTestDispatcher()) {
        coEvery { recorder.recordUtterance(any(), any()) } coAnswers {
            recordings++
            try { awaitCancellation() } finally { cancelledRecordings++ }
        }
        val g = gateway()
        g.listenOnce()
        runCurrent()
        assertEquals(1, recordings)
        assertEquals(VoiceActivity.LISTENING, g.activity.value)

        g.setSpeechActive(true)
        runCurrent()
        assertEquals(1, cancelledRecordings)
        assertEquals(VoiceActivity.IDLE, g.activity.value)
        coVerify(exactly = 0) { backend.stt(any(), any(), any()) }
    }

    @Test
    fun `continuous ASR does not record while robot speaks and resumes after`() = runTest(StandardTestDispatcher()) {
        coEvery { recorder.recordUtterance(any(), any()) } coAnswers { recordings++; delay(10_000); MicRecorder.Result.NoSpeech }
        val g = gateway()
        g.setSpeechActive(true)
        g.setContinuous(true)
        advanceTimeBy(5_000)
        assertEquals(0, recordings)

        g.setSpeechActive(false)
        runCurrent()
        assertEquals(1, recordings)
        g.setContinuous(false)
    }

    @Test
    fun `push to talk right after stop waits for the silence tail`() = runTest(StandardTestDispatcher()) {
        coEvery { recorder.recordUtterance(any(), any()) } coAnswers { recordings++; MicRecorder.Result.NoSpeech }
        val g = gateway()
        g.setSpeechActive(true) // хвост ещё идёт
        g.listenOnce()
        advanceTimeBy(200)
        assertEquals(0, recordings)
        g.setSpeechActive(false)
        runCurrent()
        assertEquals(1, recordings)
    }

    @Test
    fun `stt uses session id of the screen`() = runTest(StandardTestDispatcher()) {
        coEvery { recorder.recordUtterance(any(), any()) } returns speech()
        coEvery { backend.stt(any(), any(), any()) } returns ApiResult.Success(SttResult("r", "где кафе", Lang.RU, 0.9))
        val g = gateway()
        g.setSessionId("session-42")
        g.listenOnce()
        runCurrent()
        coVerify { backend.stt(any(), any(), "session-42") }
        assertTrue(true)
    }
}
