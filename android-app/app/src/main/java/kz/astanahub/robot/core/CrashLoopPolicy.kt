package kz.astanahub.robot.core

/**
 * Что делать после падения: перезапуск, пока падений не больше [maxRestarts] за [windowMillis];
 * дальше — экран «техническая пауза» (без перезапусков по кругу).
 */
object CrashLoopPolicy {
    const val MAX_RESTARTS = 3
    const val WINDOW_MILLIS = 5 * 60_000L

    enum class Decision { RESTART, PAUSE }

    /** [history] — моменты прошлых падений; возвращает решение и обновлённую историю (с текущим). */
    fun onCrash(
        history: List<Long>,
        now: Long,
        maxRestarts: Int = MAX_RESTARTS,
        windowMillis: Long = WINDOW_MILLIS,
    ): Pair<Decision, List<Long>> {
        val recent = history.filter { now - it in 0 until windowMillis } + now
        val decision = if (recent.size <= maxRestarts) Decision.RESTART else Decision.PAUSE
        return decision to recent
    }
}
