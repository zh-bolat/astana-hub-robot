package kz.astanahub.robot.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16-bit little-endian → WAV (RIFF, 44-байтный заголовок). Формат /v1/stt: 16 кГц, mono. */
object WavEncoder {
    const val HEADER_SIZE = 44

    fun encode(pcm: ShortArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val dataSize = pcm.size * 2
        val byteRate = sampleRate * channels * 2
        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // размер fmt-чанка для PCM
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * 2).toShort()) // block align
        buffer.putShort(16) // бит на сэмпл
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (s in pcm) buffer.putShort(s)
        return buffer.array()
    }
}
