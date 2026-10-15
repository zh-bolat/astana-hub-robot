package kz.astanahub.robot.audio

import kotlin.math.max
import kotlin.math.sqrt

data class VadConfig(
    val sampleRate: Int = 16_000,
    val frameMillis: Int = 20,
    /** Минимальный RMS (из 32768), выше которого кадр может считаться речью. */
    val minSpeechRms: Double = 500.0,
    /** Порог речи = max(minSpeechRms, шумовой фон × noiseFactor). */
    val noiseFactor: Double = 3.0,
    /** Сколько подряд «громкого» нужно для старта (отсекает щелчки). */
    val startMillis: Int = 120,
    /** Тишина после речи, после которой фраза закончена. */
    val endSilenceMillis: Int = 800,
    val maxUtteranceMillis: Int = 15_000,
    /** Сколько звука до старта сохранить, чтобы не обрезать первый слог. */
    val preRollMillis: Int = 300,
    /** Сколько ждать начала речи. */
    val noSpeechTimeoutMillis: Int = 8_000,
) {
    val frameSamples: Int get() = sampleRate * frameMillis / 1000
}

enum class VadEvent { NONE, SPEECH_STARTED, SPEECH_ENDED, MAX_LENGTH, NO_SPEECH }

/**
 * Простой энергетический VAD: кадры по 20 мс, адаптивный порог по шумовому фону.
 * Чистый Kotlin без Android — покрыт юнит-тестами. Одна фраза на экземпляр.
 */
class EnergyVad(private val config: VadConfig = VadConfig()) {

    private val preRoll = ArrayDeque<ShortArray>()
    private val speech = ArrayList<ShortArray>()
    private var noiseFloor = 0.0
    private var loudFrames = 0
    private var silentFrames = 0
    private var framesSeen = 0
    private var speechFrames = 0

    var isSpeaking: Boolean = false
        private set
    var finished: Boolean = false
        private set
    /** Последний RMS 0..1 — для индикатора уровня микрофона. */
    var level: Float = 0f
        private set

    private val startFrames = max(1, config.startMillis / config.frameMillis)
    private val endFrames = max(1, config.endSilenceMillis / config.frameMillis)
    private val maxFrames = config.maxUtteranceMillis / config.frameMillis
    private val preRollFrames = config.preRollMillis / config.frameMillis
    private val noSpeechFrames = config.noSpeechTimeoutMillis / config.frameMillis

    fun accept(frame: ShortArray): VadEvent {
        if (finished) return VadEvent.NONE
        framesSeen++
        val rms = rms(frame)
        level = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        val threshold = max(config.minSpeechRms, noiseFloor * config.noiseFactor)
        val loud = rms >= threshold

        if (!isSpeaking) {
            // Шумовой фон считаем только до начала речи.
            noiseFloor = if (noiseFloor == 0.0) rms else noiseFloor * 0.95 + rms * 0.05
            preRoll.addLast(frame)
            while (preRoll.size > preRollFrames + startFrames) preRoll.removeFirst()
            loudFrames = if (loud) loudFrames + 1 else 0
            if (loudFrames >= startFrames) {
                isSpeaking = true
                speech.addAll(preRoll)
                speechFrames = preRoll.size
                preRoll.clear()
                silentFrames = 0
                return VadEvent.SPEECH_STARTED
            }
            if (framesSeen >= noSpeechFrames) {
                finished = true
                return VadEvent.NO_SPEECH
            }
            return VadEvent.NONE
        }

        speech.add(frame)
        speechFrames++
        silentFrames = if (loud) 0 else silentFrames + 1
        if (silentFrames >= endFrames) {
            finished = true
            return VadEvent.SPEECH_ENDED
        }
        if (speechFrames >= maxFrames) {
            finished = true
            return VadEvent.MAX_LENGTH
        }
        return VadEvent.NONE
    }

    /** Записанная фраза (пре-ролл + речь), хвост тишины обрезан до ~200 мс. */
    fun utterance(): ShortArray {
        val keepTail = 200 / config.frameMillis
        val frames = if (finished && silentFrames > keepTail) speech.dropLast(silentFrames - keepTail) else speech
        val out = ShortArray(frames.sumOf { it.size })
        var pos = 0
        for (f in frames) { f.copyInto(out, pos); pos += f.size }
        return out
    }

    companion object {
        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return sqrt(sum / frame.size)
        }
    }
}
