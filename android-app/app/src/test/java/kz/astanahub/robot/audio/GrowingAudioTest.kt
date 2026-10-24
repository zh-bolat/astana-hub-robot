package kz.astanahub.robot.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Растущий буфер потокового TTS: чтение ждёт байты, конец/обрыв, дописывание хвоста после дозапроса. */
class GrowingAudioTest {

    @Test
    fun `reader waits for bytes that arrive later`() {
        val audio = GrowingAudio(initialCapacity = 4)
        val got = ByteArray(3)
        val read = CountDownLatch(1)
        var n = 0
        val reader = thread {
            n = audio.readAt(0, got, 0, 3, timeoutMillis = 5_000)
            read.countDown()
        }
        assertFalse(read.await(100, TimeUnit.MILLISECONDS)) // байтов ещё нет — ждёт
        audio.append(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(read.await(2, TimeUnit.SECONDS))
        reader.join()
        assertEquals(3, n)
        assertArrayEquals(byteArrayOf(1, 2, 3), got)
        assertTrue(audio.firstByteAtNanos > 0)
    }

    @Test
    fun `end of stream returns -1 after the last byte`() {
        val audio = GrowingAudio()
        audio.append(byteArrayOf(9, 8))
        audio.complete()
        val buf = ByteArray(10)
        assertEquals(2, audio.readAt(0, buf, 0, 10, 1_000))
        assertEquals(-1, audio.readAt(2, buf, 0, 10, 1_000))
    }

    @Test
    fun `reader times out when the stream stalls`() {
        val audio = GrowingAudio()
        assertEquals(-1, audio.readAt(0, ByteArray(4), 0, 4, timeoutMillis = 50))
        assertFalse(audio.awaitFirstByte(50))
        assertNull(audio.awaitAll(50))
    }

    @Test
    fun `broken stream is completed with the tail of the full phrase`() {
        val full = ByteArray(100) { it.toByte() }
        val audio = GrowingAudio()
        audio.append(full.copyOfRange(0, 40)) // пришло 40 байт, потом обрыв
        audio.appendFrom(full, audio.size) // дозапрос без stream: дописываем с 40-го
        audio.complete()
        assertArrayEquals(full, audio.awaitAll(1_000))
    }

    @Test
    fun `failed stream ends readers with what arrived`() {
        val audio = GrowingAudio()
        audio.append(byteArrayOf(1))
        audio.fail()
        assertTrue(audio.isFailed)
        assertEquals(1, audio.readAt(0, ByteArray(4), 0, 4, 1_000))
        assertEquals(-1, audio.readAt(1, ByteArray(4), 0, 4, 1_000))
        assertTrue(audio.awaitFirstByte(10))
    }
}
