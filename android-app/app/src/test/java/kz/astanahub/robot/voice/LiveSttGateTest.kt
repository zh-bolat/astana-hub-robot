package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.audio.Mp3Player
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.LiveSttEvent
import kz.astanahub.robot.backend.LiveSttSession
import kz.astanahub.robot.backend.SttResult
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.NoiseStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Порог старта live STT: WebSocket открывается только на речь ≥ минимальной длительности (250 мс по умолчанию);
 * короткий или тихий звук — ни соединения, ни `end`, ни запроса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSttGateTest {

    private class FakeSession : LiveSttSession {
        private val channel = Channel<LiveSttEvent>(Channel.UNLIMITED)
        override val events: ReceiveChannel<LiveSttEvent> = channel
        var sentBytes = 0
        var ended = false
        var cancelled = false

        init {
            channel.trySend(LiveSttEvent.Ready("fake"))
        }

        override fun sendPcm(pcm: ByteArray): Boolean {
            sentBytes += pcm.size
            return true
        }

        override fun end() {
            ended = true
            channel.trySend(LiveSttEvent.Final(SttResult("r", "Где кафе?", Lang.RU, null)))
            channel.close()
        }

        override fun cancel() {
            cancelled = true
            channel.close()
        }
    }

    private val random = Random(3)
    private val sessions = mutableListOf<FakeSession>()
    private val backend = mockk<BackendApi> {
        every { supportsLiveStt } returns true
        every { openLiveStt(any(), any()) } answers { FakeSession().also { sessions += it } }
    }
    private val recorder = mockk<MicRecorder> { every { hasPermission() } returns true }
    private val player = mockk<Mp3Player>(relaxed = true)

    @Before
    fun setUp() = NoiseStats.reset()

    private fun pcm(ms: Int, sample: (Int) -> Int): ShortArray = ShortArray(16 * ms) { sample(it).toShort() }
    private fun silence(ms: Int) = pcm(ms) { random.nextInt(-60, 61) }
    private fun tone(ms: Int, amplitude: Int = 6_000) = pcm(ms) { (amplitude * sin(2 * PI * 220 * it / 16_000)).toInt() }

    /** Микрофон отдаёт [samples] кусками по 100 мс, потом поток заканчивается. */
    private fun micPlays(samples: ShortArray) {
        coEvery { recorder.stream(any(), any(), any(), any()) } coAnswers {
            val onChunk = arg<(ByteArray) -> Unit>(3)
            MicRecorder.toLittleEndian(samples).toList().chunked(3_200).forEach { onChunk(it.toByteArray()) }
            null
        }
    }

    private fun TestScope.listen(): Pair<ServerVoiceGateway, List<Transcript>> {
        val g = ServerVoiceGateway(backend, recorder, player, scope = backgroundScope)
        val transcripts = mutableListOf<Transcript>()
        backgroundScope.launch { g.transcripts.collect { transcripts += it } }
        g.listenOnce()
        // advanceUntilIdle не крутит backgroundScope — двигаем время явно
        advanceTimeBy(5_000)
        runCurrent()
        return g to transcripts
    }

    @Test
    fun `short sound does not open the websocket and sends no end`() = runTest(StandardTestDispatcher()) {
        micPlays(silence(500) + tone(200) + silence(1_000))
        val (_, transcripts) = listen()
        verify(exactly = 0) { backend.openLiveStt(any(), any()) }
        assertTrue(transcripts.isEmpty())
        assertEquals(1, NoiseStats.cancelledShortSounds)
    }

    @Test
    fun `quiet background talk does not open the websocket`() = runTest(StandardTestDispatcher()) {
        micPlays(silence(300) + tone(2_000, amplitude = 300) + silence(500))
        listen()
        verify(exactly = 0) { backend.openLiveStt(any(), any()) }
    }

    @Test
    fun `speech of minimum duration opens the websocket with pre-roll and ends it`() = runTest(StandardTestDispatcher()) {
        micPlays(silence(500) + tone(800) + silence(1_000))
        val (_, transcripts) = listen()
        verify(exactly = 1) { backend.openLiveStt(any(), any()) }
        val s = sessions.single()
        assertTrue(s.ended)
        // пре-ролл ушёл вместе с началом речи: не меньше 300 мс до старта + сама речь
        assertTrue("sent ${s.sentBytes}", s.sentBytes >= (300 + 800) * 32)
        assertEquals("Где кафе?", transcripts.last { it.isFinal }.text)
    }

    @Test
    fun `server without live STT records the old way`() = runTest(StandardTestDispatcher()) {
        every { backend.supportsLiveStt } returns false
        coEvery { recorder.recordUtterance(any(), any()) } returns MicRecorder.Result.NoSpeech
        listen()
        verify(exactly = 0) { backend.openLiveStt(any(), any()) }
        assertFalse(sessions.isNotEmpty())
    }
}
