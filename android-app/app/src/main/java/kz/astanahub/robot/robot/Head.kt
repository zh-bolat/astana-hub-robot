package kz.astanahub.robot.robot

import kz.astanahub.robot.ui.Emotion
import timber.log.Timber
import kotlin.math.roundToInt

/** Голова робота (RobotAPI: startFocusFollow / stopFocusFollow / resetHead / moveHead). На эмуляторе — лог. */
interface HeadGateway {
    /**
     * Слежение за лицом [faceId]: голова следит, корпус доворачивается. Занимает шасси (нельзя вместе с навигацией).
     * [onLost] — цель потеряна или слежение прервано системой. false — вызов не прошёл.
     */
    fun startFocusFollow(faceId: Int, onLost: () -> Unit): Boolean

    fun stopFocusFollow()

    /** Голова в исходное положение. */
    fun resetHead()

    /** Кивок: вниз на [degrees] и обратно. */
    fun nod(degrees: Int)

    /** Повернуть голову по горизонтали в [degrees] от «прямо» (абсолютно; + — влево, как угол на карте). */
    fun turnTo(degrees: Int)

    /** Лёгкий наклон: на [degrees] по вертикали и обратно. */
    fun tilt(degrees: Int)
}

/**
 * Проверка головы и зрения на роботе (экран диагностики): каждая команда с подробным отчётом — код возврата вызова
 * и всё, что пришло в `onResult` / `onError` / `onStatusUpdate` (код и строка). [report] зовётся из потока SDK.
 */
interface HeadDiagnostics {
    fun move(hMode: String, vMode: String, hAngle: Int, vAngle: Int, report: (String) -> Unit)
    fun reset(report: (String) -> Unit)
    fun status(report: (String) -> Unit)
    fun follow(faceId: Int, report: (String) -> Unit)
    fun stopFollow(report: (String) -> Unit)

    /** Кто сейчас перед роботом (PersonApi): строки с id, дистанцией, углом; пусто — никого. */
    fun persons(): List<String>

    /** id ближайшего лица (≥ 0) или null. */
    fun nearestFaceId(): Int?
}

/**
 * Кто управляет головой. Правила:
 * - есть собеседник с id лица → focus follow; ушёл → stopFocusFollow + resetHead;
 * - навигация или снимок камерой → слежение выключено (одно устройство — одно API; камера и зрение не вместе),
 *   после — включается снова, если собеседник ещё рядом;
 * - «живые» движения — только когда голова **свободна** (нет слежения, навигации, камеры и другого движения):
 *   кивок [NOD_DEGREES]° на радость/восторг (не чаще [nodIntervalMillis]) и на приветствие подошедшего (слежение
 *   стартует после кивка); на ответ «где X» — поворот головы к месту (±[LOOK_MAX_DEGREES]°) и возврат через
 *   [LOOK_HOLD_MS]; лёгкий наклон, когда робот начинает говорить (не чаще [gestureIntervalMillis]).
 * Методы потокобезопасны: колбэки SDK приходят из чужих потоков.
 */
