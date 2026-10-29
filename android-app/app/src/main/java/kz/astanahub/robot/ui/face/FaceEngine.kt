package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.motion.SpringBank
import kz.astanahub.robot.ui.motion.SpringMath
import kz.astanahub.robot.ui.motion.SpringSpec
import kz.astanahub.robot.ui.motion.SpringStep
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kz.astanahub.robot.ui.face.FaceParam as P

/** Итоговая поза кадра: значения параметров после пружин, «жизни» и звука — читает рисовальщик. */
class FacePose {
    val v = FloatArray(P.COUNT)

    /** Фазы украшений (копятся по dt — не зависят от аптайма): покачивание «?» (рад), капля и «z» (0..1). */
    var wobble: Float = 0f
    var sweatPhase: Float = 0f
    var zPhase: Float = 0f

    /** Растяжение глаз по горизонтали от скорости саккады (0..0.08) — «смаз движения». */
    var eyeStretch: Float = 0f

    /** Огибающие звука 0..1 (отладка, тесты). */
    var talk: Float = 0f
    var listen: Float = 0f

    var mood: FaceMood = FaceMood.IDLE
    var emotion: FaceEmotion = FaceEmotion.NEUTRAL

    /** Фигура глаз прототипа и когда она сменилась (мс) — для «раскрытия» `exprIn`; время кадра (мс). */
    var expr: ProtoExpr = ProtoExpr.NEUTRAL
    var exprSince: Long = Long.MIN_VALUE / 2
    var nowMs: Long = 0L

    /** Моргание прототипа `sblink` (0 — открыты, 1 — сжаты в линию). */
    var blink: Float = 0f

    /** Кольцо удержания: прогресс 0..1 и проявление 0..1; где палец (−1..1 от центра экрана). */
    var holdProgress: Float = 0f
    var holdAlpha: Float = 0f
    var touchX: Float = 0f
    var touchY: Float = 0f

    operator fun get(p: Int): Float = v[p]
}

/**
 * Движок лица: пружина на каждый параметр ([FaceParam]), цели — из выражения состояния ([FaceDirector]) и наложения
 * эмоции, поверх — «жизнь» ([FaceLife]) и звук ([FaceAudioSource]). Шаг — [update] раз в кадр (vsync), без аллокаций.
 * Любое новое состояние стартует от текущих значений и скоростей — морфинг бесшовный и прерываемый.
 *
 * Детерминирован: время — параметр [update], случайность — от [seed]. Не потокобезопасен: всё на главном потоке.
 */
