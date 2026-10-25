package kz.astanahub.robot.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kz.astanahub.robot.audio.MicRecorder
import timber.log.Timber

/** Открытый микрофон для live: читает кадры PCM16 LE mono 16 кГц (реализация на AudioRecord блокирует — вызывать в IO). */
interface OpenedMic {
    /** Эхоподавление реально включено (AcousticEchoCanceler на сессии записи) — уходит в `hello.aec`. */
    val aec: Boolean

    /** Как открыт (для логов/диагностики). */
    val description: String

    /** Заполняет [frame] целиком; false — ошибка записи. */
    suspend fun read(frame: ByteArray): Boolean

    fun close()
}

/** Микрофон live-режима (реализация — [AndroidLiveMicrophone]; в тестах — фейк). */
fun interface LiveMicrophone {
    /** null — микрофон не открылся (нет разрешения, занят). */
    fun open(sampleRate: Int): OpenedMic?
}

/**
 * AudioRecord для живого режима: источник VOICE_COMMUNICATION (на нём платформа включает AEC/NS), затем
 * VOICE_RECOGNITION и MIC; поверх — AcousticEchoCanceler и NoiseSuppressor, если доступны.
 * ПРОВЕРИТЬ НА РОБОТЕ: есть ли AEC на Nova (AcousticEchoCanceler.isAvailable) и отдаёт ли RobotOS
 * VOICE_COMMUNICATION в стерео (голос — левый канал, как у VOICE_RECOGNITION).
 */
class AndroidLiveMicrophone(
    context: Context,
    private val preferStereoLeft: Boolean = false,
) : LiveMicrophone {
    private val appContext = context.applicationContext

    @Volatile var lastConfig: String = "—"
        private set

    @SuppressLint("MissingPermission") // проверка ниже
    override fun open(sampleRate: Int): OpenedMic? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Timber.w("live mic: RECORD_AUDIO not granted")
            return null
        }
        val channelOptions = if (preferStereoLeft) listOf(true, false) else listOf(false)
        for (stereo in channelOptions) {
            val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            for (source in SOURCES) {
                val record = runCatching {
                    AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
                }.getOrNull()
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    record?.release()
                    continue
                }
                val aec = enable(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(record.audioSessionId) }
                val ns = enable(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(record.audioSessionId) }
                return try {
                    record.startRecording()
                    val desc = "source=${sourceName(source)}, ${if (stereo) "stereo→left" else "mono"}, " +
                        "aec=${aec != null}, ns=${ns != null}"
                    lastConfig = desc
                    Timber.i("live mic %s", desc)
                    AndroidOpenedMic(record, stereo, aec, ns, desc)
                } catch (e: IllegalStateException) {
                    Timber.w(e, "live mic start failed")
                    aec?.release(); ns?.release(); record.release()
                    null
                }
            }
        }
        lastConfig = "не открылся"
        return null
    }

    private fun <T : AudioEffect> enable(available: Boolean, create: () -> T?): T? {
        if (!available) return null
        val effect = runCatching { create() }.getOrNull() ?: return null
        runCatching { effect.enabled = true }
        return if (runCatching { effect.enabled }.getOrDefault(false)) effect else { effect.release(); null }
    }

    private class AndroidOpenedMic(
        private val record: AudioRecord,
        private val stereo: Boolean,
        private val aecEffect: AcousticEchoCanceler?,
        private val nsEffect: NoiseSuppressor?,
        override val description: String,
    ) : OpenedMic {
        override val aec: Boolean get() = aecEffect != null

        private var raw = ShortArray(0)

        override suspend fun read(frame: ByteArray): Boolean {
            val channels = if (stereo) 2 else 1
            val samples = frame.size / 2
            if (raw.size != samples * channels) raw = ShortArray(samples * channels)
            var filled = 0
            while (filled < raw.size) {
                val n = record.read(raw, filled, raw.size - filled)
                if (n < 0) return false
                filled += n
            }
            val mono = MicRecorder.leftChannel(raw, channels)
            for (i in mono.indices) {
                val s = mono[i].toInt()
                frame[2 * i] = (s and 0xFF).toByte()
                frame[2 * i + 1] = (s shr 8 and 0xFF).toByte()
            }
            return true
        }

        override fun close() {
            runCatching { record.stop() }
            aecEffect?.release()
            nsEffect?.release()
            record.release()
        }
    }

    private companion object {
        val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        fun sourceName(source: Int) = when (source) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "MIC"
        }
    }
}
