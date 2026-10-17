package kz.astanahub.robot.core

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Вызов SDK робота (RobotApi / SkillApi / SurfaceShareApi / AgentCore) без падения приложения:
 * любое исключение (в т.ч. NoClassDefFoundError на прошивке без нужного сервиса) → null + лог.
 * Отмену корутины не глотаем.
 */
inline fun <T> sdkCall(what: String, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Timber.e(e, "SDK call failed: %s", what)
        null
    }
