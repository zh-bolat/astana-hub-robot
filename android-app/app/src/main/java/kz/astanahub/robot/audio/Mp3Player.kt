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
 *
 * Бесшовность: [prepare] готовит следующий MediaPlayer (prepareAsync из памяти), пока звучит текущий;
 * [play] подготовленного клипа — только `start()`. Всё на [Dispatchers.Main.immediate]: продолжение после
 * onCompletion выполняется прямо в колбэке, и следующий клип стартует в том же такте главного потока.
 * Каждый MediaPlayer освобождается ровно один раз: по завершении/ошибке/стопу [play], отменой [prepare]
 * или [release] для не сыгранного клипа.
 */
class Mp3Player(
    /** Громкость речи для лица (Visualizer на сессии плеера); null — не измеряем (тесты, диагностика). */
    private val meter: PlaybackVisualizer? = null,
) : ClipPlayer {

    private class Clip(val player: MediaPlayer, private val beforeRelease: () -> Unit = {}) : AudioClip {
        private val released = AtomicBoolean(false)
        val isReleased: Boolean get() = released.get()

        /** Только на главном потоке (там живёт MediaPlayer). */
        fun releaseNow() {
            if (released.compareAndSet(false, true)) {
                // Потоковый источник: сначала разбудить readAt (иначе release ждёт заблокированное чтение).
                runCatching { beforeRelease() }
                runCatching { player.release() }
            }
        }
    }

    private class Session(val finish: (Boolean) -> Unit)

    @Volatile private var session: Session? = null

    /** Клип, на сессии которого сейчас висит [meter] (только главный поток). */
    private var meteredClip: Clip? = null

    private fun meterStart(c: Clip) {
        val m = meter ?: return
        meteredClip = c
        m.attach(runCatching { c.player.audioSessionId }.getOrDefault(0))
    }

    private fun meterStop(c: Clip) {
        if (meteredClip !== c) return
        meteredClip = null
        meter?.detach()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override suspend fun prepare(bytes: ByteArray): AudioClip? = prepareSource(ByteArrayDataSource(bytes)) {}

    /**
     * Потоковый звук: MediaDataSource с неизвестным размером (getSize = -1), readAt ждёт байты в потоке плеера.
     * prepareAsync завершается, когда пришло достаточно байт для заголовка MP3 — то есть вскоре после первых байт.
     * ПРОВЕРИТЬ НА РОБОТЕ: MediaPlayer (NuPlayer) этой прошивки играет MP3 из MediaDataSource без размера.
     */
    override suspend fun prepareStream(audio: GrowingAudio): AudioClip? = prepareSource(GrowingDataSource(audio)) { audio.fail() }

    private suspend fun prepareSource(source: MediaDataSource, beforeRelease: () -> Unit): AudioClip? =
        withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val clip = Clip(MediaPlayer(), beforeRelease)
            cont.invokeOnCancellation { mainHandler.post { clip.releaseNow() } }
            try {
                clip.player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                clip.player.setDataSource(source)
                clip.player.setOnPreparedListener {
                    if (clip.isReleased) return@setOnPreparedListener
                    // Отменили между prepared и возобновлением — клип никому не достался, освобождаем.
                    if (cont.isActive) cont.resume(clip) { _, _, _ -> clip.releaseNow() } else clip.releaseNow()
                }
                clip.player.setOnErrorListener { _, what, extra ->
                    Timber.w("MediaPlayer prepare error %d/%d", what, extra)
                    clip.releaseNow()
                    if (cont.isActive) cont.resume(null)
                    true
                }
                clip.player.prepareAsync()
            } catch (e: Exception) {
                Timber.e(e, "MediaPlayer setup")
                clip.releaseNow()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    override suspend fun play(clip: AudioClip): Boolean = withContext(Dispatchers.Main.immediate) {
        val c = clip as Clip
        if (c.isReleased) return@withContext false
        suspendCancellableCoroutine { cont ->
            lateinit var self: Session
            fun finish(ok: Boolean) {
                if (session === self) session = null
                meterStop(c)
                c.releaseNow()
                if (cont.isActive) cont.resume(ok)
            }
            self = Session(::finish)
            // Предыдущая сессия (если её не дождались) не должна играть поверх новой.
            session?.finish?.invoke(false)
            session = self
            cont.invokeOnCancellation {
                if (session === self) session = null
                mainHandler.post {
                    meterStop(c)
                    c.releaseNow()
                }
            }
            try {
                c.player.setOnCompletionListener { finish(true) }
                c.player.setOnErrorListener { _, what, extra ->
                    Timber.w("MediaPlayer error %d/%d", what, extra)
                    finish(false); true
                }
                c.player.start()
                meterStart(c)
            } catch (e: Exception) {
                Timber.e(e, "MediaPlayer start")
                finish(false)
            }
        }
    }

    /** Подготовить и сыграть (одиночный звук, экран диагностики). */
    suspend fun play(bytes: ByteArray): Boolean {
        val clip = prepare(bytes) ?: return false
        return play(clip)
    }

    override fun release(clip: AudioClip) {
        val c = clip as Clip
        if (Looper.myLooper() == Looper.getMainLooper()) c.releaseNow() else mainHandler.post { c.releaseNow() }
    }

    /** Остановить текущее проигрывание (из любого потока). Нечего играть — ничего не делает. */
    override fun stop() {
        val current = session ?: return
        mainHandler.post { current.finish(false) }
    }

    /** Растущий источник: блокирующее чтение на потоке MediaPlayer, конец — когда поток закончен/оборван. */
    private class GrowingDataSource(private val audio: GrowingAudio) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            audio.readAt(position, buffer, offset, size, STREAM_WAIT_MILLIS)

        override fun getSize(): Long = -1
        override fun close() = Unit
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
