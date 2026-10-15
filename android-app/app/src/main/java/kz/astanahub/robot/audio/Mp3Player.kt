package kz.astanahub.robot.audio

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Проигрывание MP3 из памяти через MediaPlayer (без ExoPlayer). Один звук за раз. */
class Mp3Player {

    @Volatile private var current: MediaPlayer? = null

    /** true — доиграно до конца; false — остановлено [stop] или ошибка. */
    suspend fun play(bytes: ByteArray): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            current = player
            fun finish(ok: Boolean) {
                if (current === player) current = null
                runCatching { player.release() }
                if (cont.isActive) cont.resume(ok)
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
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            } catch (e: Exception) {
                Timber.e(e, "MediaPlayer setup")
                finish(false)
            }
            cont.invokeOnCancellation { runCatching { player.release() } }
            stopHook = { finish(false) }
        }
    }

    @Volatile private var stopHook: (() -> Unit)? = null

    fun stop() {
        val hook = stopHook
        stopHook = null
        if (hook != null) android.os.Handler(android.os.Looper.getMainLooper()).post(hook)
    }

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
