package kz.astanahub.robot.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Звук, который ещё докачивается (потоковый `/v1/tts`): писатель добавляет байты по мере прихода,
 * читатель (MediaDataSource плеера, его собственный поток) читает по позиции и ждёт, пока байты появятся.
 * Чистый Kotlin, без Android — покрыт юнит-тестами.
 *
 * Обрыв посреди потока: [append] докачанной без стриминга фразы с позиции [size] (MP3 CBR — байтовое смещение
 * ≈ время; декодер синхронизируется по заголовку кадра), затем [complete]. Не удалось — [fail]: читатель получает
 * конец данных, звучит то, что успело прийти.
 */
class GrowingAudio(initialCapacity: Int = 32 * 1024) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var data = ByteArray(initialCapacity)
    private var length = 0
    private var finished = false
    private var failed = false

    /** Когда пришёл первый байт (System.nanoTime), 0 — ещё не пришёл. */
    @Volatile var firstByteAtNanos: Long = 0L
        private set

    val size: Int get() = lock.withLock { length }
    val isFinished: Boolean get() = lock.withLock { finished }
    val isFailed: Boolean get() = lock.withLock { failed }

    fun append(bytes: ByteArray, count: Int = bytes.size) {
        if (count <= 0) return
        lock.withLock {
            if (finished) return
            if (length + count > data.size) data = data.copyOf(maxOf(data.size * 2, length + count))
            System.arraycopy(bytes, 0, data, length, count)
            if (length == 0) firstByteAtNanos = System.nanoTime()
            length += count
            changed.signalAll()
        }
    }

    /** Байты с позиции [from] (дозапрос после обрыва: недостающий хвост полной фразы). */
    fun appendFrom(full: ByteArray, from: Int) {
        if (from < full.size) append(full.copyOfRange(from, full.size))
    }

    fun complete() = lock.withLock {
        finished = true
        changed.signalAll()
    }

    /** Поток оборвался и докачать не вышло: дальше данных не будет. */
    fun fail() = lock.withLock {
        failed = true
        finished = true
        changed.signalAll()
    }

    /** Ждать первый байт (или конец). true — есть хотя бы один байт. */
    fun awaitFirstByte(timeoutMillis: Long): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (length == 0 && !finished) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        length > 0
    }

    /** Ждать конца потока; null — не дождались. */
    fun awaitAll(timeoutMillis: Long): ByteArray? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!finished) {
            if (left <= 0) return null
            left = changed.awaitNanos(left)
        }
        data.copyOf(length)
    }

    /**
     * Чтение для MediaDataSource.readAt: ждёт байты на позиции [position] до [timeoutMillis].
     * -1 — конец данных (поток закончен, оборван или так и не пришли байты за таймаут).
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int, timeoutMillis: Long): Int = lock.withLock {
        if (count <= 0) return 0
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (position >= length && !finished) {
            if (left <= 0) return -1
            left = changed.awaitNanos(left)
        }
        if (position >= length) return -1
        val n = minOf(count.toLong(), length - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        n
    }
}
