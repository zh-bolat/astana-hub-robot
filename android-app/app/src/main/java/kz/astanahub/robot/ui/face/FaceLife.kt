package kz.astanahub.robot.ui.face

import kotlin.random.Random

/**
 * «Жизнь» лица поверх выражения — как в прототипе (`docs/design/robot-mvp.html`): моргание (`blinkLoop`), взгляд по
 * сторонам на странице лица в тишине (`glanceAround`), на заставке — «свободное время» (`wanderLoop`: глаза бродят,
 * иногда скучают, удивляются или радуются; робот «никогда не спит»). Дыхания и микродвижений у прототипа нет.
 * Всё детерминировано от [random] и времени (мс), без аллокаций в кадре.
 */
class FaceLife(private val random: Random) {

    /** Моргание сейчас: 0 — открыты, 1 — сжаты в линию (прототип `sblink`: scale(1.04, .08)). */
    var blink: Float = 0f
        private set

    /** Смещение взгляда (прототип `lookAt`) в долях [LOOK_RANGE_X] / [LOOK_RANGE_Y] (−1..1). */
    var gazeOffsetX: Float = 0f
        private set
    var gazeOffsetY: Float = 0f
        private set

    /** Выражение заставки (`idleExpr`): обычное / скучает / удивилось / радуется. */
    var idleExpr: ProtoExpr = ProtoExpr.NEUTRAL
        private set

    /** Дыхание и микродвижения — у прототипа их нет (оставлены нулями для совместимости). */
    var breathPhase: Float = 0f
        private set
    var breath: Float = 0f
        private set
    var microX: Float = 0f
        private set
    var microY: Float = 0f
        private set
    var microTilt: Float = 0f
        private set

    /** Сколько было морганий (тесты, отладка). */
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

    /** Сменилось состояние: взгляд — к центру (кроме покоя и заставки), заставка — заново с обычного выражения. */
    fun onMood(m: FaceMood, nowMs: Long) {
        val wasSleep = mood == FaceMood.SLEEP
        mood = m
        returnAt = -1L
        if (m == FaceMood.SLEEP) {
            if (!wasSleep) nextGazeAt = nowMs
        } else {
            idleExpr = ProtoExpr.NEUTRAL
            gazeOffsetX = 0f
            gazeOffsetY = 0f
            nextGazeAt = nowMs + GLANCE_MIN_MS + random.nextLong(GLANCE_SPREAD_MS)
        }
    }

    fun update(nowMs: Long, @Suppress("UNUSED_PARAMETER") dt: Float, @Suppress("UNUSED_PARAMETER") breathRate: Float, reducedMotion: Boolean) {
        if (nextBlinkAt == Long.MIN_VALUE) nextBlinkAt = nowMs + BLINK_MIN_MS + random.nextLong(BLINK_SPREAD_MS)
        if (nextGazeAt == Long.MIN_VALUE) nextGazeAt = nowMs + GLANCE_MIN_MS
        updateGaze(nowMs, reducedMotion)
        updateBlink(nowMs)
    }

    /** Прототип: `blinkLoop` пропускает «думает» и «не понял»; видно моргание только у овалов `bl`. */
    private fun blinksAllowed(m: FaceMood) = m != FaceMood.THINKING && m != FaceMood.CONFUSED

    private fun updateBlink(nowMs: Long) {
        if (nowMs >= nextBlinkAt) {
            if (blinksAllowed(mood)) {
                startBlink(nowMs)
                // 20 % — двойное моргание через 260 мс.
                secondBlinkAt = if (random.nextFloat() < DOUBLE_BLINK_CHANCE) nowMs + DOUBLE_BLINK_GAP_MS else -1L
            }
            nextBlinkAt = nowMs + BLINK_MIN_MS + random.nextLong(BLINK_SPREAD_MS)
        }
        if (secondBlinkAt >= 0L && secondBlinkAt <= nowMs) {
            startBlink(nowMs)
            secondBlinkAt = -1L
        }
        blink = blinkCurve((nowMs - blinkStart).toFloat())
    }

    /**
     * Моргнуть сейчас (рефлекс на тап). Моргание уже идёт — оно и есть видимая реакция (не перезапускаем).
     * @return true — на лице есть моргание (false — «думает» / «не понял»: там не моргают).
     */
    fun forceBlink(nowMs: Long): Boolean {
        if (!blinksAllowed(mood)) return false
        if (nowMs - blinkStart < BLINK_MS) return true
        startBlink(nowMs)
        return true
    }

