package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.WayPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Только голос ElevenLabs: при VOICE_BUILTIN_FALLBACK=false встроенный TTS не зовётся никогда; сбой /v1/tts →
 * 1 повтор → субтитры без звука + «не могу сейчас говорить» из кеша (не чаще раза в минуту); фразы из кеша — без сети.
 * Настоящий ServerVoiceGateway + проигрыватель-дублёр, виртуальное время.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElevenLabsOnlyVoiceTest {

    private val phrases = Phrases { key, lang -> "${key.name}:${lang.code}." }
    private val cantSpeak = phrases.get(PhraseKey.CANT_SPEAK, Lang.RU)
    private val backend = mockk<BackendApi> { every { openLiveStt(any(), any()) } returns null }
    private val config = MutableStateFlow(VoiceConfig())
    private val settings = mockk<VoiceSettings> { every { config } returns this@ElevenLabsOnlyVoiceTest.config }
    private val builtinTts = mockk<VoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
    }
    private val builtinAsr = mockk<VoiceGateway>(relaxed = true) {
        every { transcripts } returns emptyFlow()
        every { activity } returns MutableStateFlow(VoiceActivity.IDLE)
    }
    private val cache = PhraseAudioCache(MemoryAudioStore())
    private val ttsCalls = mutableListOf<String>()

    private fun ttsFails(kind: ErrorKind = ErrorKind.NETWORK) {
        coEvery { backend.tts(any(), any()) } coAnswers {
            ttsCalls += firstArg<String>()
            delay(100)
            ApiResult.Failure(BackendError(kind))
        }
    }

    private fun ttsWorks() {
        coEvery { backend.tts(any(), any()) } coAnswers {
            ttsCalls += firstArg<String>()
            delay(100)
            ApiResult.Success(firstArg<String>().toByteArray())
        }
    }

    private class Subtitles : SpeechListener {
        val shown = mutableListOf<String>()
        override fun onChunkStart(chunk: SpeechChunk) { shown += chunk.text }
    }

    private fun TestScope.setup(clips: FakeClipPlayer): RoutingVoiceGateway {
        val server = ServerVoiceGateway(backend, mockk(relaxed = true), clips, scope = backgroundScope, cache = cache)
        return RoutingVoiceGateway(
            builtinAsr, builtinTts, server, settings, mockk(relaxed = true), continuousServerAsr = false,
            builtinAsrAvailable = true, scope = backgroundScope, cache = cache, phrases = phrases,
            now = { currentTime },
        )
    }

    @Test
    fun `defaults - every language goes to the server voice, builtin TTS is never called`() = runTest(StandardTestDispatcher()) {
        ttsWorks()
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val r = setup(clips)
        Lang.entries.forEach { lang -> assertTrue(r.speak(listOf("Фраза ${lang.code}."), lang)) }
        assertEquals(listOf("Фраза ru.", "Фраза kk.", "Фраза en."), clips.started.map { it.first })
        coVerify(exactly = 0) { builtinTts.speak(any(), any()) }
    }

    @Test
    fun `tts failure - one retry, then subtitles without sound and cached cant-speak phrase`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        cache.put(cantSpeak, Lang.RU, cantSpeak.toByteArray())
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val r = setup(clips)
        val subs = Subtitles()
        val ok = r.speakStream(channelOf("Ответ без звука."), Lang.RU, subs)
        assertTrue(ok) // текст показан до конца
        assertEquals(listOf("Ответ без звука.", "Ответ без звука."), ttsCalls) // запрос + 1 повтор
        assertEquals(listOf("Ответ без звука."), subs.shown)
        assertEquals(listOf(cantSpeak), clips.started.map { it.first })
        coVerify(exactly = 0) { builtinTts.speak(any(), any()) }
    }

    @Test
    fun `fake speech provider is treated as failure too`() = runTest(StandardTestDispatcher()) {
        ttsFails(ErrorKind.SPEECH_UNAVAILABLE)
        val clips = FakeClipPlayer(this)
        val r = setup(clips)
        r.speak(listOf("Ответ."), Lang.RU)
        assertEquals(2, ttsCalls.size)
        assertTrue(clips.started.isEmpty()) // фразы «не могу» в кеше нет — молча
        coVerify(exactly = 0) { builtinTts.speak(any(), any()) }
    }

    @Test
    fun `cant-speak phrase is not repeated within a minute`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        cache.put(cantSpeak, Lang.RU, cantSpeak.toByteArray())
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val r = setup(clips)
        r.speak(listOf("Раз."), Lang.RU)
        r.speak(listOf("Два."), Lang.RU)
        assertEquals(1, clips.started.count { it.first == cantSpeak })
        delay(RoutingVoiceGateway.CANT_SPEAK_INTERVAL_MS)
        r.speak(listOf("Три."), Lang.RU)
        assertEquals(2, clips.started.count { it.first == cantSpeak })
    }

    @Test
    fun `silent subtitle is held for reading time`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        val r = setup(FakeClipPlayer(this))
        val start = currentTime
        r.speak(listOf("Ответ."), Lang.RU)
        // 2 попытки по 100 мс + удержание текста 1,2 с (короткая фраза)
        assertEquals(200L + 1_200L, currentTime - start)
    }

    @Test
    fun `cached local phrase plays from file without network`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        val noConnection = phrases.get(PhraseKey.NO_CONNECTION, Lang.RU)
        cache.put(noConnection, Lang.RU, noConnection.toByteArray())
        val clips = FakeClipPlayer(this, millisPerChar = 10)
        val r = setup(clips)
        assertTrue(r.speak(listOf(noConnection), Lang.RU))
        assertTrue(ttsCalls.isEmpty())
        assertEquals(listOf(noConnection), clips.started.map { it.first })
    }

    @Test
    fun `guide phrase - cached first sentence starts at once, the name is synthesized`() = runTest(StandardTestDispatcher()) {
        ttsWorks()
        cache.put(WayPhrases.followMe(Lang.RU), Lang.RU, WayPhrases.followMe(Lang.RU).toByteArray())
        val clips = FakeClipPlayer(this, millisPerChar = 50)
        val r = setup(clips)
        r.speak(listOf(WayPhrases.guideStart("Кафе", Lang.RU)), Lang.RU)
        assertEquals(listOf("Идите за мной.", "Провожу: Кафе."), clips.started.map { it.first })
        assertEquals(0L, clips.started.first().second) // из кеша — без ожидания сети
        assertEquals(listOf("Провожу: Кафе."), ttsCalls)
    }

    @Test
    fun `first launch offline without cache - text only, no builtin voice`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        val clips = FakeClipPlayer(this)
        val r = setup(clips)
        val subs = Subtitles()
        r.speakStream(channelOf(phrases.get(PhraseKey.NO_CONNECTION, Lang.RU)), Lang.RU, subs)
        assertTrue(clips.started.isEmpty())
        assertEquals(1, subs.shown.size)
        coVerify(exactly = 0) { builtinTts.speak(any(), any()) }
    }

    @Test
    fun `builtin fallback enabled explicitly - remaining phrases go to the builtin voice`() = runTest(StandardTestDispatcher()) {
        config.value = VoiceConfig(builtinFallback = true)
        ttsFails()
        coEvery { builtinTts.speak(any(), any()) } returns true
        val r = setup(FakeClipPlayer(this))
        r.speak(listOf("Ответ."), Lang.RU)
        coVerify { builtinTts.speak(listOf("Ответ."), Lang.RU) }
    }

    @Test
    fun `offline STT does not switch to builtin ASR unless fallback is enabled`() = runTest(StandardTestDispatcher()) {
        ttsWorks()
        val r = setup(FakeClipPlayer(this))
        runCurrent()
        assertEquals(VoiceSource.SERVER, r.effectiveAsr())
    }

    @Test
    fun `filler from cache plays only if cached and raises the speech guard`() = runTest(StandardTestDispatcher()) {
        ttsWorks()
        val hm = phrases.get(PhraseKey.FILLER_HM, Lang.RU)
        val clips = FakeClipPlayer(this, millisPerChar = 50)
        val r = setup(clips)
        assertFalse(r.speakCached(hm, Lang.RU)) // кеша нет — тишина, без сети
        assertTrue(ttsCalls.isEmpty())
        cache.put(hm, Lang.RU, hm.toByteArray())
        val playing = async { r.speakCached(hm, Lang.RU) }
        runCurrent()
        assertTrue(r.speechGuard.value)
        assertTrue(playing.await())
        assertEquals(listOf(hm), clips.started.map { it.first })
        assertTrue(ttsCalls.isEmpty())
    }

    @Test
    fun `speech events - first byte and audio start for metrics`() = runTest(StandardTestDispatcher()) {
        ttsWorks()
        val r = setup(FakeClipPlayer(this))
        val events = mutableListOf<Pair<SpeechEvent, Long>>()
        backgroundScope.launch { r.speechEvents.collect { events += it to currentTime } }
        runCurrent()
        r.speak(listOf("Да."), Lang.RU)
        assertEquals(listOf(SpeechEvent.TTS_FIRST_BYTE to 100L, SpeechEvent.AUDIO_START to 100L), events)
    }

    @Test
    fun `stop during silent subtitles ends at once`() = runTest(StandardTestDispatcher()) {
        ttsFails()
        val r = setup(FakeClipPlayer(this))
        val speech = async { r.speak(listOf("Длинный ответ, который показываем без звука."), Lang.RU) }
        delay(500)
        r.stopSpeaking()
        runCurrent()
        assertFalse(speech.await())
        advanceUntilIdle()
    }

    private fun channelOf(vararg items: String) =
        kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED).apply {
            items.forEach { trySend(it) }
            close()
        }
}
