package kz.astanahub.robot.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Запись одной фразы с микрофона: AudioRecord 16 кГц mono PCM16, источник VOICE_RECOGNITION (фолбэк MIC),
 * границы фразы — [EnergyVad].
 * ПРОВЕРИТЬ НА РОБОТЕ: отдаёт ли система микрофон, пока AgentOS держит свой ASR.
 */
class MicRecorder(
    context: Context,
    /** RobotOS: микрофонный массив отдаёт стерео 16 кГц, голос — в левом канале (docs/context.md). */
    private val preferStereoLeft: Boolean = false,
) {

    /** Как открыт микрофон в последний раз — для экрана диагностики. */
    @Volatile var lastConfig: String = "—"
        private set

    private class Opened(val record: AudioRecord, val stereo: Boolean)


    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    sealed interface Result {
        data class Speech(val pcm: ShortArray, val event: VadEvent) : Result
        data object NoSpeech : Result
        data class Error(val message: String) : Result
    }

    /** Блокирует IO-поток до конца фразы; отмена корутины останавливает запись. */
    @SuppressLint("MissingPermission") // проверяем hasPermission() выше
    suspend fun recordUtterance(config: VadConfig = VadConfig(), onLevel: (Float) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext Result.Error("RECORD_AUDIO not granted")
            val opened = open(config.sampleRate) ?: return@withContext Result.Error("AudioRecord init failed")
            val record = opened.record
            val vad = EnergyVad(config)
            val channels = if (opened.stereo) 2 else 1
            val raw = ShortArray(config.frameSamples * channels)
            try {
                record.startRecording()
                while (currentCoroutineContext().isActive) {
                    var filled = 0
                    while (filled < raw.size) {
                        val n = record.read(raw, filled, raw.size - filled)
                        if (n < 0) return@withContext Result.Error("AudioRecord.read=$n")
                        filled += n
                    }
                    val event = vad.accept(leftChannel(raw, channels))
                    onLevel(vad.level)
                    when (event) {
                        VadEvent.SPEECH_ENDED, VadEvent.MAX_LENGTH -> return@withContext Result.Speech(vad.utterance(), event)
                        VadEvent.NO_SPEECH -> return@withContext Result.NoSpeech
                        else -> Unit
                    }
                }
                Result.NoSpeech
            } finally {
                runCatching { record.stop() }
                record.release()
                onLevel(0f)
            }
        }

    /** Только уровень сигнала (для экрана диагностики), пока корутина не отменена. */
    @SuppressLint("MissingPermission")
    suspend fun monitorLevel(sampleRate: Int = 16_000, onLevel: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext false
            val opened = open(sampleRate) ?: return@withContext false
            val record = opened.record
            val channels = if (opened.stereo) 2 else 1
            val frame = ShortArray(sampleRate / 50 * channels)
            try {
                record.startRecording()
                while (currentCoroutineContext().isActive) {
                    val n = record.read(frame, 0, frame.size)
                    if (n < 0) return@withContext false
                    val mono = leftChannel(frame.copyOf(n - n % channels), channels)
                    onLevel((EnergyVad.rms(mono) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f))
                }
                true
            } finally {
                runCatching { record.stop() }
                record.release()
                onLevel(0f)
            }
        }

    @SuppressLint("MissingPermission")
    private fun open(sampleRate: Int): Opened? {
        val channelOptions = if (preferStereoLeft) listOf(true, false) else listOf(false)
        for (stereo in channelOptions) {
            val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val record = runCatching {
                    AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
                }.getOrNull()
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    lastConfig = "source=" + (if (source == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_RECOGNITION") +
                        ", " + (if (stereo) "stereo→left" else "mono") + ", buffer=${minBuffer * 4}"
                    Timber.i("AudioRecord %s", lastConfig)
                    return Opened(record, stereo)
                }
                record?.release()
                Timber.w("AudioRecord source=%d stereo=%s failed, trying next", source, stereo)
            }
        }
        lastConfig = "не открылся"
        return null
    }

    companion object {
        /** Интерливинг L R L R … → левый канал. Для mono — копия. */
        fun leftChannel(samples: ShortArray, channels: Int): ShortArray =
            if (channels == 1) samples.copyOf() else ShortArray(samples.size / channels) { samples[it * channels] }
    }
}