class FaceEngine(
    seed: Long = DEFAULT_SEED,
    private val audio: FaceAudioSource = FaceAudioSource.SILENT,
) {
    val inputs = FaceInputs()
    val director = FaceDirector()
    val pose = FacePose()

    /** Уменьшение движения (системные анимации выключены): без толчков, покачиваний и «прыжков». */
    var reducedMotion: Boolean = false

    private val life = FaceLife(Random(seed * 31 + 7))
    private val synth = SpeechSynth(Random(seed * 17 + 3))
    private val springs = SpringBank(P.COUNT, FaceSprings.defaults())
    private val talkEnv = FaceAudioEnvelope.playback()
    private val micEnv = FaceAudioEnvelope.mic()
    private var talkSlow = 0f

    /** Раскрытие рта от речи: огибающая → своя быстрая пружина (слоги без рывков). */
    private var talkOpen = 0f
    private var talkOpenVelocity = 0f
    private val talkStep = SpringStep()

    /** Пружины по состояниям (темп: сон медленнее, пробуждение бодрее) — готовятся один раз. */
    private val moodSpecs: Array<Array<SpringSpec>> = buildMoodSpecs()

    /** Поведение «жизни» (взгляд, моргание) — по состоянию, иногда по эмоции. */
    private var lifeMood = FaceMood.IDLE

    private var lastNanos = Long.MIN_VALUE
    private var nowMs = 0L

    /** Отложенные толчки (второй прыжок восторга). */
    private val kickParam = IntArray(MAX_KICKS) { -1 }
    private val kickValue = FloatArray(MAX_KICKS)
    private val kickAt = LongArray(MAX_KICKS)

    /** Скорость параметра (тесты, отладка). */
    fun velocity(p: Int): Float = springs.velocity[p]

    /** Значение пружины параметра без «жизни» и звука (тесты). */
    fun springValue(p: Int): Float = springs.value[p]

    /** Сколько было морганий (тесты). */
    val blinkCount: Int get() = life.blinkCount

    /** Насколько закрыты глаза морганием сейчас (тесты). */
    val blink: Float get() = life.blink

    /** Текущая цель параметра (тесты). */
    fun target(p: Int): Float = springs.target[p]

    /** Реакция на событие (время — последнего кадра). */
    fun cue(c: FaceCue) = director.cue(c, nowMs)

    /** Что сделал рефлекс на тап (тесты, отладка). */
    enum class Reflex { NONE, BLINK, STARTLE }

    private var lastTapMs = Long.MIN_VALUE / 2
    private var lastStartleMs = Long.MIN_VALUE / 2

    /**
     * Тап в тишине: рефлекторно моргнуть или слегка вздрогнуть (глаза распахнулись, брови вверх). Без спама:
     * тапы чаще [TAP_REFLEX_MIN_MS] — ничего, вздрогнуть — не чаще раза в [STARTLE_MIN_MS], иначе моргнуть.
     */
    fun reflex(): Reflex {
        val now = nowMs
        if (now - lastTapMs < TAP_REFLEX_MIN_MS) {
            lastTapMs = now
            return Reflex.NONE
        }
        lastTapMs = now
        val mood = director.mood
        if (mood == FaceMood.SLEEP || mood == FaceMood.YAWN || mood == FaceMood.PETTED) return Reflex.NONE
        if (now - lastStartleMs >= STARTLE_MIN_MS && reflexRandom.nextFloat() < STARTLE_CHANCE) {
            lastStartleMs = now
            if (!reducedMotion) {
                kick(P.POP, 1.6f)
                kick(P.SQUASH, -0.8f)
                springs.impulse(P.BROW_L, 420f)
                springs.impulse(P.BROW_R, 420f)
            }
            life.forceBlink(now)
            return Reflex.STARTLE
        }
        return if (life.forceBlink(now)) Reflex.BLINK else Reflex.NONE
    }

    /** Удержание выдержано (вход в админку): кольцо вспыхивает и гаснет, лицо «кивает». */
    fun holdDone() {
        holdFlash = 1f
        if (!reducedMotion) kick(P.POP, 0.8f)
    }

    private val reflexRandom = Random(seed * 13 + 5)
    private var holdAlpha = 0f
    private var holdAlphaVelocity = 0f
    private val holdStep = SpringStep()
    private var holdFlash = 0f

    /** Кадр: [nowNanos] — время vsync (Choreographer) или тестовые часы. */
    fun update(nowNanos: Long) {
        val first = lastNanos == Long.MIN_VALUE
        val dt = if (first) 0f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, MAX_DT)
        lastNanos = nowNanos
        nowMs = nowNanos / 1_000_000L

        // Звук → огибающие.
        val mic = audio.micLevel()
        val voice = audio.voiceActive()
        micEnv.step(mic, dt, speech = voice)
        // «Слушает» — по VAD (не по громкости: шум зала), громкость — только сила реакции.
        director.update(inputs, voice, nowMs)
        val mood = director.mood
        if (director.moodChanged || first) onMoodEnter(mood, first)
        if (director.emotionChanged && !first) onEmotionEnter(director.emotion)

        val measured = audio.playbackLevel()
        val talking = director.mood != FaceMood.SLEEP && inputs.activity == FaceActivity.SPEAKING
        val playback = if (measured >= 0f) measured else synth.level(nowMs, talking)
        talkEnv.step(playback, dt)
        talkSlow += (talkEnv.value - talkSlow) * (1f - exp(-dt / TALK_SLOW_SEC))

        // Цели: нейтральное → состояние → эмоция → взгляд «жизни».
        val t = springs.target
        Expressions.NEUTRAL.applyTo(t)
        Expressions.mood(mood).applyTo(t)
        // Эмоция поверх; пока говорит — раскрытие рта остаётся за речью.
        val mouthOpen = t[P.MOUTH_OPEN]
        Expressions.overlay(director.emotion)?.blendInto(t, director.overlayWeight)
        if (t[P.TALK] >= 0.5f) t[P.MOUTH_OPEN] = mouthOpen
        if (mood != lifeMood) {
            lifeMood = mood
            life.onMood(mood, nowMs)
        }
        life.update(nowMs, dt, springs.value[P.BREATH_RATE], reducedMotion)
        // Фигура глаз прототипа: сменилось имя — «раскрытие» `exprIn` (на заставке — выражение «свободного времени»).
        val expr = Expressions.proto(mood, life.idleExpr)
        if (expr != pose.expr || first) {
            pose.expr = expr
            pose.exprSince = if (first) Long.MIN_VALUE / 2 else nowMs
        }
        pose.nowMs = nowMs
        val eyesOpen = mood != FaceMood.PETTED
        if (inputs.touching && eyesOpen) {
            // Отклик на касание: глаза сразу на палец (саккада), голова — за ними.
            t[P.GAZE_X] = (inputs.touchX * TOUCH_GAZE_X).coerceIn(-1f, 1f)
            t[P.GAZE_Y] = (inputs.touchY * TOUCH_GAZE_Y).coerceIn(-1f, 1f)
        } else if (inputs.looking && eyesOpen) {
            // Остров where_is: глаза — в сторону места (туда же, куда стрелка), голова — за ними; микросаккады
            // «жизни» остаются, но втрое тише — взгляд держится направления.
            t[P.GAZE_X] = (inputs.lookX + life.gazeOffsetX * LOOK_LIFE).coerceIn(-1f, 1f)
            t[P.GAZE_Y] = (inputs.lookY + life.gazeOffsetY * LOOK_LIFE).coerceIn(-1f, 1f)
        } else {
            t[P.GAZE_X] = (t[P.GAZE_X] + life.gazeOffsetX).coerceIn(-1f, 1f)
            t[P.GAZE_Y] = (t[P.GAZE_Y] + life.gazeOffsetY).coerceIn(-1f, 1f)
        }
        // Удержание: лицо сосредотачивается по мере заполнения кольца (прищур, брови к переносице).
        val hold = holdProgress()
        if (hold > 0f) {
            t[P.LID_L] += 0.14f * hold
            t[P.LID_R] += 0.14f * hold
            t[P.BROW_TILT_L] -= 7f * hold
            t[P.BROW_TILT_R] -= 7f * hold
        }
        // Поглаживание: голова льнёт к руке — наклон за пальцем по горизонтали, кивок по вертикали.
        if (mood == FaceMood.PETTED && !reducedMotion) {
            t[P.HEAD_TILT] += (inputs.strokeVx * STROKE_TILT).coerceIn(-7f, 7f)
            t[P.HEAD_Y] += (inputs.strokeVy * STROKE_NOD).coerceIn(-10f, 10f)
        }
        t[P.HEAD_FOLLOW_X] = t[P.GAZE_X]
        t[P.HEAD_FOLLOW_Y] = t[P.GAZE_Y]
        // У «тела» цель всегда покой: движется только от толчков.
        t[P.SQUASH] = 0f
        t[P.POP] = 0f
        t[P.SHAKE] = 0f

        runKicks()
        if (first) springs.snapAll(t) else springs.step(dt)
        val talkTarget = springs.value[P.TALK].coerceIn(0f, 1f) * TALK_OPEN * talkEnv.value
        SpringMath.step(talkOpen, talkOpenVelocity, talkTarget, TALK_SPRING, dt, talkStep)
        talkOpen = talkStep.value
        talkOpenVelocity = talkStep.velocity
        // Кольцо удержания проявляется пружиной (как «материализация»), вспышка в конце гаснет.
        val showRing = if (inputs.holdStartMs >= 0 && nowMs - inputs.holdStartMs >= FaceGestureRecognizer.HOLD_SHOW_MS) 1f else 0f
        SpringMath.step(holdAlpha, holdAlphaVelocity, maxOf(showRing, holdFlash), FaceSprings.DECOR, dt, holdStep)
        holdAlpha = holdStep.value.coerceIn(0f, 1f)
        holdAlphaVelocity = holdStep.velocity
        holdFlash = (holdFlash - dt / HOLD_FLASH_SEC).coerceAtLeast(0f)
        pose.holdProgress = if (holdFlash > 0f) 1f else hold
        pose.holdAlpha = holdAlpha
        pose.touchX = inputs.touchX
        pose.touchY = inputs.touchY
        compose(dt)
    }

    /** Заполнение кольца удержания 0..1 (от времени касания; первые [FaceGestureRecognizer.HOLD_SHOW_MS] — 0). */
    private fun holdProgress(): Float {
        val start = inputs.holdStartMs
        if (start < 0) return 0f
        val shown = (nowMs - start - FaceGestureRecognizer.HOLD_SHOW_MS).toFloat()
        val span = (FaceGestureRecognizer.HOLD_MS - FaceGestureRecognizer.HOLD_SHOW_MS).toFloat()
        return (shown / span).coerceIn(0f, 1f)
    }

    private fun compose(dt: Float) {
        val o = pose.v
        val s = springs.value
        System.arraycopy(s, 0, o, 0, P.COUNT)
        val motion = if (reducedMotion) REDUCED else 1f

        // Моргание прототипа — сжатие фигуры (`sblink`), рисует [FaceRenderer] по [FacePose.blink].
        val blink = life.blink
        pose.blink = blink
        var brow = -3f * blink

        // Дыхание и микродвижения.
        val breathAmp = s[P.BREATH] * motion
        o[P.HEAD_SCALE] *= 1f + 0.006f * breathAmp * life.breath
        o[P.HEAD_Y] += 3.5f * breathAmp * life.breath
        brow += 1.2f * breathAmp * life.breath
        val micro = s[P.MICRO] * (if (reducedMotion) 0.3f else 1f)
        o[P.HEAD_X] += 2.5f * micro * life.microX
        o[P.HEAD_Y] += 1.8f * micro * life.microY
        o[P.HEAD_TILT] += 0.7f * micro * life.microTilt
        brow += 1.0f * micro * life.microY

        // Прототип двигает только глаза (`lookAt`), голова за взглядом не доворачивается; покачивание «нет».
        o[P.HEAD_X] += s[P.SHAKE]

        // Речь робота: рот открывается по громкости, на ударениях — брови и кивок.
        val talk = s[P.TALK].coerceIn(0f, 1f)
        val env = talkEnv.value
        val onset = (env - talkSlow).coerceAtLeast(0f)
        o[P.MOUTH_OPEN] += talkOpen
        o[P.MOUTH_W] *= 1f - 0.1f * talk * env
        // Рта у лица нет (прототип): речь видна по глазам — они чуть сплющиваются и раздаются в такт.
        o[P.EYE_H] *= 1f - 0.11f * talk * env
        o[P.EYE_W] *= 1f + 0.035f * talk * env
        o[P.HEAD_Y] -= 4f * talk * env * motion
        brow += (40f * onset).coerceAtMost(8f) * talk

        // Голос посетителя: «прислушивается» — брови вверх, глаза чуть больше, наклон.
        val listen = s[P.LISTEN].coerceIn(0f, 1f)
        val heard = micEnv.value
        o[P.POP] += 0.05f * listen * heard
        brow += 9f * listen * heard
        o[P.HEAD_TILT] += 1.5f * listen * heard * motion
        o[P.GLOW] += 0.15f * listen * heard

        o[P.BROW_L] += brow
        o[P.BROW_R] += brow

        clamp(o)
        pose.eyeStretch = if (reducedMotion) 0f else (abs(springs.velocity[P.GAZE_X]) * 0.02f).coerceAtMost(0.08f)
        pose.wobble = (pose.wobble + WOBBLE_RATE * dt) % TWO_PI
        pose.sweatPhase = (pose.sweatPhase + SWEAT_RATE * dt) % 1f
        pose.zPhase = (pose.zPhase + dt / Z_PERIOD_SEC) % 1f
        pose.talk = env
        pose.listen = heard
        pose.mood = director.mood
        pose.emotion = director.emotion
    }

    private fun clamp(o: FloatArray) {
        o[P.OPEN_L] = o[P.OPEN_L].coerceIn(0f, 1f)
        o[P.OPEN_R] = o[P.OPEN_R].coerceIn(0f, 1f)
        o[P.SMILE_L] = o[P.SMILE_L].coerceIn(0f, 1.2f)
        o[P.SMILE_R] = o[P.SMILE_R].coerceIn(0f, 1.2f)
        o[P.LID_L] = o[P.LID_L].coerceIn(0f, 1f)
        o[P.LID_R] = o[P.LID_R].coerceIn(0f, 1f)
        o[P.ARC] = o[P.ARC].coerceIn(-1f, 1f)
        o[P.MOUTH_OPEN] = o[P.MOUTH_OPEN].coerceIn(0f, 1.1f)
        o[P.MOUTH_ROUND] = o[P.MOUTH_ROUND].coerceIn(0f, 1f)
        o[P.MOUTH_W] = o[P.MOUTH_W].coerceAtLeast(8f)
        o[P.EYE_W] = o[P.EYE_W].coerceAtLeast(10f)
        o[P.EYE_H] = o[P.EYE_H].coerceAtLeast(10f)
        o[P.BROW_ALPHA] = o[P.BROW_ALPHA].coerceIn(0f, 1f)
        o[P.BROW_LEN] = o[P.BROW_LEN].coerceAtLeast(0f)
        for (p in P.QUESTION..P.ZZZ) o[p] = o[p].coerceIn(0f, 1f)
        o[P.GLOW] = o[P.GLOW].coerceIn(0f, 1.2f)
        o[P.GAZE_X] = o[P.GAZE_X].coerceIn(-1.2f, 1.2f)
        o[P.GAZE_Y] = o[P.GAZE_Y].coerceIn(-1.2f, 1.2f)
        o[P.POP] = o[P.POP].coerceIn(-0.3f, 0.4f)
        o[P.SQUASH] = o[P.SQUASH].coerceIn(-0.2f, 0.2f)
    }

    private fun onMoodEnter(mood: FaceMood, first: Boolean) {
        val specs = moodSpecs[mood.ordinal]
        for (i in 0 until P.COUNT) springs.setSpec(i, specs[i])
        lifeMood = mood
        life.onMood(mood, nowMs)
        // Толчков на смену состояния нет: у прототипа каждая смена фигуры — «раскрытие» `exprIn` (рисовальщик).
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEmotionEnter(e: FaceEmotion) {
        // Эмоции сервера на лице нет (прототип): настроение — после ответа ([FaceCue.MOOD_HAPPY] и др.).
    }

    private fun kick(p: Int, dv: Float) = springs.impulse(p, dv)

    private fun kickLater(p: Int, dv: Float, delayMs: Long) {
        for (i in 0 until MAX_KICKS) {
            if (kickParam[i] < 0) {
                kickParam[i] = p
                kickValue[i] = dv
                kickAt[i] = nowMs + delayMs
                return
            }
        }
    }

    private fun runKicks() {
        for (i in 0 until MAX_KICKS) {
            if (kickParam[i] >= 0 && nowMs >= kickAt[i]) {
                if (!reducedMotion) springs.impulse(kickParam[i], kickValue[i])
                kickParam[i] = -1
            }
        }
    }

    private fun buildMoodSpecs(): Array<Array<SpringSpec>> {
        val base = FaceSprings.defaults()
        val cache = HashMap<Float, Array<SpringSpec>>()
        return Array(FaceMood.entries.size) { i ->
            val tempo = Expressions.tempo(FaceMood.entries[i])
            cache.getOrPut(tempo) {
                Array(P.COUNT) { p ->
                    val spec = base[p]
                    if (tempo != 1f && spec in TEMPO_GROUPS) SpringSpec(spec.dampingRatio, spec.response * tempo) else spec
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SEED = 0x5EEDL

        /** Больше 50 мс за кадр не шагаем (после паузы приложения лицо не «прыгает»). */
        const val MAX_DT = 0.05f

        private const val MAX_KICKS = 4
        private const val TALK_SLOW_SEC = 0.25f

        /** Тапы чаще — без рефлекса; вздрогнуть — не чаще; доля тапов со «вздрогнуть» (остальные — моргнуть). */
        const val TAP_REFLEX_MIN_MS = 350L
        const val STARTLE_MIN_MS = 3_000L
        private const val STARTLE_CHANCE = 0.4f

        /** Взгляд на палец: доля от положения пальца. */
        private const val LOOK_LIFE = 0.3f
        private const val TOUCH_GAZE_X = 0.9f
        private const val TOUCH_GAZE_Y = 0.8f

        /** Поглаживание: ° наклона на px/с горизонтали и ед. кивка на px/с вертикали. */
        private const val STROKE_TILT = 0.004f
        private const val STROKE_NOD = 0.008f
        private const val HOLD_FLASH_SEC = 0.35f
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** Покачивание «?» (рад/с), сползание капли (раз/с), период «z» (с). */
        private const val WOBBLE_RATE = 2.6f
        private const val SWEAT_RATE = 0.6f
        private const val Z_PERIOD_SEC = 3.2f

        /** Рот при полной громкости речи раскрыт на эту долю. */
        private const val TALK_OPEN = 0.85f

        /** Пружина рта по речи: 50 % пути за ~25 мс — успевает за слогами (~200 мс), не дёргается. */
        private val TALK_SPRING = SpringSpec(dampingRatio = 1f, response = 0.09f)

        /** Множитель амплитуд «жизни» при уменьшенном движении. */
        private const val REDUCED = 0.4f

        /** Группы пружин, которые замедляются во сне и ускоряются при пробуждении. */
        private val TEMPO_GROUPS = setOf(FaceSprings.SHAPE, FaceSprings.MOUTH, FaceSprings.HEAD, FaceSprings.DECOR)
    }
}
