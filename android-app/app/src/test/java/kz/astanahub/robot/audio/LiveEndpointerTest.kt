package kz.astanahub.robot.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VAD 0,5 с по умолчанию (настраивается) и конец фразы для live STT по кускам PCM16 LE. */
class LiveEndpointerTest {
    private val random = Random(7)

    private fun pcm(ms: Int, sample: (Int) -> Int): ShortArray = ShortArray(16 * ms) { sample(it) .toShort() }
    private fun silence(ms: Int) = pcm(ms) { random.nextInt(-60, 61) }
    private fun tone(ms: Int) = pcm(ms) { (6_000 * sin(2 * PI * 220 * it / 16_000)).toInt() }

    private fun frames(samples: ShortArray, frame: Int = 320): List<ShortArray> =
        samples.toList().chunked(frame).map { it.toShortArray() }

    /** В какую миллисекунду тишины VAD закончил фразу. */
    private fun endAfterSilence(config: VadConfig): Int? {
        val vad = EnergyVad(config)
        frames(silence(300) + tone(800)).forEach { vad.accept(it) }
        frames(silence(2_000)).forEachIndexed { i, f ->
            if (vad.accept(f) == VadEvent.SPEECH_ENDED) return (i + 1) * 20
        }
        return null
    }

    @Test
    fun `default end of phrase is 0_5 s of silence instead of 0_8`() {
        assertEquals(500, VadConfig().endSilenceMillis)
        assertEquals(500, endAfterSilence(VadConfig()))
    }

    @Test
    fun `end of phrase silence is configurable`() {
        assertEquals(800, endAfterSilence(VadConfig(endSilenceMillis = 800)))
        assertEquals(300, endAfterSilence(VadConfig(endSilenceMillis = 300)))
    }

    private fun le(samples: ShortArray): ByteArray = MicRecorder.toLittleEndian(samples)

    @Test
    fun `live endpointer fires once after 0_5 s of silence on 100 ms chunks`() {
        val ep = LiveEndpointer(VadConfig())
        val audio = le(silence(300) + tone(1_000) + silence(1_000))
        val chunks = audio.toList().chunked(3_200).map { it.toByteArray() } // 100 мс
        val firedAt = chunks.indexOfFirst { ep.accept(it) }
        assertTrue(ep.ended)
        // речь кончилась на 1300 мс, конец — через 500 мс тишины → в куске, покрывающем 1800 мс
        assertTrue("fired at chunk $firedAt", firedAt == 17)
        assertFalse(chunks.drop(firedAt + 1).any { ep.accept(it) })
    }

    @Test
    fun `live endpointer handles odd chunk sizes`() {
        val ep = LiveEndpointer(VadConfig())
        val audio = le(silence(300) + tone(600) + silence(800))
        var fired: Int? = null
        audio.toList().chunked(777).map { it.toByteArray() }.forEachIndexed { i, c -> if (ep.accept(c) && fired == null) fired = i }
        assertNotNull(fired)
    }

    @Test
    fun `no speech - never fires (server closes the phrase)`() {
        val ep = LiveEndpointer(VadConfig())
        val audio = le(silence(3_000))
        assertFalse(audio.toList().chunked(3_200).any { ep.accept(it.toByteArray()) })
    }
}
