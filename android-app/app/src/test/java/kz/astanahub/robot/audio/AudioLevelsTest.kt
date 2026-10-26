package kz.astanahub.robot.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelsTest {

    private var now = 1_000L
    private val levels = AudioLevels { now }

    private fun pcm(seconds: Float, rate: Int, amplitude: (Int) -> Double): ByteArray {
        val n = (seconds * rate).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (amplitude(i) * 32767).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = (s shr 8 and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `rms of pcm16`() {
        val silence = ByteArray(960)
        assertEquals(0f, AudioLevels.rmsPcm16(silence, 0, silence.size), 0f)
        val sine = pcm(0.1f, 16_000) { 0.5 * sin(2 * PI * 440 * it / 16_000) }
        assertEquals((0.5 / sqrt(2.0)).toFloat(), AudioLevels.rmsPcm16(sine, 0, sine.size), 0.01f)
        val square = pcm(0.1f, 16_000) { if (it % 20 < 10) 1.0 else -1.0 }
        assertEquals(1f, AudioLevels.rmsPcm16(square, 0, square.size), 0.01f)
        assertEquals(0f, AudioLevels.rmsPcm16(square, 0, 1), 0f)
    }

    @Test
    fun `rms of visualizer bytes`() {
        assertEquals(0f, AudioLevels.rmsU8(ByteArray(64) { 128.toByte() }), 0f)
        assertEquals(1f, AudioLevels.rmsU8(ByteArray(64) { if (it % 2 == 0) 0 else 255.toByte() }), 0.01f)
    }

    @Test
    fun `mic level is fresh for 250 ms only`() {
        levels.reportMic(0.3f)
        assertEquals(0.3f, levels.micLevel(), 0f)
        now += 200
        assertEquals(0.3f, levels.micLevel(), 0f)
        now += 100
        assertEquals(0f, levels.micLevel(), 0f)
    }

    @Test
    fun `playback is unmeasured by default and fresh visualizer level wins until stale`() {
        assertEquals(-1f, levels.playbackLevel(), 0f)
        levels.reportPlayback(0.12f)
        assertEquals(0.12f, levels.playbackLevel(), 0f)
        now += 300
        assertEquals(-1f, levels.playbackLevel(), 0f)
    }

    @Test
    fun `live pcm level follows what is actually playing, not what is written`() {
        val meter = levels.pcm
        val rate = 24_000
        meter.configure(rate)
        // Записано вперёд: 0.5 с громко, затем 0.5 с тихо.
        val loud = pcm(0.5f, rate) { if (it % 2 == 0) 0.5 else -0.5 }
        val quiet = pcm(0.5f, rate) { if (it % 2 == 0) 0.05 else -0.05 }
        meter.onWritten(loud, 0, loud.size, firstFrame = 0)
        meter.onWritten(quiet, 0, quiet.size, firstFrame = (rate / 2).toLong())
        // Пока не играет — уровня нет.
        assertEquals(-1f, levels.playbackLevel(), 0f)
        meter.onPlayhead(0, active = true)
        now += 100
        assertEquals(0.5f, levels.playbackLevel(), 0.01f)
        // Позиция экстраполируется по часам — через 0.7 с звучит тихий кусок.
        now += 600
        assertEquals(0.05f, levels.playbackLevel(), 0.01f)
        // Дальше записанного не уходим.
        now += 5_000
        assertEquals(0.05f, levels.playbackLevel(), 0.01f)
        // Сброс (перебили / ход доигран) — снова «не играет».
        meter.reset()
        assertEquals(-1f, levels.playbackLevel(), 0f)
    }

    @Test
    fun `playhead update corrects the estimate`() {
        val meter = levels.pcm
        meter.configure(16_000)
        val a = pcm(0.2f, 16_000) { if (it % 2 == 0) 0.4 else -0.4 }
        val b = pcm(0.2f, 16_000) { 0.0 }
        meter.onWritten(a, 0, a.size, 0)
        meter.onWritten(b, 0, b.size, 3_200)
        meter.onPlayhead(0, true)
        now += 300 // по часам уже тишина
        meter.onPlayhead(1_000, true) // но выход отстаёт: играет 1000-й кадр (громкий)
        assertEquals(0.4f, levels.playbackLevel(), 0.01f)
    }

    @Test
    fun `vad voice is reported, cleared and goes stale`() {
        assertEquals(false, levels.voiceActive())
        levels.reportVoice(true)
        assertEquals(true, levels.voiceActive())
        now += 1_000
        levels.reportVoice(false)
        assertEquals(false, levels.voiceActive())
        levels.reportVoice(true)
        now += AudioLevels.VOICE_STALE_MS + 1
        assertEquals("source died without saying 'stopped'", false, levels.voiceActive())
    }

    @Test
    fun `debug hall noise is loud but not a voice`() {
        levels.debugNoise = true
        assertEquals(AudioLevels.DEBUG_NOISE_RMS, levels.micLevel(), 0f)
        assertEquals(false, levels.voiceActive())
        levels.debugNoise = false
    }

    @Test
    fun `debug voice produces a varying level`() {
        levels.debugMic = true
        val values = (0 until 100).map { now += 16; levels.micLevel() }
        assertTrue(values.max() > 0.04f)
        assertTrue(values.any { it == 0f } || values.min() < values.max() / 3)
        assertTrue(levels.voiceActive())
        levels.debugMic = false
        assertEquals(0f, levels.micLevel(), 0f)
    }
}
