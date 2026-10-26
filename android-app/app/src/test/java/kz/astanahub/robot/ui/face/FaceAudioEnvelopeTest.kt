package kz.astanahub.robot.ui.face

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAudioEnvelopeTest {

    private val dt = 1f / 60f

    private fun FaceAudioEnvelope.feed(level: Float, ms: Int): Float {
        var v = value
        repeat(ms * 60 / 1000) { v = step(level, dt) }
        return v
    }

    @Test
    fun `attack is fast and release is slower`() {
        val env = FaceAudioEnvelope.playback()
        val attack = env.feed(0.2f, 50)
        assertTrue("attack $attack", attack > 0.8f)
        env.feed(0.2f, 500)
        val afterRelease = env.feed(0f, 50)
        assertTrue("release keeps some opening after 50 ms: $afterRelease", afterRelease > 0.2f)
        assertTrue(env.feed(0f, 500) < 0.02f)
    }

    @Test
    fun `noise floor is silence`() {
        val env = FaceAudioEnvelope.mic()
        assertEquals(0f, env.feed(0.008f, 1_000), 0f)
    }

    @Test
    fun `quiet but real speech is normalized up, never above 1`() {
        val env = FaceAudioEnvelope.playback()
        var max = 0f
        repeat(300) { i ->
            val level = if (i % 12 < 6) 0.05f else 0.01f
            max = maxOf(max, env.step(level, dt))
        }
        assertTrue("max $max", max in 0.25f..1f)
    }

    @Test
    fun `a one-frame spike opens only partially and decays`() {
        val env = FaceAudioEnvelope.playback()
        env.feed(0f, 200)
        val spike = env.step(0.2f, dt)
        assertTrue("spike $spike", spike < 0.7f)
        var prev = spike
        repeat(30) {
            val v = env.step(0f, dt)
            assertTrue(v <= prev)
            prev = v
        }
        assertTrue(prev < 0.05f)
    }

    @Test
    fun `zero dt changes nothing`() {
        val env = FaceAudioEnvelope.playback()
        env.feed(0.2f, 100)
        val v = env.value
        assertEquals(v, env.step(1f, 0f), 0f)
    }

    @Test
    fun `speech synth has syllables and pauses and stays bounded`() {
        val synth = SpeechSynth(Random(5))
        var now = 0L
        var zeros = 0
        var rises = 0
        var prev = 0f
        var max = 0f
        repeat(60 * 10) {
            now += 16
            val v = synth.level(now, active = true)
            assertTrue(v in 0f..0.21f)
            if (v == 0f) zeros++
            if (prev < 0.02f && v >= 0.02f) rises++
            max = maxOf(max, v)
            prev = v
        }
        // ~3.5–6 слогов в секунду, с паузами между словами.
        assertTrue("syllables in 10 s: $rises", rises in 25..70)
        assertTrue("pauses: $zeros", zeros > 20)
        assertTrue(max > 0.1f)
        assertEquals(0f, synth.level(now + 16, active = false), 0f)
    }
}
