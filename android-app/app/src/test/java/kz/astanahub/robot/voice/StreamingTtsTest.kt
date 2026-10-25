package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.BytesTtsStream
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.TtsStream
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Потоковый /v1/tts: звук с первых байт, обрыв посреди потока — дозапрос без stream, кеш — без сети. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingTtsTest {

    private val backend = mockk<BackendApi> { every { openLiveStt(any(), any()) } returns null; every { supportsLiveStt } returns false }

    /** Отдаёт первые [breakAfter] байт, потом обрыв соединения. */
    private class BrokenStream(private val bytes: ByteArray, private val breakAfter: Int) : TtsStream {
        private var sent = false
        override fun read(buffer: ByteArray): Int {
            if (sent) throw IOException("connection reset")
            sent = true
            System.arraycopy(bytes, 0, buffer, 0, breakAfter)
            return breakAfter
        }
        override fun close() = Unit
    }

    private fun TestScope.gateway(clips: FakeClipPlayer, cache: PhraseAudioCache? = null) = ServerVoiceGateway(
        backend, mockk(relaxed = true), clips, scope = backgroundScope, cache = cache,
        streaming = { true }, ioDispatcher = Dispatchers.IO,
    )

    @Test
    fun `streaming request is used and the clip plays`() = runTest {
        coEvery { backend.ttsStream(any(), any()) } answers { ApiResult.Success(BytesTtsStream(firstArg<String>().toByteArray())) }
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val result = gateway(clips).speakDetailed(listOf("Привет из потока."), Lang.RU)
        assertEquals(ServerSpeakResult.Done, result)
        assertEquals(listOf("Привет из потока."), clips.started.map { it.first })
        coVerify(exactly = 0) { backend.tts(any(), any()) }
    }

    @Test
    fun `stream broken in the middle - phrase refetched without stream, tail appended`() = runTest {
        val text = "Обрыв посреди фразы."
        val full = text.toByteArray()
        coEvery { backend.ttsStream(any(), any()) } returns ApiResult.Success(BrokenStream(full, breakAfter = 6))
        coEvery { backend.tts(text, Lang.RU) } returns ApiResult.Success(full)
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        assertEquals(ServerSpeakResult.Done, gateway(clips).speakDetailed(listOf(text), Lang.RU))
        assertEquals(listOf(text), clips.started.map { it.first }) // без повтора начала
        coVerify(exactly = 1) { backend.tts(text, Lang.RU) }
    }

    @Test
    fun `stream error before first bytes is retried once`() = runTest {
        var calls = 0
        coEvery { backend.ttsStream(any(), any()) } answers {
            calls++
            if (calls == 1) ApiResult.Failure(BackendError(ErrorKind.SPEECH_UNAVAILABLE))
            else ApiResult.Success(BytesTtsStream(firstArg<String>().toByteArray()))
        }
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        assertEquals(ServerSpeakResult.Done, gateway(clips).speakDetailed(listOf("Со второй попытки."), Lang.RU))
        assertEquals(2, calls)
        assertEquals(listOf("Со второй попытки."), clips.started.map { it.first })
    }

    @Test
    fun `two failures make the chunk unavailable`() = runTest {
        coEvery { backend.ttsStream(any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.TIMEOUT))
        val result = gateway(FakeClipPlayer(this)).speakDetailed(listOf("Нет."), Lang.RU)
        assertTrue(result is ServerSpeakResult.Unavailable)
        coVerify(exactly = 2) { backend.ttsStream(any(), any()) }
    }

    @Test
    fun `cached phrase is played from file even with streaming on`() = runTest {
        val cache = PhraseAudioCache(MemoryAudioStore())
        cache.put("Секунду…", Lang.RU, "Секунду…".toByteArray())
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        assertEquals(ServerSpeakResult.Done, gateway(clips, cache).speakDetailed(listOf("Секунду…"), Lang.RU))
        coVerify(exactly = 0) { backend.ttsStream(any(), any()) }
        assertEquals(listOf("Секунду…"), clips.started.map { it.first })
    }

    @Test
    fun `several cached phrases play one after another and every clip is released`() = runTest {
        val cache = PhraseAudioCache(MemoryAudioStore())
        listOf("Раз.", "Два.").forEach { cache.put(it, Lang.RU, it.toByteArray()) }
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val queue = PhraseQueue().apply { add("Раз."); add("Два."); close() }
        assertEquals(ServerSpeakResult.Done, gateway(clips, cache).speakQueue(queue, Lang.RU))
        assertEquals(listOf("Раз.", "Два."), clips.started.map { it.first })
        assertEquals(clips.prepared.toSet(), clips.released)
    }
}
