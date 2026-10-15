package kz.astanahub.robot.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {

    @Test
    fun `header matches PCM16 mono 16 kHz and data is little-endian`() {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = WavEncoder.encode(pcm, 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size * 2, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, b.getInt(16))
        assertEquals(1.toShort(), b.getShort(20)) // PCM
        assertEquals(1.toShort(), b.getShort(22)) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2.toShort(), b.getShort(32)) // block align
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size * 2, b.getInt(40))
        pcm.forEachIndexed { i, s -> assertEquals(s, b.getShort(44 + i * 2)) }
    }

    @Test
    fun `empty pcm gives bare header`() {
        assertEquals(44, WavEncoder.encode(ShortArray(0)).size)
    }
}
