package kz.astanahub.robot.core

import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * Счётчики фильтра шума (с запуска приложения): реплики, не ушедшие на сервер, и короткие звуки,
 * на которые не открывали live STT. В лог — только причина и число, без текстов.
 */
object NoiseStats {
    private val filtered = AtomicInteger()
    private val shortSounds = AtomicInteger()
    private val sleepIgnored = AtomicInteger()

    val filteredUtterances: Int get() = filtered.get()
    val cancelledShortSounds: Int get() = shortSounds.get()
    val ignoredWhileAsleep: Int get() = sleepIgnored.get()

    /** Реплика-шум (или посторонняя реплика, пока робот занят) не отправлена. */
    fun utteranceFiltered(reason: String) {
        val n = filtered.incrementAndGet()
        Timber.i("noise: utterance filtered reason=%s total=%d", reason, n)
    }

    /** Робот спит и рядом никого: голос не будит. */
    fun ignoredAsleep() {
        val n = sleepIgnored.incrementAndGet()
        Timber.i("noise: utterance ignored while asleep total=%d", n)
    }

    /** Звук оборвался раньше минимальной длительности речи: live STT не открывали. */
    fun shortSoundCancelled(loudMillis: Int) {
        val n = shortSounds.incrementAndGet()
        Timber.d("noise: short sound %d ms cancelled total=%d", loudMillis, n)
        if (n % LOG_EVERY == 0) Timber.i("noise: short sounds cancelled total=%d", n)
    }

    fun summary(): String =
        "отфильтровано реплик: ${filtered.get()}, коротких звуков без STT: ${shortSounds.get()}, " +
            "голос во сне: ${sleepIgnored.get()}"

    /** Для тестов. */
    fun reset() {
        filtered.set(0)
        shortSounds.set(0)
        sleepIgnored.set(0)
    }

    private const val LOG_EVERY = 10
}
