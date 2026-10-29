package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Серверная озвучка без пауз: склейка, предзагрузка следующего куска во время текущего,
 * старт следующего сразу по окончании текущего, стоп отменяет всё. Виртуальное время.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSpeechPipelineTest {

    private val recorder = mockk<MicRecorder>(relaxed = true)
    private val backend = mockk<BackendApi> { every { openLiveStt(any(), any()) } returns null; every { supportsLiveStt } returns false }

    /** (текст запроса /v1/tts, время начала синтеза). */
    private val synthStarts = mutableListOf<Pair<String, Long>>()
    private var synthCancelled = 0

    private fun TestScope.ttsTakes(millis: Long, failOn: String? = null) {
        coEvery { backend.tts(any(), any()) } coAnswers {
            val text = firstArg<String>()
            synthStarts += text to currentTime
            try {
                delay(millis)
            } catch (e: CancellationException) {
                synthCancelled++
                throw e
            }
            if (failOn != null && failOn in text) ApiResult.Failure(BackendError(ErrorKind.SPEECH_UNAVAILABLE, message = "503"))
            else ApiResult.Success(text.toByteArray())
        }
    }

    private fun TestScope.gateway(clips: FakeClipPlayer) = ServerVoiceGateway(backend, recorder, clips, scope = backgroundScope)

    /** Каждый следующий кусок стартует ровно тогда, когда закончился предыдущий. */
    private fun assertNoGaps(clips: FakeClipPlayer) {
        for (i in 1 until clips.started.size) {
            assertEquals("пауза перед «${clips.started[i].first}»", clips.finished[i - 1].second, clips.started[i].second)
        }
    }

    @Test
    fun `first phrase starts at once, the rest is merged and synthesized while the first plays`() = runTest(StandardTestDispatcher()) {
        ttsTakes(300)
        val clips = FakeClipPlayer(this, millisPerChar = 50)
        val q = PhraseQueue()
        listOf("Привет, я робот Astana Hub.", "Здесь коворкинг.", "Кафе на первом этаже.", "Спросите ещё.").forEach(q::add)
        q.close()

        assertEquals(ServerSpeakResult.Done, gateway(clips).speakQueue(q, Lang.RU))

        assertEquals(
            listOf("Привет, я робот Astana Hub." to 0L, "Здесь коворкинг. Кафе на первом этаже. Спросите ещё." to 300L),
            synthStarts,
        )
        val firstEnds = clips.finished[0].second
        assertTrue("синтез второго куска начался во время первого", synthStarts[1].second in clips.started[0].second until firstEnds)
        assertEquals(2, clips.started.size)
        assertNoGaps(clips)
        assertEquals(clips.prepared.toSet(), clips.released)
    }

    @Test
    fun `phrases streamed one by one - each next chunk is ready before the current ends`() = runTest(StandardTestDispatcher()) {
        ttsTakes(400)
        val clips = FakeClipPlayer(this, millisPerChar = 60)
        val q = PhraseQueue()
        launch {
            q.add("Astana Hub — технопарк в Астане.")
            delay(100); q.add("Здесь работают стартапы.")
            delay(900); q.add("Есть коворкинг и кафе.")
            delay(100); q.add("Могу проводить.")
            q.close()
        }
        val chunks = mutableListOf<String>()
        val listener = object : SpeechListener {
            override fun onChunkStart(chunk: SpeechChunk) { chunks += chunk.text }
        }
        assertEquals(ServerSpeakResult.Done, gateway(clips).speakQueue(q, Lang.RU, listener))

        assertEquals(synthStarts.map { it.first }, clips.started.map { it.first })
        assertEquals(chunks, clips.started.map { it.first })
        assertEquals(3, clips.started.size)
        assertEquals("Есть коворкинг и кафе. Могу проводить.", clips.started[2].first)
        assertNoGaps(clips)
        // Синтез каждого следующего куска — пока звучит предыдущий.
        for (i in 1 until synthStarts.size) {
            assertTrue(synthStarts[i].second >= clips.started[i - 1].second)
            assertTrue(synthStarts[i].second + 400 <= clips.finished[i - 1].second)
        }
        assertEquals(clips.prepared.toSet(), clips.released)
    }

    @Test
    fun `stop silences current chunk, cancels preload and drops the queue`() = runTest(StandardTestDispatcher()) {
        ttsTakes(300)
        val clips = FakeClipPlayer(this, millisPerChar = 100)
        val g = gateway(clips)
        val q = PhraseQueue()
        listOf("Первая фраза.", "Вторая.", "Третья.").forEach(q::add)
        val speech = async { g.speakQueue(q, Lang.RU) }
        advanceTimeBy(500) // первая звучит, вторая+третья синтезируются
        assertEquals(VoiceActivity.SPEAKING, g.activity.value)
        assertEquals(2, synthStarts.size)

        g.stopSpeaking()
        runCurrent()
        assertEquals(ServerSpeakResult.Interrupted, speech.await())
        assertEquals(1, synthCancelled)
        advanceTimeBy(5_000)
        assertEquals(1, clips.started.size)
        assertEquals(clips.prepared.toSet(), clips.released)
        assertEquals(VoiceActivity.IDLE, g.activity.value)
    }

    @Test
    fun `stop while the next chunk is already prepared releases it`() = runTest(StandardTestDispatcher()) {
        ttsTakes(100)
        val clips = FakeClipPlayer(this, millisPerChar = 100)
        val g = gateway(clips)
        val q = PhraseQueue()
        listOf("Первая фраза.", "Вторая.").forEach(q::add)
        q.close()
        val speech = async { g.speakQueue(q, Lang.RU) }
        advanceTimeBy(1_000) // вторая уже готова и ждёт
        assertEquals(2, clips.prepared.size)
        g.stopSpeaking()
        runCurrent()
        assertEquals(ServerSpeakResult.Interrupted, speech.await())
        assertEquals(1, clips.started.size)
        assertEquals(clips.prepared.toSet(), clips.released)
    }

    @Test
    fun `synthesis failure returns unspoken phrases for the builtin voice`() = runTest(StandardTestDispatcher()) {
        ttsTakes(200, failOn = "Вторая")
        val clips = FakeClipPlayer(this, millisPerChar = 50)
        val q = PhraseQueue()
        listOf("Первая.", "Вторая.", "Третья.").forEach(q::add)
        q.close()
        val r = gateway(clips).speakQueue(q, Lang.KK)
        assertTrue(r is ServerSpeakResult.Unavailable)
        r as ServerSpeakResult.Unavailable
        assertEquals(ErrorKind.SPEECH_UNAVAILABLE, r.error.kind)
        assertEquals(listOf("Первая."), clips.finished.map { it.first })
        assertEquals(listOf("Вторая.", "Третья."), r.remaining)
        assertEquals(clips.prepared.toSet(), clips.released)
    }
}
