package kz.astanahub.robot.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * «Я сейчас говорю» для встроенного распознавания: пока идёт своя озвучка и [tailMillis] после неё
 * результаты ASR — это эхо собственного голоса, их надо игнорировать.
 * Счётчик, а не флаг: вложенные/перекрывающиеся озвучки не сбрасывают признак раньше времени.
 */
class EchoGate(
    private val tailMillis: Long = DEFAULT_TAIL_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val active = AtomicInteger(0)
    @Volatile private var lastEnd: Long = Long.MIN_VALUE / 2

    fun begin() {
        active.incrementAndGet()
    }

    fun end() {
        // Не уходим в минус при лишнем end() (например, после ошибки SDK).
        if (active.updateAndGet { if (it > 0) it - 1 else 0 } == 0) lastEnd = clock()
    }

    val speaking: Boolean get() = active.get() > 0

    /** true — сейчас говорю или прошло меньше [tailMillis] после конца озвучки. */
    fun suppressed(): Boolean = active.get() > 0 || clock() - lastEnd < tailMillis

    inline fun <T> around(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    companion object {
        const val DEFAULT_TAIL_MILLIS = 300L
    }
}
