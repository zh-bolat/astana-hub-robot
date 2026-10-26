package kz.astanahub.robot.ui.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Жизнь» лица поверх выражения: моргание, взгляды, дыхание, микродвижения. Всё детерминировано от [random]
 * и времени (мс), без аллокаций в кадре. Поведение зависит от состояния ([onMood]).
 */
class FaceLife(private val random: Random) {

    /** Насколько закрыты глаза морганием сейчас (0 — открыты, 1 — закрыты). */
    var blink: Float = 0f
        private set

    /** Смещение взгляда от «якоря» выражения (−1..1). */
    var gazeOffsetX: Float = 0f
        private set
    var gazeOffsetY: Float = 0f
        private set

    /** Фаза дыхания (рад) и текущее значение sin(фазы). */
    var breathPhase: Float = 0f
        private set
    var breath: Float = 0f
        private set

    /** Микродвижения −1..1 по трём каналам (x, y, наклон) — плавный «шум» из несоизмеримых синусов. */
    var microX: Float = 0f
        private set
    var microY: Float = 0f
        private set
    var microTilt: Float = 0f
        private set

    /** Сколько морганий было (тесты, отладка). */
    var blinkCount: Int = 0
        private set

    private var mood = FaceMood.IDLE

    // Моргание.
    private var nextBlinkAt = Long.MIN_VALUE
    private var blinkStart = Long.MIN_VALUE / 2
    private var secondBlinkAt = -1L

    // Взгляд.
    private var nextGazeAt = Long.MIN_VALUE
    private var returnAt = -1L
    private var thinkSide = false

    private val microPhase = FloatArray(6) { random.nextFloat() * TWO_PI }

    /** Сменилось состояние: перепланировать взгляд (сразу) и моргание (не раньше чем через 0.6 с). */
    fun onMood(m: FaceMood, nowMs: Long) {
        mood = m
        nextGazeAt = nowMs
        returnAt = -1L
        gazeOffsetX = 0f
        gazeOffsetY = 0f
        if (nextBlinkAt != Long.MIN_VALUE && nextBlinkAt > nowMs && blinksAllowed(m)) return
        nextBlinkAt = nowMs + 600L + random.nextLong(1_400L)
    }

    fun update(nowMs: Long, dt: Float, breathRate: Float, reducedMotion: Boolean) {
        if (nextBlinkAt == Long.MIN_VALUE) nextBlinkAt = nowMs + 1_200L + random.nextLong(2_000L)
        if (nextGazeAt == Long.MIN_VALUE) nextGazeAt = nowMs + 1_500L
        updateGaze(nowMs, reducedMotion)
        updateBlink(nowMs)

        breathPhase = (breathPhase + TWO_PI * breathRate * dt) % TWO_PI
        breath = sin(breathPhase)

        // Фазы копятся по dt (а не от абсолютного времени во float): киоск работает неделями, точность не падает.
        for (i in 0 until MICRO_FREQ.size) microPhase[i] = (microPhase[i] + TWO_PI * MICRO_FREQ[i] * dt) % TWO_PI
        microX = 0.6f * sin(microPhase[0]) + 0.4f * sin(microPhase[1])
        microY = 0.6f * sin(microPhase[2]) + 0.4f * sin(microPhase[3])
        microTilt = 0.7f * sin(microPhase[4]) + 0.3f * sin(microPhase[5])
    }

    private fun blinksAllowed(m: FaceMood) =
        m != FaceMood.SLEEP && m != FaceMood.YAWN && m != FaceMood.PETTED

    /** Множитель интервала между морганиями: говорит — чаще, думает и смотрит — реже. */
    private fun blinkPace(m: FaceMood): Float = when (m) {
        FaceMood.SPEAKING -> 0.75f
        FaceMood.LISTENING -> 1.1f
        FaceMood.THINKING -> 1.4f
        FaceMood.LOOKING -> 1.6f
        else -> 1f
    }

    private fun updateBlink(nowMs: Long) {
        if (!blinksAllowed(mood)) {
            blink = 0f
            secondBlinkAt = -1L
            if (nowMs >= nextBlinkAt) nextBlinkAt = nowMs + 1_000L
            return
        }
        if (nowMs >= nextBlinkAt) {
            startBlink(nowMs)
            // 20 % — двойное моргание.
            secondBlinkAt = if (random.nextFloat() < DOUBLE_BLINK_CHANCE) nowMs + DOUBLE_BLINK_GAP_MS else -1L
            val interval = BLINK_MIN_MS + random.nextLong(BLINK_MAX_MS - BLINK_MIN_MS)
            nextBlinkAt = nowMs + (interval * blinkPace(mood)).toLong()
        }
        if (secondBlinkAt >= 0L && secondBlinkAt <= nowMs) {
            startBlink(nowMs)
            secondBlinkAt = -1L
        }
        blink = blinkCurve((nowMs - blinkStart).toFloat())
    }

    /** Моргнуть сейчас (рефлекс на тап), если глаза открыты и не моргали только что. @return моргнул. */
    fun forceBlink(nowMs: Long): Boolean {
        if (!blinksAllowed(mood) || nowMs - blinkStart < BLINK_MS + 60) return false
        startBlink(nowMs)
        if (nextBlinkAt - nowMs < 1_200L) nextBlinkAt = nowMs + 1_200L + random.nextLong(1_500L)
        return true
    }

    private fun startBlink(nowMs: Long) {
        blinkStart = nowMs
        blinkCount++
    }

