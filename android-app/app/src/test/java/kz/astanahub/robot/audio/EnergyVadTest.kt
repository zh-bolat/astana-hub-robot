package kz.astanahub.robot.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {

    private val config = VadConfig()
    private val frameSize = config.frameSamples // 320 сэмплов = 20 мс
    private val random = Random(42)

    private fun silence(ms: Int, amplitude: Int = 60): List<ShortArray> =
        frames(ms) { random.nextInt(-amplitude, amplitude + 1).toShort() }

    private fun tone(ms: Int, amplitude: Double = 6_000.0): List<ShortArray> {
        var n = 0
        return frames(ms) { (amplitude * sin(2 * PI * 220 * (n++) / config.sampleRate)).toInt().toShort() }
    }

    private fun frames(ms: Int, sample: () -> Short): List<ShortArray> =
        List(ms / config.frameMillis) { ShortArray(frameSize) { sample() } }

    private fun run(vad: EnergyVad, frames: List<ShortArray>): List<VadEvent> =
        frames.map { vad.accept(it) }.filter { it != VadEvent.NONE }

    @Test
    fun `speech between silences is detected and ends after 800 ms of silence`() {
        val vad = EnergyVad(config)
        val events = run(vad, silence(500) + tone(1_000) + silence(1_000))

        assertEquals(listOf(VadEvent.SPEECH_STARTED, VadEvent.SPEECH_ENDED), events)
        assertTrue(vad.finished)
        // ≈ 0.3 с пре-ролла + 1 с речи + ≤ 0.2 с хвоста, без всей паузы в 0.8 с.
        val ms = vad.utterance().size * 1000 / config.sampleRate
        assertTrue("utterance $ms ms", ms in 1_100..1_600)
    }

    @Test
    fun `end is not triggered by a short pause inside a phrase`() {
        val vad = EnergyVad(config)
        val events = run(vad, silence(300) + tone(500) + silence(400) + tone(500))
        assertEquals(listOf(VadEvent.SPEECH_STARTED), events)
        assertFalse(vad.finished)
    }

    @Test
    fun `short click does not start speech`() {
        val vad = EnergyVad(config)
        val events = run(vad, silence(300) + tone(40) + silence(500))
        assertTrue(events.isEmpty())
        assertFalse(vad.isSpeaking)
    }

    @Test
    fun `only silence ends with NO_SPEECH after timeout`() {
        val vad = EnergyVad(config.copy(noSpeechTimeoutMillis = 2_000))
        val events = run(vad, silence(3_000))
        assertEquals(listOf(VadEvent.NO_SPEECH), events)
    }

    @Test
    fun `continuous speech is cut at max length`() {
        val vad = EnergyVad(config.copy(maxUtteranceMillis = 3_000))
        val events = run(vad, silence(200) + tone(5_000))
        assertEquals(listOf(VadEvent.SPEECH_STARTED, VadEvent.MAX_LENGTH), events)
        assertTrue(vad.utterance().size <= 3_000 * config.sampleRate / 1000)
    }

    @Test
    fun `threshold adapts to noisy room`() {
        // Фон ≈ RMS 600 (выше minSpeechRms) не должен считаться речью.
        val vad = EnergyVad(config)
        val noisy = silence(1_000, amplitude = 1_000)
        assertTrue(run(vad, noisy).isEmpty())
        val events = run(vad, tone(600, amplitude = 12_000.0))
        assertEquals(listOf(VadEvent.SPEECH_STARTED), events)
    }

    @Test
    fun `rms of a sine is amplitude over sqrt 2`() {
        val frame = tone(20, amplitude = 10_000.0).first()
        assertEquals(10_000 / kotlin.math.sqrt(2.0), EnergyVad.rms(frame), 300.0)
    }
}