    private fun startBlink(nowMs: Long) {
        blinkStart = nowMs
        blinkCount++
    }

    /** Взгляд: на странице лица в тишине — `glanceAround`, на заставке — `wanderLoop`, иначе — в центр. */
    private fun updateGaze(nowMs: Long, reducedMotion: Boolean) {
        if (returnAt >= 0L && returnAt <= nowMs) {
            returnAt = -1L
            setGaze(0f, 0f)
        }
        if (nowMs < nextGazeAt) return
        val r = if (reducedMotion) 0.5f else 1f
        when (mood) {
            FaceMood.IDLE -> {
                // Прототип `glanceAround`: точка из первых семи SPOTS × 0,8, держит 1,1–1,7 с и обратно.
                val i = random.nextInt(7)
                setGaze(SPOTS[i * 2] * 0.8f * r / LOOK_RANGE_X, SPOTS[i * 2 + 1] * 0.8f * r / LOOK_RANGE_Y)
                returnAt = nowMs + 1_100L + random.nextLong(600L)
                nextGazeAt = nowMs + GLANCE_MIN_MS + random.nextLong(GLANCE_SPREAD_MS)
            }
            FaceMood.SLEEP -> {
                // Прототип `wanderLoop`: 14 % скучает, 12 % удивилось и смотрит в сторону, 6 % радуется, иначе бродит.
                val roll = random.nextFloat()
                when {
                    roll < 0.14f -> {
                        idleExpr = ProtoExpr.BORED
                        setGaze(0f, 0f)
                    }
                    roll < 0.26f -> {
                        idleExpr = ProtoExpr.WIDE
                        spot(random.nextInt(7), r)
                    }
                    roll < 0.32f -> {
                        idleExpr = ProtoExpr.HAPPY
                        setGaze(0f, -4f * r / LOOK_RANGE_Y)
                    }
                    else -> {
                        idleExpr = ProtoExpr.NEUTRAL
                        spot(random.nextInt(SPOTS.size / 2), r)
                    }
                }
                nextGazeAt = nowMs + 1_600L + random.nextLong(2_200L)
            }
            else -> {
                setGaze(0f, 0f)
                nextGazeAt = nowMs + GLANCE_MIN_MS
            }
        }
    }

    private fun spot(i: Int, r: Float) = setGaze(SPOTS[i * 2] * r / LOOK_RANGE_X, SPOTS[i * 2 + 1] * r / LOOK_RANGE_Y)

    private fun setGaze(x: Float, y: Float) {
        gazeOffsetX = x
        gazeOffsetY = y
    }

    companion object {
        /** Моргание: раз в 2,6–5,8 с, 20 % — двойное через 260 мс (прототип `blinkLoop`). */
        const val BLINK_MIN_MS = 2_600L
        const val BLINK_SPREAD_MS = 3_200L
        const val BLINK_MAX_MS = BLINK_MIN_MS + BLINK_SPREAD_MS
        const val DOUBLE_BLINK_CHANCE = 0.2f
        const val DOUBLE_BLINK_GAP_MS = 260L

        /** Взгляд в покое: раз в 4,5–8 с (прототип `glanceAround`). */
        const val GLANCE_MIN_MS = 4_500L
        const val GLANCE_SPREAD_MS = 3_500L

        /** Моргание `sblink`: 220 мс, сжатие к 45 %, держит до 55 %, ease-in-out на отрезках. */
        const val BLINK_MS = 220f

        /** Полный ход взгляда (единицы прототипа, поле 200 × 100): ±22 по x, ±12 по y — крайние SPOTS. */
        const val LOOK_RANGE_X = 22f
        const val LOOK_RANGE_Y = 12f

        /** Точки взгляда прототипа (x, y в единицах поля 200 × 100). */
        val SPOTS = floatArrayOf(-22f, 0f, 22f, 0f, 0f, -12f, 16f, -10f, -16f, -8f, -12f, 8f, 12f, 6f, 0f, 0f, 0f, 0f)

        private val EASE_IN_OUT = Bezier.EASE_IN_OUT

        /** Кривая моргания по времени от начала (мс): 0 → 1 (45 %) → 1 (55 %) → 0 (100 %). */
        fun blinkCurve(ms: Float): Float {
            if (ms < 0f || ms >= BLINK_MS) return 0f
            val t = ms / BLINK_MS
            return when {
                t < 0.45f -> EASE_IN_OUT.transform(t / 0.45f)
                t < 0.55f -> 1f
                else -> 1f - EASE_IN_OUT.transform((t - 0.55f) / 0.45f)
            }
        }

        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }
    }
}