    /** Взгляд по поведению состояния. Смещение — от «якоря» выражения; пружина взгляда делает саккаду. */
    private fun updateGaze(nowMs: Long, reducedMotion: Boolean) {
        if (returnAt >= 0L && returnAt <= nowMs) {
            returnAt = -1L
            setGaze(0f, 0f, nowMs)
        }
        if (nowMs < nextGazeAt) return
        val r = if (reducedMotion) 0.5f else 1f
        when (mood) {
            FaceMood.IDLE -> {
                val roll = random.nextFloat()
                if (roll < 0.5f) {
                    setGaze(sym(0.12f), sym(0.1f), nowMs)
                } else {
                    // Взгляд по сторонам; чаще — короткий, с возвратом к центру.
                    val x = (0.35f + random.nextFloat() * 0.45f) * if (random.nextBoolean()) 1f else -1f
                    setGaze(x * r, (random.nextFloat() * 0.65f - 0.35f) * r, nowMs)
                    if (random.nextFloat() < 0.6f) returnAt = nowMs + 800L + random.nextLong(800L)
                }
                nextGazeAt = nowMs + 1_800L + random.nextLong(2_700L)
            }
            FaceMood.LISTENING, FaceMood.HAPPY, FaceMood.EXCITED, FaceMood.ACK, FaceMood.WAKE -> {
                // Смотрит на собеседника: микросаккады.
                setGaze(sym(0.06f), sym(0.05f), nowMs)
                nextGazeAt = nowMs + 500L + random.nextLong(900L)
            }
            FaceMood.THINKING -> {
                // Переводит взгляд вверх-вправо ↔ вверх-влево.
                thinkSide = !thinkSide
                setGaze(if (thinkSide) -1.0f * r else 0f, sym(0.08f), nowMs)
                nextGazeAt = nowMs + 1_200L + random.nextLong(1_200L)
            }
            FaceMood.SPEAKING -> {
                if (random.nextFloat() < 0.35f) {
                    val x = (0.3f + random.nextFloat() * 0.3f) * if (random.nextBoolean()) 1f else -1f
                    setGaze(x * r, (random.nextFloat() * 0.4f - 0.3f) * r, nowMs)
                    returnAt = nowMs + 500L + random.nextLong(500L)
                } else {
                    setGaze(sym(0.06f), sym(0.05f), nowMs)
                }
                nextGazeAt = nowMs + 1_800L + random.nextLong(1_800L)
            }
            FaceMood.TRAVEL -> {
                // По ходу движения; иногда оглядывается на посетителя.
                if (random.nextFloat() < 0.3f) {
                    setGaze(-0.6f, 0.05f, nowMs)
                    returnAt = nowMs + 800L
                } else {
                    setGaze(sym(0.08f), sym(0.06f), nowMs)
                }
                nextGazeAt = nowMs + 1_500L + random.nextLong(1_500L)
            }
            FaceMood.LOOKING -> {
                // Разглядывает: из стороны в сторону.
                thinkSide = !thinkSide
                setGaze((if (thinkSide) 0.3f else -0.3f) * r, sym(0.06f), nowMs)
                nextGazeAt = nowMs + 500L + random.nextLong(400L)
            }
            else -> {
                setGaze(0f, 0f, nowMs)
                nextGazeAt = nowMs + 1_000L
            }
        }
    }

    private fun setGaze(x: Float, y: Float, nowMs: Long) {
        // Большая саккада иногда сопровождается морганием (как у людей).
        if (abs(x - gazeOffsetX) > 0.6f && blinksAllowed(mood) && nowMs - blinkStart > 600L &&
            random.nextFloat() < SACCADE_BLINK_CHANCE
        ) {
            startBlink(nowMs)
        }
        gazeOffsetX = x
        gazeOffsetY = y
    }

    private fun sym(amp: Float): Float = (random.nextFloat() * 2f - 1f) * amp

    companion object {
        const val BLINK_MIN_MS = 2_000L
        const val BLINK_MAX_MS = 6_000L
        const val DOUBLE_BLINK_CHANCE = 0.2f
        const val DOUBLE_BLINK_GAP_MS = 240L
        const val SACCADE_BLINK_CHANCE = 0.3f

        /** Моргание: закрытие 70 мс, пауза 20 мс, открытие 110 мс. */
        const val BLINK_CLOSE_MS = 70f
        const val BLINK_HOLD_MS = 20f
        const val BLINK_OPEN_MS = 110f
        const val BLINK_MS = BLINK_CLOSE_MS + BLINK_HOLD_MS + BLINK_OPEN_MS

        private const val TWO_PI = (2.0 * PI).toFloat()

        /** Частоты микродвижений (Гц): x — 0.23 и 0.61, y — 0.31 и 0.53, наклон — 0.17 и 0.43. */
        private val MICRO_FREQ = floatArrayOf(0.23f, 0.61f, 0.31f, 0.53f, 0.17f, 0.43f)

        /** Кривая моргания по времени от начала (мс): 0 → 1 → 0, плавно. */
        fun blinkCurve(ms: Float): Float = when {
            ms < 0f || ms >= BLINK_MS -> 0f
            ms < BLINK_CLOSE_MS -> smooth(ms / BLINK_CLOSE_MS)
            ms < BLINK_CLOSE_MS + BLINK_HOLD_MS -> 1f
            else -> 1f - easeOut((ms - BLINK_CLOSE_MS - BLINK_HOLD_MS) / BLINK_OPEN_MS)
        }

        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        private fun easeOut(x: Float): Float {
            val c = 1f - x.coerceIn(0f, 1f)
            return 1f - c * c * c
        }
    }
}
