package kz.astanahub.robot.audio

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Проигрывание MP3 из памяти через MediaPlayer (без ExoPlayer). Один звук за раз.
 * У каждого проигрывания своя сессия: [stop] завершает именно текущую, а не «последний сохранённый хук».
 */
class Mp3Player {

    private class Session(val finish: (Boolean) -> Unit)

    @Volatile private var session: Session? = null

    /** true — доиграно до конца; false — остановлено [stop], отменено или ошибка. */
    suspend fun play(bytes: ByteArray): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            val released = AtomicBoolean(false)
            fun release() {
                if (released.compareAndSet(false, true)) runCatching { player.release() }
            }
            lateinit var self: Session
            fun finish(ok: Boolean) {
                if (session === self) session = null
                release()
                if (cont.isActive) cont.resume(ok)
            }
            self = Session(::finish)
            // Предыдущая сессия (если её не дождались) не должна играть поверх новой.
            session?.finish?.invoke(false)
            session = self
            cont.invokeOnCancellation {
                if (session === self) session = null
                // release — на главном потоке, где живёт MediaPlayer.
                mainHandler.post { release() }
            }
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(ByteArrayDataSource(bytes))
                player.setOnCompletionListener { finish(true) }
                player.setOnErrorListener { _, what, extra ->
                    Timber.w("MediaPlayer error %d/%d", what, extra)
                    finish(false); true
                }
                player.setOnPreparedListener { if (!released.get()) it.start() }
                player.prepareAsync()
            } catch (e: Exception) {
                Timber.e(e, "MediaPlayer setup")
                finish(false)
            }
        }
    }

    /** Остановить текущее проигрывание (из любого потока). Нечего играть — ничего не делает. */
    fun stop() {
        val current = session ?: return
        mainHandler.post { current.finish(false) }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
