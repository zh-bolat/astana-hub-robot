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
    /** Не понял: не расслышал (400), места нет на карте. (`found: false` — не реакция: так помечены и ответы из общих знаний.) */
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

    /** Настроение после ответа (прототип: эмодзи в конце субтитров → `setMood(…, 2200)`). */
    MOOD_HAPPY, MOOD_BLUSH, MOOD_SPARKLE, MOOD_WIDE, MOOD_CONFUSED,

    /** Смена костюма (прототип `setSkin`: обычный — happy, казахский — blush, айтишник — wide, космонавт — sparkle; 1,8 с). */
    COSTUME_HAPPY, COSTUME_BLUSH, COSTUME_WIDE, COSTUME_SPARKLE,

    /** Пропала связь (прототип: `dizzy` 2,5 с). */
    OFFLINE,
    ;

    companion object {
        fun fromName(name: String?): FaceCue? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/** Входы лица: пишет контроллер экрана (главный поток), читает [FaceDirector] в кадре. */
class FaceInputs {
    /** false — робот спит (MF4): прикрытые глаза и значок сна. */
    var awake: Boolean = true
    var activity: FaceActivity = FaceActivity.IDLE
    var emotion: FaceEmotion = FaceEmotion.NEUTRAL

    /** Посетитель говорит: на экране его промежуточный текст (ASR partial / live user_partial). */
    var userSpeaking: Boolean = false

    /** Идёт поглаживание (жест [FaceGestureRecognizer]). */
    var petting: Boolean = false

    /** Палец на лице: координаты от центра экрана, −1..1 (лицо смотрит на палец). */
    var touching: Boolean = false
    var touchX: Float = 0f
    var touchY: Float = 0f

    /** Удержание для входа в админку: время касания (uptime, мс); −1 — нет. */
    var holdStartMs: Long = -1L

    /** Скорость пальца при поглаживании, px/с — голова льнёт к руке. */
    var strokeVx: Float = 0f
    var strokeVy: Float = 0f

    /** Остров where_is: глаза смотрят в сторону места (−1..1 по экрану), пока остров на экране. */
    var looking: Boolean = false
    var lookX: Float = 0f
    var lookY: Float = 0f
}

/**
 * Голос посетителя по VAD (адаптивный VAD приложения или `user_speaking` сервера) с удержанием [holdMillis]:
 * короткие паузы между словами не роняют «слушаю». Громкость сюда не входит — шум зала не включает «слушаю».
 */
class VoiceHold(private val holdMillis: Long = 500) {
    var active: Boolean = false
        private set
    private var lastVoice = Long.MIN_VALUE / 2

    fun update(voice: Boolean, nowMs: Long): Boolean {
        if (voice) lastVoice = nowMs
        active = voice || nowMs - lastVoice < holdMillis
        return active
    }

    fun reset() {
        active = false
        lastVoice = Long.MIN_VALUE / 2
    }
}

/**
 * Машина состояний лица: по входам ([FaceInputs]), реакциям ([FaceCue]) и времени выбирает [mood] и наложение
 * эмоции. Чистый Kotlin, время — параметр: тесты без реального времени.
 *
 * Приоритет (сверху — сильнее): отладочное [forcedMood] → сон (зевок [YAWN_MS] → сон) → пробуждение ([WAKE_MS]) →
 * поглаживание → реакция ([REACTIONS], пока не истекла или не началось новое дело) → поездка → думает → смотрит →
 * говорит → слушает (текст посетителя или VAD — не громкость) → покой.
 */
class FaceDirector(private val voice: VoiceHold = VoiceHold()) {

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

    /** Посетитель говорит (по VAD, с удержанием). */
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

    /** Вторая ступень реакции (приветствие: «смотрит» → «слушает»). */
    private var reactionNext: FaceMood? = null
    private var reactionNextMs = 0L

    /** Реакция на событие; держится [REACTIONS] мс (новое дело — думает/ведёт/смотрит/голос — обрывает раньше). */
    fun cue(c: FaceCue, nowMs: Long) {
        val (m, ms) = REACTIONS.getValue(c)
        reaction = m
        reactionUntil = nowMs + ms
        // Прототип «посетитель подошёл»: глаза распахнулись (450 мс), потом «слушает» (700 мс), потом речь.
        if (c == FaceCue.GREET) {
            reactionNext = FaceMood.LISTENING
            reactionNextMs = GREET_LISTEN_MS
        } else {
            reactionNext = null
        }
    }

    /** [voice] — VAD: посетитель говорит сейчас ([FaceAudioSource.voiceActive]). */
    fun update(inputs: FaceInputs, voice: Boolean, nowMs: Long) {
        val voiceOn = this.voice.update(voice, nowMs)

        // Сон и пробуждение.
        if (!started) {
            started = true
            wasAwake = inputs.awake
            if (!inputs.awake) skipYawn = true // запустились уже во сне — без зевка
        }
        if (wasAwake && !inputs.awake) {
            sleepStartedAt = nowMs
            skipYawn = true // засыпает сразу, без зевка (прототип `sleep`)
            reaction = null
            reactionNext = null
        } else if (!wasAwake && inputs.awake) {
            wakeUntil = nowMs + WAKE_MS
            this.voice.reset()
        }
        wasAwake = inputs.awake

        // Новое дело обрывает реакцию: начал думать / смотреть / поехал / посетитель заговорил.
        val activity = inputs.activity
        if (activity != lastActivity &&
            (activity == FaceActivity.THINKING || activity == FaceActivity.LOOKING || activity == FaceActivity.TRAVELING)
        ) {
            reaction = null
        }
        if (inputs.userSpeaking && !lastUserSpeaking) {
            reaction = null
            reactionNext = null
        }
        lastActivity = activity
        lastUserSpeaking = inputs.userSpeaking
        if (reaction != null && nowMs >= reactionUntil) {
            val next = reactionNext
            reactionNext = null
            if (next != null) {
                reaction = next
                reactionUntil += reactionNextMs
            } else {
                reaction = null
            }
        }

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
            FaceCue.GREET to (FaceMood.WIDE to 450L),
            FaceCue.ARRIVED to (FaceMood.HAPPY to 2_200L),
            FaceCue.TRIP_CANCELLED to (FaceMood.ACK to 1_400L),
            FaceCue.SURPRISE to (FaceMood.SURPRISED to 1_300L),
            FaceCue.MOOD_HAPPY to (FaceMood.HAPPY to MOOD_MS),
            FaceCue.MOOD_BLUSH to (FaceMood.BLUSH to MOOD_MS),
            FaceCue.MOOD_SPARKLE to (FaceMood.SPARKLE to MOOD_MS),
            FaceCue.MOOD_WIDE to (FaceMood.WIDE to MOOD_MS),
            FaceCue.MOOD_CONFUSED to (FaceMood.CONFUSED to MOOD_MS),
            FaceCue.COSTUME_HAPPY to (FaceMood.HAPPY to COSTUME_MS),
            FaceCue.COSTUME_BLUSH to (FaceMood.BLUSH to COSTUME_MS),
            FaceCue.COSTUME_WIDE to (FaceMood.WIDE to COSTUME_MS),
            FaceCue.COSTUME_SPARKLE to (FaceMood.SPARKLE to COSTUME_MS),
            FaceCue.OFFLINE to (FaceMood.DIZZY to 2_500L),
        )

        /** Настроение после ответа и реакция на костюм (прототип `setMood`), мс. */
        const val MOOD_MS = 2_200L
        const val COSTUME_MS = 1_800L

        /** Приветствие: «слушает» после «смотрит», мс. */
        const val GREET_LISTEN_MS = 700L

        /** Настроение по эмодзи в конце ответа (прототип `MOOD_FACE`; прочие — happy). */
        fun moodFor(emoji: String): FaceCue = when (emoji) {
            "🤔" -> FaceCue.MOOD_CONFUSED
            "👋" -> FaceCue.MOOD_BLUSH
            "🎉" -> FaceCue.MOOD_SPARKLE
            "📶" -> FaceCue.MOOD_WIDE
            else -> FaceCue.MOOD_HAPPY
        }
    }
}
