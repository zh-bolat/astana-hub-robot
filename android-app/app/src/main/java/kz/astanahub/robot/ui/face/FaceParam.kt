package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.motion.SpringSpec

/**
 * Параметры процедурного лица. Каждый — одна пружина ([kz.astanahub.robot.ui.motion.SpringBank]); выражение —
 * целевые значения части параметров ([Expression]). Единицы — «единицы лица»: виртуальный холст 1400 × 790,
 * начало координат в центре (см. [FaceLayout]); углы — в градусах; доли — 0..1.
 *
 * Знаки: y вниз (как на экране). «Левый» — слева для зрителя.
 */
object FaceParam {
    /** Взгляд: −1 (влево/вверх) … 1 (вправо/вниз); глаза сдвигаются на [FaceLayout.GAZE_RANGE_X]/[FaceLayout.GAZE_RANGE_Y]. */
    const val GAZE_X = 0
    const val GAZE_Y = 1

    /** «Голова» — всё лицо целиком: сдвиг (ед.), наклон (°), масштаб. */
    const val HEAD_X = 2
    const val HEAD_Y = 3
    const val HEAD_TILT = 4
    const val HEAD_SCALE = 5

    /** Сплющивание «тела» (+ шире и ниже, − выше и уже) — пружина с отскоком, двигается толчками. */
    const val SQUASH = 6

    /** Надувание глаз (доля к размеру) — толчком при удивлении/пробуждении. */
    const val POP = 7

    /** Покачивание головой «нет» (ед. по x) — цель всегда 0, раскачивается толчками. */
    const val SHAKE = 8

    /** Полуширина и полувысота глаза (ед.). */
    const val EYE_W = 9
    const val EYE_H = 10

    /** Открытость глаза 0 (закрыт — линия) … 1. */
    const val OPEN_L = 11
    const val OPEN_R = 12

    /** «Щёки»: нижний край глаза поднимается, 1 — глаз-дуга «∩» (радость). */
    const val SMILE_L = 13
    const val SMILE_R = 14

    /** Изгиб закрытого глаза: + «∩» (довольный), − «∪» (спит). */
    const val ARC = 15

    /** Верхнее веко: доля глаза, закрытая сверху прямой кромкой (прищур, грусть, сонливость). */
    const val LID_L = 16
    const val LID_R = 17

    /** Наклон кромки века, °: + внешний угол ниже (грусть), − внутренний ниже (решимость). Зеркально для глаз. */
    const val LID_ANGLE = 18

    /** Расстояние между глазами (+ шире) и их высота (+ ниже), ед. */
    const val EYE_SPREAD = 19
    const val EYE_Y = 20

    /** Разный размер глаз: + левый больше, правый меньше (доля). */
    const val EYE_ASYM = 21

    /** Брови: подъём (ед., + выше), наклон (°, + внутренний конец выше — «домиком», − ниже — сосредоточен). */
    const val BROW_L = 22
    const val BROW_R = 23
    const val BROW_TILT_L = 24
    const val BROW_TILT_R = 25

    /** Изгиб бровей дугой вверх (ед.). */
    const val BROW_ARCH = 26
    const val BROW_ALPHA = 27

    /** Длина брови (ед.). */
    const val BROW_LEN = 28

    /** Рот: полуширина (ед.), подъём уголков (ед., − грусть), раскрытие 0..1, округлость 0 («D») … 1 («o»). */
    const val MOUTH_W = 29
    const val MOUTH_SMILE = 30
    const val MOUTH_OPEN = 31
    const val MOUTH_ROUND = 32

    /** Перекос рта: + правый уголок выше (ед.); сдвиг рта (ед.). */
    const val MOUTH_SKEW = 33
    const val MOUTH_X = 34
    const val MOUTH_Y = 35

    /** Украшения 0..1: «?» (не понял), «///» на щеках (поглаживание), капля (ошибка), «z» (сон). */
    const val QUESTION = 36
    const val BLUSH = 37
    const val SWEAT = 38
    const val ZZZ = 39

    /** Яркость свечения 0..1. */
    const val GLOW = 40

    /** Дыхание: амплитуда (множитель) и частота (Гц). */
    const val BREATH = 41
    const val BREATH_RATE = 42

    /** Микродвижения (множитель амплитуды). */
    const val MICRO = 43

    /** Насколько рот следует за громкостью речи робота (0..1). */
    const val TALK = 44

    /** Насколько лицо реагирует на громкость голоса посетителя (0..1). */
    const val LISTEN = 45

    /** Голова доворачивается за взглядом (внутренние, цель = взгляд × коэффициент). */
    const val HEAD_FOLLOW_X = 46
    const val HEAD_FOLLOW_Y = 47

    const val COUNT = 48

    val NAMES: Array<String> = arrayOf(
        "gazeX", "gazeY", "headX", "headY", "headTilt", "headScale", "squash", "pop", "shake",
        "eyeW", "eyeH", "openL", "openR", "smileL", "smileR", "arc", "lidL", "lidR", "lidAngle",
        "eyeSpread", "eyeY", "eyeAsym", "browL", "browR", "browTiltL", "browTiltR", "browArch", "browAlpha", "browLen",
        "mouthW", "mouthSmile", "mouthOpen", "mouthRound", "mouthSkew", "mouthX", "mouthY",
        "question", "blush", "sweat", "zzz", "glow", "breath", "breathRate", "micro", "talk", "listen",
        "headFollowX", "headFollowY",
    )

