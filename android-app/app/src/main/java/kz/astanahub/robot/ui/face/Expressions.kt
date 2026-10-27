package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.face.FaceParam as P

/**
 * Состояние лица — что робот «делает» сейчас. Выбирает [FaceDirector] по событиям приложения;
 * у каждого — целевой набор параметров ([Expressions.mood]) и своё поведение «жизни» ([FaceLife]).
 */
enum class FaceMood {
    IDLE, LISTENING, THINKING, SPEAKING, LOOKING, TRAVEL,
    HAPPY, EXCITED, ACK, CONFUSED, ERROR, SURPRISED, PETTED,
    YAWN, SLEEP, WAKE,

    /** Настроения прототипа (`setMood`): смущение, восторг (глаза-звёзды), «смотрит», «кружится голова». */
    BLUSH, SPARKLE, WIDE, DIZZY,
    ;

    companion object {
        fun fromName(name: String?): FaceMood? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * Выражение глаз прототипа (`EXPR` в `docs/design/robot-mvp.html`): каждое — своя фигура, рисуется
 * [FaceRenderer] по путям прототипа; смена имени — «раскрытие» `exprIn`.
 */
enum class ProtoExpr { NEUTRAL, LISTEN, SPEAK, THINK, GUIDE, WIDE, HAPPY, BLUSH, SPARKLE, BORED, CONFUSED, DIZZY, SAD }

/** Эмоция с сервера (`emotion`) — накладывается поверх состояния ([Expressions.overlay]). */
enum class FaceEmotion {
    NEUTRAL, HAPPY, THINKING, SURPRISED, SAD, EXCITED;

    companion object {
        fun fromName(name: String?): FaceEmotion? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * Выражение: целевые значения части параметров ([mask] — какие заданы). Неизменяемо после создания;
 * создаются один раз при старте, в кадре — только чтение.
 */
class Expression internal constructor(val name: String, private val values: FloatArray, private val mask: BooleanArray) {
    fun has(p: Int): Boolean = mask[p]

    operator fun get(p: Int): Float = values[p]

    /** Записать заданные параметры в [out] целиком. */
    fun applyTo(out: FloatArray) {
        for (i in 0 until P.COUNT) if (mask[i]) out[i] = values[i]
    }

    /** Смешать заданные параметры с [out] с весом [weight] (0 — без изменений, 1 — как в выражении). */
    fun blendInto(out: FloatArray, weight: Float) {
        if (weight <= 0f) return
        val w = if (weight > 1f) 1f else weight
        for (i in 0 until P.COUNT) if (mask[i]) out[i] += (values[i] - out[i]) * w
    }

    override fun toString(): String = "Expression($name)"
}

/** Построитель выражений: `expression("happy") { eyeSmile(1f); mouth(w = 92f, smile = 34f) }`. */
class ExpressionBuilder internal constructor(base: Expression?) {
    internal val values = FloatArray(P.COUNT)
    internal val mask = BooleanArray(P.COUNT)

    init {
        base?.applyTo(values)
        if (base != null) for (i in 0 until P.COUNT) mask[i] = base.has(i)
    }

    operator fun set(p: Int, v: Float) {
        values[p] = v
        mask[p] = true
    }

    fun gaze(x: Float, y: Float) { this[P.GAZE_X] = x; this[P.GAZE_Y] = y }
    fun head(x: Float = 0f, y: Float = 0f, tilt: Float = 0f) { this[P.HEAD_X] = x; this[P.HEAD_Y] = y; this[P.HEAD_TILT] = tilt }
    fun eyeSize(w: Float, h: Float) { this[P.EYE_W] = w; this[P.EYE_H] = h }
    fun eyesOpen(v: Float) { this[P.OPEN_L] = v; this[P.OPEN_R] = v }
    fun eyeSmile(v: Float) { this[P.SMILE_L] = v; this[P.SMILE_R] = v }
    fun lids(v: Float, angle: Float = 0f) { this[P.LID_L] = v; this[P.LID_R] = v; this[P.LID_ANGLE] = angle }
    fun brows(lift: Float, tilt: Float = 0f) { this[P.BROW_L] = lift; this[P.BROW_R] = lift; this[P.BROW_TILT_L] = tilt; this[P.BROW_TILT_R] = tilt }
    fun browL(lift: Float, tilt: Float) { this[P.BROW_L] = lift; this[P.BROW_TILT_L] = tilt }
    fun browR(lift: Float, tilt: Float) { this[P.BROW_R] = lift; this[P.BROW_TILT_R] = tilt }
    fun mouth(w: Float, smile: Float, open: Float = 0f, round: Float = 0f) {
        this[P.MOUTH_W] = w; this[P.MOUTH_SMILE] = smile; this[P.MOUTH_OPEN] = open; this[P.MOUTH_ROUND] = round
    }
}

fun expression(name: String, base: Expression? = null, block: ExpressionBuilder.() -> Unit): Expression {
    val b = ExpressionBuilder(base).apply(block)
    return Expression(name, b.values, b.mask)
}

/**
 * Каталог выражений. Числа — единицы лица ([FaceLayout]); таблица с пояснениями — в docs/ui-redesign/DESIGN.md.
 * Выражение состояния строится поверх [NEUTRAL] (все параметры заданы), эмоции — частичные наложения.
 */
object Expressions {
    val NEUTRAL: Expression = expression("neutral") {
        gaze(0f, 0f)
        head(0f, 0f, 0f)
        this[P.HEAD_SCALE] = 1f
        this[P.SQUASH] = 0f
        this[P.POP] = 0f
        this[P.SHAKE] = 0f
        eyeSize(62f, 79f)
        eyesOpen(1f)
        eyeSmile(0f)
        this[P.ARC] = 0f
        lids(0f, 0f)
        this[P.EYE_SPREAD] = 0f
        this[P.EYE_Y] = 0f
        this[P.EYE_ASYM] = 0f
        brows(0f, 0f)
        this[P.BROW_ARCH] = 8f
        this[P.BROW_ALPHA] = 1f
        this[P.BROW_LEN] = 118f
        mouth(w = 62f, smile = 10f, open = 0f, round = 0f)
        this[P.MOUTH_SKEW] = 0f
        this[P.MOUTH_X] = 0f
        this[P.MOUTH_Y] = 0f
        this[P.QUESTION] = 0f
        this[P.BLUSH] = 0f
        this[P.SWEAT] = 0f
        this[P.ZZZ] = 0f
        this[P.GLOW] = 0.6f
        this[P.BREATH] = 1f
        this[P.BREATH_RATE] = 0.24f
        this[P.MICRO] = 1f
        this[P.TALK] = 0f
        this[P.LISTEN] = 0f
        this[P.HEAD_FOLLOW_X] = 0f
        this[P.HEAD_FOLLOW_Y] = 0f
    }

    private val IDLE = expression("idle", NEUTRAL) {}

    /** Слушает (прототип `listen`: овалы 21 × 26 вместо 18 × 22), без наклона; реагирует на голос. */
    private val LISTENING = expression("listening", NEUTRAL) {
        eyeSize(75f, 96.5f)
        brows(12f)
        this[P.BROW_ARCH] = 11f
        mouth(w = 50f, smile = 6f)
        this[P.LISTEN] = 1f
        this[P.GLOW] = 0.75f
        this[P.BREATH] = 0.8f
    }

    /** Думает (прототип `think`): две ровные полоски и «?», всё стоит на месте. */
    private val THINKING = expression("thinking", NEUTRAL) {
        eyeSize(60f, 78f)
        eyesOpen(0f)
        this[P.ARC] = 0f
        this[P.QUESTION] = 1f
        browL(2f, -6f)
        browR(22f, 4f)
        mouth(w = 40f, smile = -2f)
        this[P.MOUTH_SKEW] = 6f
        this[P.MOUTH_X] = 30f
        this[P.GLOW] = 0.7f
        this[P.MICRO] = 0.6f
    }

    /** Говорит: рот от громкости речи, чуть ярче. */
    private val SPEAKING = expression("speaking", NEUTRAL) {
        mouth(w = 62f, smile = 10f)
        this[P.BROW_ARCH] = 10f
        this[P.TALK] = 1f
        this[P.GLOW] = 0.85f
    }

    /** Смотрит в камеру («Что это?»): сосредоточенно и с интересом, рот «о». */
    private val LOOKING = expression("looking", NEUTRAL) {
        gaze(0f, 0.1f)
        eyeSize(64f, 98f)
        lids(0.06f)
        brows(8f, -4f)
        mouth(w = 30f, smile = 0f, open = 0.18f, round = 1f)
        this[P.GLOW] = 0.8f
    }

    /** Ведёт к месту (прототип `guide`): глаза сдвинуты вправо — по ходу (сдвиг рисует фигура). */
    private val TRAVEL = expression("travel", NEUTRAL) {
        eyeSize(60f, 82f)
        brows(-2f, -9f)
        mouth(w = 66f, smile = 18f)
        this[P.TALK] = 0.8f
        this[P.GLOW] = 0.8f
    }

    /** Радуется / приветствует: глаза-дуги, широкая улыбка. */
    private val HAPPY = expression("happy", NEUTRAL) {
        eyeSize(64f, 84f)
        eyeSmile(1f)
        brows(16f)
        this[P.BROW_ARCH] = 14f
        mouth(w = 92f, smile = 34f, open = 0.32f)
        head(y = -8f)
        this[P.BLUSH] = 0.35f
        this[P.GLOW] = 1f
        this[P.TALK] = 0.7f
    }

    /** Восторг: как радость, но шире и с подпрыгиванием (толчки — в [FaceDirector.entryKick]). */
    private val EXCITED = expression("excited", HAPPY) {
        eyeSize(68f, 90f)
        brows(22f)
        mouth(w = 96f, smile = 36f, open = 0.45f)
        this[P.BLUSH] = 0.5f
    }

    /** «Хорошо»: короткая мягкая улыбка (остановили поездку). */
    private val ACK = expression("ack", NEUTRAL) {
        eyeSmile(0.45f)
        brows(6f)
        mouth(w = 70f, smile = 20f)
        this[P.TALK] = 0.8f
        this[P.GLOW] = 0.8f
    }

    /** Не понял (прототип `confused`): полоски и «?», без наклона. */
    private val CONFUSED = expression("confused", NEUTRAL) {
        eyesOpen(0f)
        browL(28f, 6f)
        browR(-4f, -10f)
        this[P.BROW_ARCH] = 14f
        mouth(w = 44f, smile = -8f)
        this[P.MOUTH_SKEW] = 16f
        this[P.MOUTH_X] = 34f
        this[P.QUESTION] = 1f
        this[P.TALK] = 0.8f
    }

    /** Ошибка: милое огорчение — веки и брови «домиком», уголки вниз, капля; формой, не цветом. */
    private val ERROR = expression("error", NEUTRAL) {
        gaze(0f, 0.25f)
        eyeSize(60f, 80f)
        eyesOpen(0f)
        this[P.ARC] = 0f
        lids(0.32f, 18f)
        brows(6f, 18f)
        mouth(w = 44f, smile = -18f)
        head(y = 12f)
        this[P.SWEAT] = 1f
        this[P.GLOW] = 0.45f
        this[P.TALK] = 0.7f
    }

    /** Удивление: глаза большие, брови высоко, рот «о». */
    private val SURPRISED = expression("surprised", NEUTRAL) {
        eyeSize(72f, 110f)
        brows(32f)
        this[P.BROW_ARCH] = 18f
        mouth(w = 26f, smile = 0f, open = 0.7f, round = 1f)
        head(y = -10f)
        this[P.GLOW] = 1f
        this[P.TALK] = 0.5f
    }

    /** Поглаживание: жмурится дугами «^ ^», румянец, довольная улыбка, голова льнёт к руке. */
    private val PETTED = expression("petted", NEUTRAL) {
        eyesOpen(0f)
        this[P.ARC] = 0.9f
        brows(6f, 8f)
        mouth(w = 78f, smile = 30f, open = 0.14f)
        this[P.BLUSH] = 1f
        this[P.GLOW] = 1f
        this[P.MICRO] = 0.5f
        this[P.BREATH] = 1.3f
        // Гладят, пока робот говорит: довольный рот продолжает двигаться по речи (ревью M2-r1, M3).
        this[P.TALK] = 0.6f
    }

    /** Зевок перед сном: рот широко «о», глаза зажмурены, голова назад. */
    private val YAWN = expression("yawn", NEUTRAL) {
        eyesOpen(0.05f)
        this[P.ARC] = 0.5f
        brows(16f, 10f)
        mouth(w = 40f, smile = 0f, open = 1f, round = 0.9f)
        head(y = -14f, tilt = -7f)
        this[P.GLOW] = 0.5f
    }

    /** Заставка (прототип «никогда не спит»): глаза открыты, бродят по сторонам — фигуру выбирает [FaceLife]. */
    private val SLEEP = expression("sleep", NEUTRAL) {
        this[P.GLOW] = 0.6f
    }

    /** Смущение (прототип `blush`): тонкие дуги и красный румянец. */
    private val BLUSH = expression("blush", NEUTRAL) {
        eyesOpen(0f)
        this[P.ARC] = 0.9f
        this[P.BLUSH] = 1f
        this[P.GLOW] = 1f
    }

    /** Восторг (прототип `sparkle`): глаза — четырёхконечные звёзды. */
    private val SPARKLE = expression("sparkle", NEUTRAL) {
        this[P.GLOW] = 1f
    }

    /** «Смотрит» (прототип `wide`): глаза 21 × 26 и искорка. */
    private val WIDE = expression("wide", NEUTRAL) {
        eyeSize(75f, 96.5f)
        this[P.GLOW] = 1f
    }

    /** Нет связи (прототип `dizzy`): спирали вместо глаз. */
    private val DIZZY = expression("dizzy", NEUTRAL) {
        eyesOpen(0f)
        this[P.GLOW] = 0.8f
    }

    /** Пробуждение: бодро — глаза распахнуты, брови вверх, улыбка. */
    private val WAKE = expression("wake", NEUTRAL) {
        eyeSize(70f, 106f)
        brows(20f)
        this[P.BROW_ARCH] = 14f
        mouth(w = 54f, smile = 18f, open = 0.2f)
        this[P.GLOW] = 1f
    }

    private val MOODS: Array<Expression> = Array(FaceMood.entries.size) { i ->
        when (FaceMood.entries[i]) {
            FaceMood.IDLE -> IDLE
            FaceMood.LISTENING -> LISTENING
            FaceMood.THINKING -> THINKING
            FaceMood.SPEAKING -> SPEAKING
            FaceMood.LOOKING -> LOOKING
            FaceMood.TRAVEL -> TRAVEL
            FaceMood.HAPPY -> HAPPY
            FaceMood.EXCITED -> EXCITED
            FaceMood.ACK -> ACK
            FaceMood.CONFUSED -> CONFUSED
            FaceMood.ERROR -> ERROR
            FaceMood.SURPRISED -> SURPRISED
            FaceMood.PETTED -> PETTED
            FaceMood.YAWN -> YAWN
            FaceMood.SLEEP -> SLEEP
            FaceMood.WAKE -> WAKE
            FaceMood.BLUSH -> BLUSH
            FaceMood.SPARKLE -> SPARKLE
            FaceMood.WIDE -> WIDE
            FaceMood.DIZZY -> DIZZY
        }
    }

    fun mood(m: FaceMood): Expression = MOODS[m.ordinal]

    /**
     * Фигура глаз прототипа для состояния ([FaceMood.SLEEP] — заставка: глаза открыты, «скучают / удивляются /
     * радуются» по [idle] — его выбирает [FaceLife]). Классы `#ind3` прототипа: listen / think / speak / guide /
     * unclear → confused / look → wide; настроения `setMood`: happy / blush / sparkle / wide / dizzy.
     */
    fun proto(m: FaceMood, idle: ProtoExpr = ProtoExpr.NEUTRAL): ProtoExpr = when (m) {
        FaceMood.IDLE, FaceMood.WAKE, FaceMood.YAWN -> ProtoExpr.NEUTRAL
        FaceMood.LISTENING -> ProtoExpr.LISTEN
        FaceMood.THINKING -> ProtoExpr.THINK
        FaceMood.SPEAKING -> ProtoExpr.SPEAK
        FaceMood.LOOKING, FaceMood.SURPRISED, FaceMood.WIDE -> ProtoExpr.WIDE
        FaceMood.TRAVEL -> ProtoExpr.GUIDE
        FaceMood.HAPPY, FaceMood.EXCITED, FaceMood.ACK -> ProtoExpr.HAPPY
        FaceMood.CONFUSED -> ProtoExpr.CONFUSED
        FaceMood.ERROR -> ProtoExpr.SAD
        FaceMood.PETTED, FaceMood.BLUSH -> ProtoExpr.BLUSH
        FaceMood.SPARKLE -> ProtoExpr.SPARKLE
        FaceMood.DIZZY -> ProtoExpr.DIZZY
        FaceMood.SLEEP -> idle
    }

    /** Моргают только овалы с классом `bl` прототипа: обычное, слушает, ведёт (говорит и «смотрит» — нет). */
    fun blinks(e: ProtoExpr): Boolean = e == ProtoExpr.NEUTRAL || e == ProtoExpr.LISTEN || e == ProtoExpr.GUIDE

    // --- Эмоции сервера: частичные наложения. Пока робот говорит, раскрытие рта — от речи (см. FaceEngine). ---

    private val E_HAPPY = expression("emotion:happy") {
        eyeSmile(0.75f)
        brows(12f)
        this[P.BROW_ARCH] = 13f
        this[P.MOUTH_SMILE] = 28f
        this[P.MOUTH_W] = 80f
        this[P.BLUSH] = 0.2f
        this[P.GLOW] = 1f
    }

    private val E_EXCITED = expression("emotion:excited") {
        eyeSmile(0.85f)
        eyeSize(66f, 92f)
        brows(18f)
        this[P.MOUTH_SMILE] = 32f
        this[P.MOUTH_W] = 86f
        this[P.BLUSH] = 0.35f
        this[P.GLOW] = 1f
    }

    private val E_SURPRISED = expression("emotion:surprised") {
        eyeSize(70f, 106f)
        brows(26f)
        this[P.BROW_ARCH] = 16f
        mouth(w = 34f, smile = 2f, open = 0.4f, round = 1f)
    }

    private val E_SAD = expression("emotion:sad") {
        lids(0.24f, 16f)
        brows(4f, 14f)
        this[P.MOUTH_SMILE] = -14f
        this[P.MOUTH_W] = 50f
        this[P.HEAD_Y] = 6f
        this[P.GLOW] = 0.5f
    }

    private val E_THINKING = expression("emotion:thinking") {
        gaze(0.45f, -0.55f)
        browR(16f, 4f)
        this[P.MOUTH_X] = 18f
        this[P.MOUTH_SKEW] = 5f
    }

    /** Наложение эмоции; NEUTRAL — null. */
    fun overlay(e: FaceEmotion): Expression? = when (e) {
        FaceEmotion.NEUTRAL -> null
        FaceEmotion.HAPPY -> E_HAPPY
        FaceEmotion.EXCITED -> E_EXCITED
        FaceEmotion.SURPRISED -> E_SURPRISED
        FaceEmotion.SAD -> E_SAD
        FaceEmotion.THINKING -> E_THINKING
    }

    /**
     * Сила эмоции сервера поверх состояния — 0 везде: в прототипе эмоций поверх нет, пока робот говорит, глаза круглые;
     * настроение показывается после ответа (эмодзи → [FaceCue.MOOD_HAPPY] и др., MainActivity).
     */
    @Suppress("UNUSED_PARAMETER")
    fun overlayWeight(m: FaceMood): Float = 0f

    /**
     * Темп пружин формы в состоянии: засыпание и сон — медленно (веки тяжелеют), пробуждение — бодро.
     * Множитель к response пружин формы, рта и головы.
     */
    fun tempo(m: FaceMood): Float = when (m) {
        FaceMood.YAWN -> 2.4f
        FaceMood.WAKE -> 0.8f
        FaceMood.PETTED -> 1.4f
        else -> 1f
    }
}
