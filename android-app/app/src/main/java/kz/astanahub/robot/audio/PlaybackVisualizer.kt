package kz.astanahub.robot.audio

import android.media.audiofx.Visualizer
import timber.log.Timber

/**
 * Громкость речи из MediaPlayer (MP3 серверного TTS) для лица: android.media.audiofx.Visualizer на сессии
 * плеера, волна ~20 раз в секунду → RMS → [AudioLevels.reportPlayback]. Нужен RECORD_AUDIO (у приложения есть).
 * Не создался (нет разрешения, прошивка не даёт эффект) — один раз в лог и больше не пробуем: лицо двигает рот
 * синтетически. Всё — на главном потоке (там живёт MediaPlayer).
 * ПРОВЕРИТЬ НА РОБОТЕ: работает ли Visualizer на сессии MediaPlayer в прошивке Nova и нет ли задержки.
 */
class PlaybackVisualizer(private val levels: AudioLevels) {
    private var visualizer: Visualizer? = null
    private var unavailable = false

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            if (waveform != null) levels.reportPlayback(AudioLevels.rmsU8(waveform))
        }

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
    }

    fun attach(audioSessionId: Int) {
        detach()
        if (unavailable || audioSessionId == 0) return
        try {
            val v = Visualizer(audioSessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            visualizer = v
        } catch (t: Throwable) {
            unavailable = true
            Timber.w("playback visualizer unavailable: %s", t.javaClass.simpleName)
        }
    }

    fun detach() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false }
        runCatching { v.release() }
    }
}
