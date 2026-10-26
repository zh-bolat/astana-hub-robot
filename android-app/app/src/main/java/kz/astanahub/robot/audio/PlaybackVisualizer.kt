package kz.astanahub.robot.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber

/**
 * Громкость речи из MediaPlayer (MP3 серверного TTS) для лица: android.media.audiofx.Visualizer, волна ~20 раз
 * в секунду → RMS → [AudioLevels.reportPlayback]. Нужен RECORD_AUDIO (у приложения есть).
 *
 * Без нагрузки на главный поток: все плееры играют в одной аудиосессии [sessionId] (её выдаёт AudioManager один
 * раз при старте), Visualizer на ней один, создаётся и включается/выключается на своём потоке — на старте фразы
 * главный поток не делает ни одного binder-вызова в audioserver. Не создался (нет разрешения, прошивка не даёт
 * эффект) — один раз в лог, дальше лицо двигает рот синтетически.
 * ПРОВЕРИТЬ НА РОБОТЕ: работает ли Visualizer на общей сессии MediaPlayer в прошивке Nova и его задержка.
 */
class PlaybackVisualizer(context: Context, private val levels: AudioLevels) {

    /** Общая аудиосессия всех клипов (MediaPlayer.setAudioSessionId до setDataSource); 0 — не выдали. */
    val sessionId: Int = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    }.getOrDefault(AudioManager.ERROR).coerceAtLeast(0)

    private val handler: Handler by lazy {
        Handler(HandlerThread("face-visualizer").apply { start() }.looper)
    }

    // Только поток handler.
    private var visualizer: Visualizer? = null
    @Volatile private var unavailable = false

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            if (waveform != null) levels.reportPlayback(AudioLevels.rmsU8(waveform))
        }

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
    }

    private val enable = Runnable { setEnabled(true) }
    private val disable = Runnable { setEnabled(false) }

    /** Клип начал играть (главный поток): включить замер — на своём потоке. */
    fun onPlay() {
        if (sessionId == 0 || unavailable) return
        handler.post(enable)
    }

    /** Клип доиграл / остановлен. */
    fun onStop() {
        if (sessionId == 0 || unavailable) return
        handler.post(disable)
    }

    private fun setEnabled(on: Boolean) {
        val v = visualizer ?: if (on) create() else null
        v ?: return
        runCatching { v.enabled = on }.onFailure { Timber.w("visualizer enabled=%s failed: %s", on, it.javaClass.simpleName) }
    }

    private fun create(): Visualizer? {
        if (unavailable) return null
        return try {
            Visualizer(sessionId).also { v ->
                v.captureSize = Visualizer.getCaptureSizeRange()[0]
                v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), true, false)
                visualizer = v
            }
        } catch (t: Throwable) {
            unavailable = true
            Timber.w("playback visualizer unavailable: %s", t.javaClass.simpleName)
            null
        }
    }
}
