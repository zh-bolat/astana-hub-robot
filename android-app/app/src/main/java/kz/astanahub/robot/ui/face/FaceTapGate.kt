package kz.astanahub.robot.ui.face

/**
 * Тапы по экрану лица (MF4, шумный зал; docs/api-contract.md «Шумный зал», v1.4). Чистый Kotlin, без Android —
 * покрыт тестом.
 * - Спит — одиночный тап будит сразу, второго не ждём; следующие [wakeGraceMs] тапы не считаются, чтобы двойной тап
 *   не усыпил робота тут же.
 * - Не спит — двойной тап (между тапами ≤ [doubleTapMs]) — уснуть; одиночный — ничего (лицо только моргнёт).
 *   Тап больше не перебивает робота: остановить ответ — «Стоп» на странице приложения.
 */
class FaceTapGate(
    private val doubleTapMs: Long = DOUBLE_TAP_MS,
    private val wakeGraceMs: Long = WAKE_GRACE_MS,
) {
    enum class Action {
        /** Спал — проснуться и поздороваться. */
        WAKE,

        /** Второй тап двойного — уснуть. */
        SLEEP,

        /** Одиночный тап бодрствующего (или тап сразу после пробуждения) — без действия. */
        NONE,
    }

    private var lastTapAt = NO_TAP
    private var ignoreUntil = NO_TAP

    fun onTap(nowMs: Long, asleep: Boolean): Action {
        if (asleep) {
            lastTapAt = NO_TAP
            ignoreUntil = nowMs + wakeGraceMs
            return Action.WAKE
        }
        if (nowMs < ignoreUntil) {
            lastTapAt = NO_TAP
            return Action.NONE
        }
        if (nowMs - lastTapAt <= doubleTapMs) {
            lastTapAt = NO_TAP
            return Action.SLEEP
        }
        lastTapAt = nowMs
        return Action.NONE
    }

    companion object {
        const val DOUBLE_TAP_MS = 350L
        const val WAKE_GRACE_MS = 600L
        private const val NO_TAP = Long.MIN_VALUE / 4
    }
}
