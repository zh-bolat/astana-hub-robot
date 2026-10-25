package kz.astanahub.robot.live

import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.places.Directions
import kz.astanahub.robot.robot.NavJournal
import kz.astanahub.robot.robot.RobotPose
import timber.log.Timber

/** Почему робот шлёт серверу набор фраз «где X» (`state.directions`). [code] — в журнале навигации. */
enum class DirectionsReason(val code: String) {
    /** Live-сессия открылась (`ready` после `hello`). */
    START("start"),

    /** Поездка закончилась (любой исход). */
    AFTER_TRIP("after_trip"),

    /** Позиция или курс заметно изменились с прошлой отправки. */
    MOVED("moved"),

    /** Робот стоит, но набор скоро устареет на сервере (сервер не верит набору старше 10 мин). */
    REFRESH("refresh"),
}

/**
 * Отправка фраз «где X» в live (docs/api-contract.md, `WS /v1/live`, кадр `state.directions`): сервер говорит
 * «Кафе — справа от вас, примерно в 15 метрах» сам, но позицию и карту знает только робот.
 *
 * - [report] — принудительно (старт сессии, конец поездки): шлём, если позиция известна.
 * - [tick] / [run] — периодически: шлём, только если робот сдвинулся ≥ [minMoveM] или повернулся ≥ [minTurnDeg]
 *   с прошлой отправки (или прошло [refreshMillis] — набор не должен устареть на сервере).
 * - Нет позиции / пустой набор / соединение закрыто — кадр не шлём; принудительная причина ждёт следующего [tick].
 * - Ошибки RobotApi и построения фраз сессию не роняют: только запись в журнал.
 *
 * Каждая отправка (и пропуск принудительной) — в [NavJournal] (экран диагностики). Проверить на роботе: позиция
 * из RobotApi `getPosition` и точки `getPlaceList` — на эмуляторе заглушки.
 */
class LiveDirectionsReporter(
    /** Текущая поза робота; null — не локализован / RobotApi недоступен. */
    private val pose: suspend () -> RobotPose?,
    /** Фразы от позы: `place_id` → язык → фраза ([kz.astanahub.robot.places.WayFinder.directionsFrom]). */
    private val build: suspend (RobotPose) -> Map<String, Map<Lang, String>>,
    /** Отправить кадр; false — сессия закрыта. */
    private val send: (Map<String, Map<Lang, String>>) -> Boolean,
    private val clock: () -> Long,
    private val log: (String) -> Unit = NavJournal::log,
    private val minMoveM: Double = MIN_MOVE_M,
    private val minTurnDeg: Double = MIN_TURN_DEG,
    private val refreshMillis: Long = REFRESH_MILLIS,
) {
    private val mutex = Mutex()

    /** Поза последней удачной отправки; null — ещё не отправляли. */
    private var lastPose: RobotPose? = null
    private var lastSentAt = 0L

    /** Принудительная отправка не удалась (нет позиции и т. п.) — повторим на [tick]. */
    private var pending: DirectionsReason? = null

    /** Сколько кадров ушло (диагностика, тесты). */
    var sentCount = 0
        private set

    /** Принудительно ([DirectionsReason.START], [DirectionsReason.AFTER_TRIP]). @return причина, если кадр ушёл. */
    suspend fun report(reason: DirectionsReason): DirectionsReason? = mutex.withLock {
        val p = readPose(reason, loud = true) ?: return@withLock fail(reason)
        sendFrom(p, reason, loud = true) ?: fail(reason)
    }

    /** Периодическая проверка. @return причина, если кадр ушёл. */
    suspend fun tick(): DirectionsReason? = mutex.withLock {
        val retry = pending
        val p = readPose(retry ?: DirectionsReason.MOVED, loud = false) ?: return@withLock null
        val last = lastPose
        val reason = when {
            retry != null -> retry
            last == null -> DirectionsReason.START
            moved(last, p) -> DirectionsReason.MOVED
            clock() - lastSentAt >= refreshMillis -> DirectionsReason.REFRESH
            else -> return@withLock null
        }
        sendFrom(p, reason, loud = false)
    }

    /** Цикл [tick] раз в [periodMillis], пока корутину не отменят (закрыли live-сессию). */
    suspend fun run(periodMillis: Long = PERIOD_MILLIS) {
        while (true) {
            delay(periodMillis)
            tick()
        }
    }

    private fun fail(reason: DirectionsReason): DirectionsReason? {
        pending = reason
        return null
    }

    private suspend fun readPose(reason: DirectionsReason, loud: Boolean): RobotPose? {
        val p = try {
            pose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "live directions: getPosition")
            if (loud) log("live directions (${reason.code}): ошибка getPosition — не шлю: ${e.javaClass.simpleName}")
            return null
        }
        if (p == null && loud) log("live directions (${reason.code}): нет позиции робота — не шлю")
        return p
    }

    private suspend fun sendFrom(p: RobotPose, reason: DirectionsReason, loud: Boolean): DirectionsReason? {
        val phrases = try {
            build(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "live directions: build")
            if (loud) log("live directions (${reason.code}): ошибка карты — не шлю: ${e.javaClass.simpleName}")
            return null
        }
        if (phrases.isEmpty()) {
            if (loud) log("live directions (${reason.code}): нет мест базы на карте — не шлю")
            return null
        }
        if (!send(phrases)) {
            if (loud) log("live directions (${reason.code}): сессия закрыта — не ушло")
            return null
        }
        lastPose = p
        lastSentAt = clock()
        pending = null
        sentCount++
        log("live directions → сервер: мест ${phrases.size}, причина=${reason.code}")
        return reason
    }

    private fun moved(a: RobotPose, b: RobotPose): Boolean =
        hypot(b.x - a.x, b.y - a.y) >= minMoveM ||
            abs(Math.toDegrees(Directions.normalizeAngle(b.theta - a.theta))) >= minTurnDeg

    companion object {
        const val PERIOD_MILLIS = 15_000L
        const val MIN_MOVE_M = 0.5
        const val MIN_TURN_DEG = 20.0

        /** Сервер не верит набору старше 10 мин — стоящий робот обновляет его раз в 5 мин. */
        const val REFRESH_MILLIS = 5 * 60_000L
    }
}