class HeadController(
    private val gateway: HeadGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nodIntervalMillis: Long = NOD_INTERVAL_MS,
    private val gestureIntervalMillis: Long = GESTURE_INTERVAL_MS,
    /** Отложить действие на мс (главный поток); тесты подменяют. */
    private val schedule: (Long, () -> Unit) -> Unit = MainDelay::post,
) {
    private var faceId: Int? = null
    private var following = false
    private var navigating = false
    private var cameraBusy = false
    private var lastNod = Long.MIN_VALUE / 2
    private var lastGesture = Long.MIN_VALUE / 2

    /** Идёт «живое» движение (кивок, взгляд на место): до этого момента голова занята. */
    private var movingUntil = Long.MIN_VALUE / 2

    /** Номер взгляда на место: возврат головы — только для последнего (новый взгляд отменяет старый возврат). */
    private var lookSeq = 0

    @get:Synchronized
    val isFollowing: Boolean get() = following

    /** Собеседник с id лица [id] (≥ 0); id < 0 — следить не за кем. */
    @Synchronized
    fun follow(id: Int) {
        if (id < 0) return
        if (following && faceId == id) return
        if (following) stopInternal()
        faceId = id
        apply()
    }

    /** Посетитель ушёл: перестать следить и вернуть голову. */
    @Synchronized
    fun release() {
        faceId = null
        if (following) stopInternal()
        if (!navigating) gateway.resetHead()
    }

    @Synchronized
    fun setNavigating(value: Boolean) {
        if (navigating == value) return
        navigating = value
        if (value) {
            if (following) stopInternal()
        } else {
            apply()
        }
    }

    @Synchronized
    fun setCameraBusy(value: Boolean) {
        if (cameraBusy == value) return
        cameraBusy = value
        if (value) {
            if (following) stopInternal()
        } else {
            apply()
        }
    }

    /** Смена эмоции: иногда кивнуть (радость / восторг), если голова свободна. */
    @Synchronized
    fun onEmotion(emotion: Emotion) {
        if (emotion !in NOD_EMOTIONS) return
        val now = clock()
        if (!free(now)) return
        if (now - lastNod < nodIntervalMillis) return
        nod(now)
    }

    /**
     * Подошёл посетитель: кивнуть-поздороваться (если голова свободна) — зовётся до [follow], слежение начнётся после
     * кивка.
     */
    @Synchronized
    fun greet() {
        val now = clock()
        if (!free(now)) return
        nod(now)
    }

    /**
     * Ответ «где X»: повернуть голову к месту ([robotAngleDegrees] — угол от корпуса робота, + — влево), ограничить
     * ±[LOOK_MAX_DEGREES], через [LOOK_HOLD_MS] вернуть прямо — если голова к тому времени всё ещё свободна.
     */
    @Synchronized
    fun lookToward(robotAngleDegrees: Double) {
        val now = clock()
        if (!free(now)) return
        val deg = robotAngleDegrees.coerceIn(-LOOK_MAX_DEGREES.toDouble(), LOOK_MAX_DEGREES.toDouble()).roundToInt()
        if (deg == 0) return
        gateway.turnTo(deg)
        movingUntil = now + LOOK_HOLD_MS
        val seq = ++lookSeq
        schedule(LOOK_HOLD_MS) { lookBack(seq) }
    }

    @Synchronized
    private fun lookBack(seq: Int) {
        if (seq != lookSeq) return
        movingUntil = clock()
        // Слежение / поездка уже управляют головой — не мешаем им.
        if (following || navigating || cameraBusy) return
        gateway.turnTo(0)
        apply()
    }

    /** Робот начал говорить: лёгкий наклон, не чаще [gestureIntervalMillis], только свободной головой. */
    @Synchronized
    fun onSpeaking() {
        val now = clock()
        if (!free(now)) return
        if (now - lastGesture < gestureIntervalMillis) return
        lastGesture = now
        gateway.tilt(TILT_DEGREES)
        movingUntil = now + TILT_MS
    }

    private fun free(now: Long): Boolean = !following && !navigating && !cameraBusy && now >= movingUntil

    private fun nod(now: Long) {
        lastNod = now
        lastGesture = now
        gateway.nod(NOD_DEGREES)
        movingUntil = now + NOD_MS
    }

    private fun apply() {
        val id = faceId ?: return
        if (following || navigating || cameraBusy) return
        val now = clock()
        if (now < movingUntil) {
            // Кивок / взгляд ещё идут — слежение после них (иначе слежение оборвёт движение на полпути).
            schedule(movingUntil - now) { applyLater() }
            return
        }
        following = gateway.startFocusFollow(id) { onLost(id) }
        Timber.i("head: focus follow id=%d started=%s", id, following)
    }

    @Synchronized
    private fun applyLater() = apply()

    @Synchronized
    private fun onLost(id: Int) {
        if (faceId != id) return
        Timber.i("head: focus target lost id=%d", id)
        following = false
    }

    private fun stopInternal() {
        following = false
        gateway.stopFocusFollow()
    }

    companion object {
        const val NOD_INTERVAL_MS = 20_000L
        const val GESTURE_INTERVAL_MS = 9_000L

        /** Кивок заметный, но не «поклон»: 14° вниз и обратно (было 6° — на роботе не видно). */
        const val NOD_DEGREES = 14
        const val NOD_MS = 1_000L
        const val LOOK_MAX_DEGREES = 40
        const val LOOK_HOLD_MS = 3_500L
        const val TILT_DEGREES = 6
        const val TILT_MS = 1_000L
        val NOD_EMOTIONS = setOf(Emotion.HAPPY, Emotion.EXCITED)
    }
}

/** Отложенный вызов на главном потоке (Handler); в JVM-тестах не используется — им подменяют `schedule`. */
private object MainDelay {
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun post(delayMs: Long, block: () -> Unit) {
        handler.postDelayed({ block() }, delayMs)
    }
}
