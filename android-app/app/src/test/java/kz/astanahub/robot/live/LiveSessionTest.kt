package kz.astanahub.robot.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LiveSession: hello, микрофон, потоковый звук, перебивание, эхо, сбои — на виртуальном времени. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionTest {

    private val dispatcher = StandardTestDispatcher()

    /** [ms] звука 16 кГц PCM16. */
    private fun pcm(ms: Int) = ByteArray(ms * 32) { 1 }

    private class Harness(
        val session: LiveSession,
        val conn: FakeLiveConnection,
        val mic: FakeLiveMicrophone,
        val sink: FakeAudioSink,
        val signals: MutableList<LiveSignal>,
    )

    private fun TestScope.harness(
        conn: FakeLiveConnection = FakeLiveConnection(),
        mic: FakeLiveMicrophone = FakeLiveMicrophone(),
        connect: () -> LiveConnection? = { conn },
        levels: kz.astanahub.robot.audio.AudioLevels? = null,
    ): Harness {
        val sink = FakeAudioSink({ testScheduler.currentTime })
        val session = LiveSession(
            parent = backgroundScope,
            connect = connect,
            microphone = mic,
            sink = sink,
            clock = { testScheduler.currentTime },
            confined = dispatcher,
            io = dispatcher,
            levels = levels,
        )
        val signals = mutableListOf<LiveSignal>()
        backgroundScope.launch { session.signals.collect { signals += it } }
        return Harness(session, conn, mic, sink, signals)
    }

    @Test
    fun `server VAD drives the face - user_speaking on, user_final off`() = runTest(dispatcher) {
        val levels = kz.astanahub.robot.audio.AudioLevels { testScheduler.currentTime }
        val h = harness(levels = levels)
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        assertFalse(levels.voiceActive())
        h.conn.server(LiveEvent.UserSpeaking)
        runCurrent()
        assertTrue(levels.voiceActive())
        h.conn.server(LiveEvent.UserFinal("Где кафе?", Lang.RU))
        runCurrent()
        assertFalse(levels.voiceActive())
        h.conn.server(LiveEvent.UserPartial("Где"))
        runCurrent()
        assertTrue(levels.voiceActive())
        h.conn.server(LiveEvent.Ignored)
        runCurrent()
        assertFalse(levels.voiceActive())
    }

    @Test
    fun `directions go out only after ready and never after close`() = runTest(dispatcher) {
        val h = harness(conn = FakeLiveConnection(autoReady = null))
        val d = mapOf("cafe" to mapOf(Lang.RU to "Кафе — справа от вас, примерно в 15 метрах."))
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        assertFalse("no ready yet", h.session.sendDirections(d))
        h.conn.server(LiveEvent.Ready(LiveAudioFormat.DEFAULT))
        runCurrent()
        assertTrue(h.session.sendDirections(d))
        runCurrent()
        assertEquals(listOf(LiveFrames.directions(d)), h.conn.framesOfType("state"))
        h.session.close()
        assertFalse(h.session.sendDirections(d))
    }

    @Test
    fun `hello first, visitor_arrived after ready, mic streams 40 ms frames`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, "reception", greet = true)
        runCurrent()
        assertTrue(h.conn.texts.first(), h.conn.texts.first().contains("\"type\":\"hello\""))
        assertTrue(h.conn.texts.first().contains("\"aec\":false"))
        assertTrue(h.conn.texts.first().contains("\"place_id\":\"reception\""))
        assertEquals(1, h.conn.framesOfType("visitor_arrived").size)
        assertTrue(h.signals.contains(LiveSignal.Ready(aec = false)))
        advanceTimeBy(130); runCurrent()
        assertEquals(3, h.conn.audio.size)
        assertTrue(h.conn.audio.all { it.size == 1280 })
    }

    @Test
    fun `aec flag in hello is what the microphone really has, and echo guard depends on it`() = runTest(dispatcher) {
        val withAec = harness(conn = FakeLiveConnection(), mic = FakeLiveMicrophone(aecAvailable = true))
        withAec.session.start(Lang.RU, null, greet = false)
        runCurrent()
        assertTrue(withAec.conn.texts.first().contains("\"aec\":true"))
        assertTrue(withAec.session.aec)
        // Полный дуплекс: пока звучит ответ, «слышу» показываем.
        withAec.conn.server(LiveEvent.TurnStart("t1"))
        withAec.conn.server(LiveEvent.Audio(pcm(500)))
        advanceTimeBy(100); runCurrent()
        assertTrue(withAec.session.playing)
        withAec.conn.server(LiveEvent.UserPartial("подождите"))
        runCurrent()
        assertTrue(withAec.signals.contains(LiveSignal.UserPartial("подождите")))
    }

    @Test
    fun `without aec the robot does not show 'hearing' its own voice, but mic keeps streaming`() = runTest(dispatcher) {
        val h = harness(mic = FakeLiveMicrophone(aecAvailable = false, frames = 10))
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.Audio(pcm(300)))
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(100); runCurrent()
        assertTrue(h.session.playing)
        h.conn.server(LiveEvent.UserSpeaking)
        h.conn.server(LiveEvent.UserPartial("эхо"))
        advanceTimeBy(100); runCurrent()
        assertFalse(h.signals.any { it is LiveSignal.UserPartial || it == LiveSignal.UserSpeaking })
        assertTrue("mic is still sent during playback", h.conn.audio.size >= 4)
        advanceTimeBy(2_000); runCurrent()
        assertFalse(h.session.playing)
        h.conn.server(LiveEvent.UserPartial("человек"))
        runCurrent()
        assertTrue(h.signals.contains(LiveSignal.UserPartial("человек")))
    }

    @Test
    fun `full turn - audio plays after jitter buffer, playback_done when really finished`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        assertEquals(16_000, LiveAudioFormat.parse("pcm_s16le_16000")!!.sampleRate)
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.Emotion("happy"))
        h.conn.server(LiveEvent.Phrase("Кафе на первом этаже."))
        h.conn.server(LiveEvent.Audio(pcm(60)))
        runCurrent()
        assertNull("60 ms < jitter buffer: not playing yet", h.sink.firstPlayAt)
        h.conn.server(LiveEvent.Audio(pcm(140)))
        runCurrent()
        val playAt = h.sink.firstPlayAt!!
        assertEquals(16_000, h.sink.sampleRate)
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(150); runCurrent()
        assertTrue("200 ms of audio is not finished after 150 ms", h.conn.framesOfType("playback_done").isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("""{"type":"playback_done","turn_id":"t1"}"""), h.conn.framesOfType("playback_done"))
        assertEquals(6_400L, h.sink.totalWritten)
        val kinds = h.signals.map { it::class.simpleName }
        assertEquals(
            listOf("Ready", "TurnStart", "Emotion", "Phrase", "AudioStarted", "TurnEnd", "PlaybackDone"),
            kinds,
        )
        assertTrue(playAt >= 0)
        assertFalse(h.session.playing)
    }

    @Test
    fun `action after playback_done keeps its turn_id from the event (real server order)`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.Phrase("Провожаю, идите за мной."))
        h.conn.server(LiveEvent.Audio(pcm(200)))
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, h.conn.framesOfType("playback_done").size)
        // Сервер шлёт guide_to уже после playback_done: ход — из события, а не «текущий» (он уже null).
        h.conn.server(LiveEvent.Action("guide_to", "cafe", "t1"))
        runCurrent()
        assertEquals(LiveSignal.Action("guide_to", "cafe", "t1"), h.signals.last())
    }

    @Test
    fun `action without turn_id - current turn while playing, null between turns`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.Action("where_is", "cafe"))
        runCurrent()
        assertEquals(LiveSignal.Action("where_is", "cafe", "t1"), h.signals.last())
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(500); runCurrent()
        h.conn.server(LiveEvent.Action("guide_to", "cafe"))
        runCurrent()
        assertEquals(LiveSignal.Action("guide_to", "cafe", null), h.signals.last())
    }

    @Test
    fun `action of an interrupted turn is dropped, action of an earlier finished turn is not`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t2"))
        h.conn.server(LiveEvent.Audio(pcm(800)))
        advanceTimeBy(200); runCurrent()
        h.conn.server(LiveEvent.Interrupted("t2"))
        h.conn.server(LiveEvent.Action("guide_to", "cafe", "t2"))
        h.conn.server(LiveEvent.Action("guide_to", "lift", "t1"))
        runCurrent()
        val actions = h.signals.filterIsInstance<LiveSignal.Action>()
        assertEquals(listOf(LiveSignal.Action("guide_to", "lift", "t1")), actions)
    }

    @Test
    fun `turn without audio is done at turn_end`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        runCurrent()
        assertEquals(1, h.conn.framesOfType("playback_done").size)
    }

    @Test
    fun `interrupted - instant flush, not a single sample of the old turn after it, next turn plays`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        repeat(10) { h.conn.server(LiveEvent.Audio(pcm(100))) }
        advanceTimeBy(200); runCurrent()
        assertTrue(h.session.playing)
        val flushesBefore = h.sink.flushes
        h.conn.server(LiveEvent.Interrupted("t1"))
        runCurrent()
        assertEquals(flushesBefore + 1, h.sink.flushes)
        assertFalse(h.sink.playing)
        assertFalse(h.session.playing)
        // Звук и фразы оборванного хода, что ещё в пути, — выбрасываем.
        h.conn.server(LiveEvent.Audio(pcm(100)))
        h.conn.server(LiveEvent.Phrase("…хвост"))
        h.conn.server(LiveEvent.TurnEnd("t1", Lang.RU, true))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0L, h.sink.writtenSinceFlush)
        assertTrue(h.conn.framesOfType("playback_done").isEmpty())
        assertFalse(h.signals.contains(LiveSignal.Phrase("…хвост")))
        assertTrue(h.signals.contains(LiveSignal.Interrupted("t1", local = false)))
        // Новый ход играет как обычно.
        h.conn.server(LiveEvent.TurnStart("t2"))
        h.conn.server(LiveEvent.Audio(pcm(200)))
        h.conn.server(LiveEvent.TurnEnd("t2", Lang.RU, true))
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf("""{"type":"playback_done","turn_id":"t2"}"""), h.conn.framesOfType("playback_done"))
    }

    @Test
    fun `local stop - flush and interrupt frame to the server`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.TurnStart("t1"))
        h.conn.server(LiveEvent.Audio(pcm(600)))
        advanceTimeBy(100); runCurrent()
        h.session.interrupt()
        runCurrent()
        assertEquals(1, h.conn.framesOfType("interrupt").size)
        assertFalse(h.sink.playing)
        assertEquals(0L, h.sink.writtenSinceFlush)
        assertTrue(h.signals.contains(LiveSignal.Interrupted("t1", local = true)))
        h.conn.server(LiveEvent.Audio(pcm(100)))
        advanceTimeBy(500); runCurrent()
        assertEquals(0L, h.sink.writtenSinceFlush)
    }

    @Test
    fun `robot's own local speech - silence goes instead of the microphone`() = runTest(dispatcher) {
        val h = harness(mic = FakeLiveMicrophone(frames = 4))
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        advanceTimeBy(50); runCurrent()
        h.session.setLocalSpeech(true)
        advanceTimeBy(200); runCurrent()
        assertTrue(h.conn.audio.first().all { it == 7.toByte() })
        assertTrue(h.conn.audio.drop(1).all { frame -> frame.all { it == 0.toByte() } })
    }

    @Test
    fun `server error - Failed, connection and mic closed`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.Error(BackendError(ErrorKind.LLM_UNAVAILABLE)))
        advanceTimeBy(500); runCurrent()
        assertEquals(ErrorKind.LLM_UNAVAILABLE, (h.signals.last() as LiveSignal.Failed).error.kind)
        assertTrue(h.conn.closed)
        assertEquals(1, h.mic.closed)
        assertFalse(h.session.active)
    }

    @Test
    fun `connection drop - Failed`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.conn.server(LiveEvent.Closed(BackendError(ErrorKind.NETWORK)))
        runCurrent()
        assertEquals(ErrorKind.NETWORK, (h.signals.last() as LiveSignal.Failed).error.kind)
    }

    @Test
    fun `no ready within timeout - Failed`() = runTest(dispatcher) {
        val h = harness(conn = FakeLiveConnection(autoReady = null))
        h.session.start(Lang.RU, null, greet = false)
        advanceTimeBy(4_900); runCurrent()
        assertTrue(h.signals.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(ErrorKind.TIMEOUT, (h.signals.single() as LiveSignal.Failed).error.kind)
    }

    @Test
    fun `server without live - Failed immediately`() = runTest(dispatcher) {
        val h = harness(connect = { null })
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        assertEquals(ErrorKind.NOT_CONFIGURED, (h.signals.single() as LiveSignal.Failed).error.kind)
    }

    @Test
    fun `microphone does not open - Failed after retries`() = runTest(dispatcher) {
        val h = harness(mic = FakeLiveMicrophone(failOpen = true))
        h.session.start(Lang.RU, null, greet = false)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(h.signals.single() is LiveSignal.Failed)
        assertTrue(h.conn.closed)
    }

    @Test
    fun `close - no Failed, everything released`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        advanceTimeBy(50); runCurrent()
        h.session.close()
        advanceTimeBy(500); runCurrent()
        assertTrue(h.conn.closed)
        assertEquals(1, h.mic.closed)
        assertFalse(h.signals.any { it is LiveSignal.Failed })
    }

    @Test
    fun `navigation state frame`() = runTest(dispatcher) {
        val h = harness()
        h.session.start(Lang.RU, null, greet = false)
        runCurrent()
        h.session.sendState("cafe", navigating = true)
        runCurrent()
        assertEquals(listOf("""{"type":"state","place_id":"cafe","navigating":true}"""), h.conn.framesOfType("state"))
    }
}
