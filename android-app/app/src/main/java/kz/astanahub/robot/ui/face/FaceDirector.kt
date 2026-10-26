package kz.astanahub.robot.ui.face

/** Что делает приложение (из MainUiState.phase) — вход для [FaceDirector]. */
enum class FaceActivity { IDLE, LISTENING, THINKING, LOOKING, SPEAKING, TRAVELING }

/**
 * Короткие реакции на события приложения (эмитит MainViewModel.faceCues, жесты — экран):
 * лицо показывает их поверх текущего занятия ([FaceDirector.REACTIONS] — какое выражение и сколько держится).
 */
enum class FaceCue {
    /** Сбой: сервер / камера / связь. */
    ERROR,
    /** Не понял: не расслышал, места нет на карте, ответа в базе нет (`found: false`). */
    CONFUSED,
    /** Подошёл посетитель — приветствие. */
    GREET,
    /** Поездка: приехали / уже на месте. */
    ARRIVED,
    /** Поездка не удалась (не локализован, ошибка, таймаут). */
    TRIP_FAILED,
    /** Поездку остановили («Стоп»). */
    TRIP_CANCELLED,
    /** Удивление (тап в тишине — M2). */
    SURPRISE,
    ;

    companion object {
        fun fromName(name: String?): FaceCue? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/** Входы лица: пишет контроллер экрана (главный поток), читает [FaceDirector] в кадре. */
class FaceInputs {
    /** false — режим SLEEP (заставка): зевок → сон. */
    var awake: Boolean = true
    var activity: FaceActivity = FaceActivity.IDLE
    var emotion: FaceEmotion = FaceEmotion.NEUTRAL

    /** Посетитель говорит: на экране его промежуточный текст (ASR partial / live user_partial). */
    var userSpeaking: Boolean = false

    /** Идёт поглаживание (жест — M2). */
    var petting: Boolean = false
}

/**
 * Голос посетителя по громкости микрофона (огибающая 0..1) с гистерезисом: включается, если громко
 * [onMillis] подряд, выключается после [offMillis] тишины — лицо не дёргается от коротких шумов и пауз.
 */
class VoiceDetector(
    private val onLevel: Float = 0.22f,
    private val offLevel: Float = 0.10f,
    private val onMillis: Long = 100,
    private val offMillis: Long = 650,
) {
    var active: Boolean = false
        private set
    private var aboveSince = -1L
    private var belowSince = -1L

    fun update(level: Float, nowMs: Long): Boolean {
        if (level >= onLevel) {
            if (aboveSince < 0) aboveSince = nowMs
            belowSince = -1
            if (!active && nowMs - aboveSince >= onMillis) active = true
        } else {
            aboveSince = -1
            if (level <= offLevel) {
                if (belowSince < 0) belowSince = nowMs
                if (active && nowMs - belowSince >= offMillis) active = false
            } else {
                belowSince = -1
            }
        }
        return active
    }

    fun reset() {
        active = false
        aboveSince = -1
        belowSince = -1
    }
}

/**
 * Машина состояний лица: по входам ([FaceInputs]), реакциям ([FaceCue]) и времени выбирает [mood] и наложение
 * эмоции. Чистый Kotlin, время — параметр: тесты без реального времени.
 *
 * Приоритет (сверху — сильнее): отладочное [forcedMood] → сон (зевок [YAWN_MS] → сон) → пробуждение ([WAKE_MS]) →
 * поглаживание → реакция ([REACTIONS], пока не истекла или не началось новое дело) → поездка → думает → смотрит →
 * говорит → слушает (голос посетителя) → покой.
 */
class FaceDirector(private val voice: VoiceDetector = VoiceDetector()) {

    var mood: FaceMood = FaceMood.IDLE
        private set

    /** Когда началось текущее состояние (мс). */
    var moodSince: Long = 0L
        private set

    /** Состояние сменилось на последнем [update] (толчки «входа» — у движка). */
    var moodChanged: Boolean = false
        private set

    var emotion: FaceEmotion = FaceEmotion.NEUTRAL
        private set
    var emotionChanged: Boolean = false
        private set

    /** Сила наложения эмоции для текущего состояния. */
    val overlayWeight: Float get() = Expressions.overlayWeight(mood)

    /** Голос посетителя слышен (по микрофону). */
    val voiceActive: Boolean get() = voice.active

    /** Отладка (эмулятор): принудительное состояние / эмоция; null — по событиям. */
    var forcedMood: FaceMood? = null
    var forcedEmotion: FaceEmotion? = null

    private var reaction: FaceMood? = null
    private var reactionUntil = 0L
    private var lastActivity = FaceActivity.IDLE
    private var lastUserSpeaking = false

    private var started = false
    private var wasAwake = true
    private var sleepStartedAt = Long.MIN_VALUE / 2
    private var skipYawn = false
    private var wakeUntil = Long.MIN_VALUE / 2
    private var pettingUntil = Long.MIN_VALUE / 2

    /** Реакция на событие; держится [REACTIONS] мс (новое дело — думает/ведёт/смотрит/голос — обрывает раньше). */
    fun cue(c: FaceCue, nowMs: Long) {
        val (m, ms) = REACTIONS.getValue(c)
        reaction = m
        reactionUntil = nowMs + ms
    }

    fun update(inputs: FaceInputs, micLevel: Float, nowMs: Long) {
        val voiceOn = voice.update(micLevel, nowMs)

        // Сон и пробуждение.
        if (!started) {
            started = true
            wasAwake = inputs.awake
            if (!inputs.awake) skipYawn = true // запустились уже во сне — без зевка
        }
        if (wasAwake && !inputs.awake) {
            sleepStartedAt = nowMs
            skipYawn = false
            reaction = null
        } else if (!wasAwake && inputs.awake) {
            wakeUntil = nowMs + WAKE_MS
            voice.reset()
        }
        wasAwake = inputs.awake

        // Новое дело обрывает реакцию: начал думать / смотреть / поехал / посетитель заговорил.
        val activity = inputs.activity
        if (activity != lastActivity &&
            (activity == FaceActivity.THINKING || activity == FaceActivity.LOOKING || activity == FaceActivity.TRAVELING)
        ) {
            reaction = null
        }
        if (inputs.userSpeaking && !lastUserSpeaking) reaction = null
        lastActivity = activity
        lastUserSpeaking = inputs.userSpeaking
        if (reaction != null && nowMs >= reactionUntil) reaction = null

        if (inputs.petting) pettingUntil = nowMs + PETTING_TAIL_MS

        val next = forcedMood ?: when {
            !inputs.awake -> if (!skipYawn && nowMs - sleepStartedAt < YAWN_MS) FaceMood.YAWN else FaceMood.SLEEP
            nowMs < wakeUntil -> FaceMood.WAKE
            inputs.petting || nowMs < pettingUntil -> FaceMood.PETTED
            reaction != null -> reaction!!
            activity == FaceActivity.TRAVELING -> FaceMood.TRAVEL
            activity == FaceActivity.THINKING -> FaceMood.THINKING
            activity == FaceActivity.LOOKING -> FaceMood.LOOKING
            activity == FaceActivity.SPEAKING -> FaceMood.SPEAKING
            inputs.userSpeaking || voiceOn -> FaceMood.LISTENING
            else -> FaceMood.IDLE
        }
        moodChanged = next != mood
        if (moodChanged) {
            mood = next
            moodSince = nowMs
        }

        val nextEmotion = forcedEmotion ?: if (inputs.awake) inputs.emotion else FaceEmotion.NEUTRAL
        emotionChanged = nextEmotion != emotion
        emotion = nextEmotion
    }

    companion object {
        /** Зевок перед сном, мс. */
        const val YAWN_MS = 2_400L

        /** Бодрое пробуждение, мс. */
        const val WAKE_MS = 900L

        /** Довольное лицо держится после того, как гладить перестали, мс. */
        const val PETTING_TAIL_MS = 700L

        /** Реакция → выражение и сколько держится (мс). */
        val REACTIONS: Map<FaceCue, Pair<FaceMood, Long>> = mapOf(
            FaceCue.ERROR to (FaceMood.ERROR to 2_600L),
            FaceCue.TRIP_FAILED to (FaceMood.ERROR to 2_600L),
            FaceCue.CONFUSED to (FaceMood.CONFUSED to 2_800L),
            FaceCue.GREET to (FaceMood.EXCITED to 2_000L),
            FaceCue.ARRIVED to (FaceMood.HAPPY to 2_200L),
            FaceCue.TRIP_CANCELLED to (FaceMood.ACK to 1_400L),
            FaceCue.SURPRISE to (FaceMood.SURPRISED to 1_300L),
        )
    }
}
