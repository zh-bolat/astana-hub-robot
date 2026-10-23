package kz.astanahub.robot.robot

import kz.astanahub.robot.ui.Emotion
import timber.log.Timber

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

    /** Короткий кивок (вниз-вверх) — «живое» движение. */
    fun nod()
}

/**
 * Кто управляет головой. Правила (задача 15):
 * - есть собеседник с id лица → focus follow; ушёл → stopFocusFollow + resetHead;
 * - навигация или снимок камерой → слежение выключено (одно устройство — одно API; камера и зрение не вместе),
 *   после — включается снова, если собеседник ещё рядом;
 * - кивок на смену эмоции — редко ([nodIntervalMillis]) и только когда голова свободна (нет слежения и навигации).
 * Методы потокобезопасны: колбэки SDK приходят из чужих потоков.
 */
class HeadController(
    private val gateway: HeadGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nodIntervalMillis: Long = NOD_INTERVAL_MS,
) {
    private var faceId: Int? = null
    private var following = false
    private var navigating = false
    private var cameraBusy = false
    private var lastNod = Long.MIN_VALUE / 2

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

    /** Смена эмоции: иногда кивнуть (радость/восторг/удивление), если голова свободна. */
    @Synchronized
    fun onEmotion(emotion: Emotion) {
        if (emotion !in NOD_EMOTIONS) return
        if (following || navigating || cameraBusy) return
        val now = clock()
        if (now - lastNod < nodIntervalMillis) return
        lastNod = now
        gateway.nod()
    }

    private fun apply() {
        val id = faceId ?: return
        if (following || navigating || cameraBusy) return
        following = gateway.startFocusFollow(id) { onLost(id) }
        Timber.i("head: focus follow id=%d started=%s", id, following)
    }

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
        val NOD_EMOTIONS = setOf(Emotion.HAPPY, Emotion.EXCITED, Emotion.SURPRISED)
    }
}
