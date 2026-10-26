package kz.astanahub.robot.ui.face

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Звук для лица (реализация — [kz.astanahub.robot.audio.AudioLevels]; в тестах — фейк).
 * Уровни — RMS / 32768 (0..1). Вызывается из кадра — без аллокаций и блокировок.
 *
 * «Посетитель говорит» — только по VAD ([voiceActive]: адаптивный VAD приложения или `user_speaking` сервера);
 * громкость микрофона ([micLevel]) — лишь сила реакции. Постоянный шум зала лицо в «слушаю» не переводит.
 */
interface FaceAudioSource {
    /** Микрофон (громкость голоса посетителя); 0 — нет свежих данных. */
    fun micLevel(): Float

    /** Речь робота, если её громкость измеряется (live PCM, Visualizer); −1 — не измеряется (встроенный TTS и т. п.). */
    fun playbackLevel(): Float

    /** VAD: посетитель сейчас говорит. */
    fun voiceActive(): Boolean = false

    companion object {
        val SILENT: FaceAudioSource = object : FaceAudioSource {
            override fun micLevel(): Float = 0f
            override fun playbackLevel(): Float = -1f
        }
    }
}

/**
 * Огибающая громкости: атака/спад (однополюсный фильтр с разными τ), порог шума и автоусиление
 * по недавнему пику — на выходе 0..1 без дёрганий: быстро открывается на слог, мягко закрывается.
 */
class FaceAudioEnvelope(
    private val attackSec: Float,
    private val releaseSec: Float,
    /** Ниже — тишина (шумовой фон). */
    private val floor: Float,
    /** Пик не опускается ниже — тихий звук не раздувается до 1. */
    private val minPeak: Float,
    /** За столько секунд пик спадает в e раз. */
    private val peakDecaySec: Float = 2f,
    /** Следить за шумовым фоном (медленный минимум уровня) и вычитать его: постоянный гул зала → 0. */
    private val trackNoise: Boolean = false,
) {
    /** Сглаженный уровень (в единицах входа). */
    var level: Float = 0f
        private set

    /** Нормированная огибающая 0..1. */
    var value: Float = 0f
        private set

    private var peak = minPeak

    /** Шумовой фон: быстро опускается в паузах, медленно поднимается (речь — всплесками, гул — постоянно). */
    var noise: Float = 0f
        private set

    fun step(input: Float, dt: Float): Float {
        if (dt <= 0f) return value
        val x = if (input > 0f) input else 0f
        val tau = if (x > level) attackSec else releaseSec
        level += (x - level) * (1f - exp(-dt / tau))
        var bottom = floor
        if (trackNoise) {
            val noiseTau = if (level < noise) NOISE_FALL_SEC else NOISE_RISE_SEC
            noise += (level - noise) * (1f - exp(-dt / noiseTau))
            bottom = max(floor, noise * NOISE_MARGIN)
        }
        // Пик догоняет громкость плавно (τ [PEAK_ATTACK_SEC]) и медленно спадает: один громкий кадр не перестраивает шкалу.
        peak = if (level > peak) peak + (level - peak) * (1f - exp(-dt / PEAK_ATTACK_SEC)) else peak * exp(-dt / peakDecaySec)
        peak = max(peak, max(minPeak, bottom + minPeak - floor))
        value = ((level - bottom) / (peak - bottom)).coerceIn(0f, 1f)
        return value
    }

    fun reset() {
        level = 0f
        value = 0f
        peak = minPeak
        noise = 0f
    }

    companion object {
        private const val PEAK_ATTACK_SEC = 0.25f
        private const val NOISE_FALL_SEC = 0.4f
        private const val NOISE_RISE_SEC = 8f
        private const val NOISE_MARGIN = 1.3f

        /** Речь робота: открывается за ~45 мс, закрывается за ~140 мс. */
        fun playback() = FaceAudioEnvelope(attackSec = 0.045f, releaseSec = 0.14f, floor = 0.008f, minPeak = 0.12f)

        /** Голос посетителя: мягче, спад ~0.25 с, шум зала вычитается. */
        fun mic() = FaceAudioEnvelope(attackSec = 0.04f, releaseSec = 0.25f, floor = 0.012f, minPeak = 0.06f, trackNoise = true)
    }
}

/**
 * Синтетическая «речь» для рта, когда громкость озвучки не измеряется (встроенный TTS робота, Android TTS,
 * субтитры без звука): слоги 3.5–6 в секунду со случайной силой и паузами между словами. Детерминировано от [random].
 */
class SpeechSynth(private val random: Random) {
    private var syllableStart = 0L
    private var syllableMs = 0L
    private var syllableAmp = 0f
    private var gapUntil = 0L
    private var syllablesInWord = 0

    /** Уровень в тех же единицах, что RMS (пик ~0.18). [active] = false — тишина и сброс. */
    fun level(nowMs: Long, active: Boolean): Float {
        if (!active) {
            syllableMs = 0L
            gapUntil = 0L
            return 0f
        }
        if (nowMs < gapUntil) return 0f
        if (syllableMs == 0L || nowMs - syllableStart >= syllableMs) {
            // Конец слова — пауза.
            if (syllableMs != 0L && syllablesInWord >= 2 && random.nextFloat() < 0.28f) {
                syllablesInWord = 0
                gapUntil = nowMs + 90L + random.nextLong(190L)
                syllableMs = 0L
                return 0f
            }
            syllableStart = nowMs
            syllableMs = 160L + random.nextLong(130L)
            syllableAmp = 0.07f + random.nextFloat() * 0.13f
            syllablesInWord++
        }
        val p = (nowMs - syllableStart).toFloat() / syllableMs
        return syllableAmp * sin(Math.PI.toFloat() * p).coerceAtLeast(0f).pow(0.8f)
    }
}