    fun indexOf(name: String): Int = NAMES.indexOfFirst { it.equals(name, ignoreCase = true) }
}

/**
 * Пружины лица по группам. Значения — в DESIGN.md («Пружины лица»). По умолчанию критическое затухание
 * (ζ = 1, без перелёта); отскок (ζ < 1) — только у «тела» ([BODY]), которое двигается толчками-импульсами.
 */
object FaceSprings {
    /** Форма глаз, бровей, рта: быстрый морфинг без перелёта. */
    val SHAPE = SpringSpec(dampingRatio = 1f, response = 0.30f)

    /** Взгляд: как `lookAt` прототипа — плавный переезд глаз за ~.55 с (`translate .55s cubic-bezier(.3,.7,.3,1)`). */
    val GAZE = SpringSpec(dampingRatio = 1f, response = 0.45f)

    /** Голова: медленнее глаз — глаза ведут, голова догоняет. */
    val HEAD = SpringSpec(dampingRatio = 1f, response = 0.55f)

    /** «Тело» (сплющивание, надувание): с отскоком, движется только от толчков. */
    val BODY = SpringSpec(dampingRatio = 0.55f, response = 0.42f)

    /** Покачивание головой «нет»: слабое затухание — 2–3 качания от одного толчка. */
    val SHAKE = SpringSpec(dampingRatio = 0.25f, response = 0.32f)

    /** Раскрытие рта в выражении (речь добавляется поверх, см. [FaceAudioEnvelope]). */
    val MOUTH = SpringSpec(dampingRatio = 1f, response = 0.2f)

    /** Украшения: «?», румянец, капля. */
    val DECOR = SpringSpec(dampingRatio = 1f, response = 0.32f)

    /** Медленные «настроечные» параметры: свечение, дыхание, микродвижения, «z». */
    val SLOW = SpringSpec(dampingRatio = 1f, response = 0.9f)

    /** Коэффициенты реакции на звук. */
    val GAIN = SpringSpec(dampingRatio = 1f, response = 0.22f)

    /** Засыпание: веки и голова опускаются медленно. */
    val SLEEPY = SpringSpec(dampingRatio = 1f, response = 1.1f)

    fun forParam(p: Int): SpringSpec = when (p) {
        FaceParam.GAZE_X, FaceParam.GAZE_Y -> GAZE
        FaceParam.HEAD_X, FaceParam.HEAD_Y, FaceParam.HEAD_TILT, FaceParam.HEAD_SCALE,
        FaceParam.HEAD_FOLLOW_X, FaceParam.HEAD_FOLLOW_Y -> HEAD
        FaceParam.SQUASH, FaceParam.POP -> BODY
        FaceParam.SHAKE -> SHAKE
        FaceParam.MOUTH_OPEN -> MOUTH
        FaceParam.QUESTION, FaceParam.BLUSH, FaceParam.SWEAT, FaceParam.BROW_ALPHA -> DECOR
        FaceParam.ZZZ, FaceParam.GLOW, FaceParam.BREATH, FaceParam.BREATH_RATE, FaceParam.MICRO -> SLOW
        FaceParam.TALK, FaceParam.LISTEN -> GAIN
        else -> SHAPE
    }

    fun defaults(): Array<SpringSpec> = Array(FaceParam.COUNT) { forParam(it) }
}

/** Геометрия виртуального холста лица (ед.). Рисовальщик масштабирует её под экран. */
object FaceLayout {
    /** Виртуальный холст: 1400 × 790 ед. вписывается в экран (на 1920 × 1080 — 1 ед. ≈ 1.37 px). */
    const val WIDTH = 1400f
    const val HEIGHT = 790f

    /** Центры глаз: ±[EYE_X] от центра, на [EYE_Y] (выше центра — отрицательно). */
    const val EYE_X = 219f
    const val EYE_Y = 0f

    /** Сдвиг глаз при взгляде в край (|gaze| = 1). */
    const val GAZE_RANGE_X = 70f
    const val GAZE_RANGE_Y = 48f

    /** Брови: от верхнего края глаза до средней линии брови. */
    const val BROW_GAP = 64f

    /** Брови следуют за взглядом на эту долю. */
    const val BROW_FOLLOW = 0.5f

    /** Рот: центр по y; следует за взглядом на [MOUTH_FOLLOW]. */
    const val MOUTH_Y = 205f
    const val MOUTH_FOLLOW = 0.3f

    /** Толщина штрихов (бровь, рот, контур глаза). */
    const val STROKE = 34f

    /** Перспектива взгляда в сторону: дальний глаз меньше на эту долю. */
    const val PERSPECTIVE = 0.07f

    /** Голова доворачивается за взглядом: ед. на |gaze| = 1. */
    const val HEAD_FOLLOW_X = 34f
    const val HEAD_FOLLOW_Y = 12f
}
