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

    /** Время лица, с (анимации украшений: «z», капля, «?»). */
    var time: Float = 0f

    /** Растяжение глаз по горизонтали от скорости саккады (0..0.08) — «смаз движения». */
    var eyeStretch: Float = 0f

    /** Огибающие звука 0..1 (отладка, тесты). */
    var talk: Float = 0f
    var listen: Float = 0f

    var mood: FaceMood = FaceMood.IDLE
    var emotion: FaceEmotion = FaceEmotion.NEUTRAL

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

    /** Кадр: [nowNanos] — время vsync (Choreographer) или тестовые часы. */
    fun update(nowNanos: Long) {
        val first = lastNanos == Long.MIN_VALUE
        val dt = if (first) 0f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, MAX_DT)
        lastNanos = nowNanos
        nowMs = nowNanos / 1_000_000L

        // Звук → огибающие.
        val mic = audio.micLevel()
        micEnv.step(mic, dt)
        director.update(inputs, micEnv.value, nowMs)
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
        Expressions.overlay(director.emotion)?.blendInto(t, director.overlayWeight)
        life.update(nowMs, dt, springs.value[P.BREATH_RATE], reducedMotion)
        t[P.GAZE_X] = (t[P.GAZE_X] + life.gazeOffsetX).coerceIn(-1f, 1f)
        t[P.GAZE_Y] = (t[P.GAZE_Y] + life.gazeOffsetY).coerceIn(-1f, 1f)
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
        compose(dt)
    }

    private fun compose(dt: Float) {
        val o = pose.v
        val s = springs.value
        System.arraycopy(s, 0, o, 0, P.COUNT)
        val motion = if (reducedMotion) REDUCED else 1f

        // Моргание: веки закрываются, брови чуть опускаются.
        val blink = life.blink
        o[P.OPEN_L] *= 1f - blink
        o[P.OPEN_R] *= 1f - blink
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

        // Голова доворачивается за взглядом; покачивание «нет».
        o[P.HEAD_X] += s[P.HEAD_FOLLOW_X] * FaceLayout.HEAD_FOLLOW_X * motion + s[P.SHAKE]
        o[P.HEAD_Y] += s[P.HEAD_FOLLOW_Y] * FaceLayout.HEAD_FOLLOW_Y * motion
        o[P.HEAD_TILT] += s[P.HEAD_FOLLOW_X] * 1.5f * motion

        // Речь робота: рот открывается по громкости, на ударениях — брови и кивок.
        val talk = s[P.TALK].coerceIn(0f, 1f)
        val env = talkEnv.value
        val onset = (env - talkSlow).coerceAtLeast(0f)
        o[P.MOUTH_OPEN] += talkOpen
        o[P.MOUTH_W] *= 1f - 0.1f * talk * env
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
        pose.time = nowMs / 1000f
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
        life.onMood(mood, nowMs)
        if (first || reducedMotion) return
        when (mood) {
            FaceMood.HAPPY -> kick(P.SQUASH, 1.3f)
            FaceMood.EXCITED -> {
                kick(P.SQUASH, 1.4f)
                kick(P.POP, 0.8f)
                kickLater(P.SQUASH, 1.2f, 320L)
            }
            FaceMood.SURPRISED -> {
                kick(P.POP, 2.2f)
                kick(P.SQUASH, -1.2f)
            }
            FaceMood.WAKE -> {
                kick(P.POP, 1.8f)
                kick(P.SQUASH, -1.0f)
            }
            FaceMood.ERROR -> kick(P.SHAKE, 450f)
            FaceMood.ACK -> kick(P.SQUASH, 0.6f)
            FaceMood.PETTED -> kick(P.SQUASH, 0.5f)
            FaceMood.LISTENING -> kick(P.POP, 0.4f)
            else -> Unit
        }
    }

    private fun onEmotionEnter(e: FaceEmotion) {
        if (reducedMotion || director.overlayWeight <= 0f) return
        when (e) {
            FaceEmotion.HAPPY, FaceEmotion.EXCITED -> kick(P.SQUASH, 1.0f)
            FaceEmotion.SURPRISED -> kick(P.POP, 1.6f)
            else -> Unit
        }
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
