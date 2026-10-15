package kz.astanahub.robot.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MicRecorderTest {
    @Test
    fun `left channel is extracted from interleaved stereo`() {
        val stereo = shortArrayOf(1, 100, 2, 200, 3, 300)
        assertArrayEquals(shortArrayOf(1, 2, 3), MicRecorder.leftChannel(stereo, 2))
        assertArrayEquals(stereo, MicRecorder.leftChannel(stereo, 1))
    }
}
