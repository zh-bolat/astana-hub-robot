package kz.astanahub.robot.core

import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Переподключение к сервису робота с нарастающей паузой [initialDelayMillis] → ×2 → [maxDelayMillis].
 * [schedule] — после handleApiDisconnected; [reset] — когда подключились сами (handleApiConnected / onRecovery).
 */
class Reconnector(
    private val scope: CoroutineScope,
    private val name: String,
    private val isConnected: () -> Boolean,
    private val connect: () -> Unit,
    private val initialDelayMillis: Long = 5_000,
    private val maxDelayMillis: Long = 60_000,
) {
    private var job: Job? = null

    /** Попытки сделаны (для тестов/диагностики). */
    @Volatile var attempts: Int = 0
        private set

    @Synchronized
    fun schedule() {
        if (job?.isActive == true) return
        job = scope.launch {
            var pause = initialDelayMillis
            while (isActive) {
                delay(pause)
                if (isConnected()) break
                attempts++
                Timber.i("%s: reconnect attempt %d (pause was %d ms)", name, attempts, pause)
                sdkCall("$name reconnect") { connect() }
                pause = min(pause * 2, maxDelayMillis)
            }
        }
    }

    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
    }

    val pending: Boolean @Synchronized get() = job?.isActive == true
}
