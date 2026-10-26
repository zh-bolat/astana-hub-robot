package kz.astanahub.robot.ui.face

import androidx.compose.runtime.Immutable
import kz.astanahub.robot.ui.WhereIsHint

/** Чем закончилась поездка (короткое состояние острова и реакция лица). */
enum class TripOutcome { ARRIVED, FAILED, STOPPED }

/** Что показывает «остров» внизу экрана лица (одна пилюля в духе Dynamic Island, содержимое перетекает). */
sealed interface IslandContent {

    /** «Где X»: стрелка от посетителя, место, расстояние; «Проводить», если робот может проводить. */
    @Immutable
    data class WhereIs(val hint: WhereIsHint) : IslandContent

    /** Едем: куда + «Остановить». */
    @Immutable
    data class Trip(val place: String) : IslandContent

    /** Поездка закончилась: коротко «Пришли» / «Не получилось дойти» / «Остановились», затем остров уходит. */
    @Immutable
    data class Outcome(val kind: TripOutcome, val place: String) : IslandContent
}

/**
 * Остров экрана лица (чистый Kotlin, тест): из состояния MainViewModel (`guidingTo`, `whereIs`) и реакций на итог
 * поездки решает, что показать, и сколько.
 * - Поездка — всегда остров поездки (сильнее всего).
 * - Поездка кончилась — остров ждёт итог (реакцию ViewModel: приехали / ошибка / отмена) до [waitOutcomeMs], затем
 *   «Пришли» / «Не получилось дойти» / «Остановились» [outcomeMs] — и уходит.
 * - Новый ответ «где» (новый `seq`) — остров where_is на [whereMs] (бриф: ~8–10 с); новый вопрос его не гасит —
 *   «да» на «Проводить?» плавно превращает его в остров поездки, а не гасит и не зажигает заново.
 * Время — `uptime` мс; [nextChangeAt] — когда снова позвать [tick].
 */
class IslandModel(
    private val whereMs: Long = WHERE_MS,
    private val outcomeMs: Long = OUTCOME_MS,
    private val waitOutcomeMs: Long = WAIT_OUTCOME_MS,
) {
    var content: IslandContent? = null
        private set

    private var guidingTo: String? = null
    private var tripPlace: String? = null
    private var tripEndedAt = NEVER
    private var outcome: TripOutcome? = null
    private var outcomeUntil = NEVER
    private var where: WhereIsHint? = null
    private var whereSeq = -1
    private var whereUntil = NEVER

    /** Состояние ViewModel изменилось. @return остров изменился. */
    fun update(guidingTo: String?, whereIs: WhereIsHint?, now: Long): Boolean {
        if (guidingTo != null) {
            if (this.guidingTo == null) {
                // Поездка началась: прошлый итог и «где» больше не нужны.
                outcome = null
                outcomeUntil = NEVER
                where = null
                whereUntil = NEVER
            }
            tripPlace = guidingTo
            tripEndedAt = NEVER
        } else if (this.guidingTo != null) {
            tripEndedAt = now
            // Итог мог прийти раньше конца поездки в состоянии — считаем его срок от конца поездки.
            if (outcome != null) outcomeUntil = now + outcomeMs
        }
        this.guidingTo = guidingTo
        if (whereIs != null && whereIs.seq != whereSeq) {
            whereSeq = whereIs.seq
            if (guidingTo == null) {
                where = whereIs
                whereUntil = now + whereMs
            }
        }
        return resolve(now)
    }

    /** Итог поездки (реакция ViewModel: приехали / ошибка / отмена). */
    fun onOutcome(kind: TripOutcome, now: Long): Boolean {
        if (tripPlace == null) return false
        outcome = kind
        outcomeUntil = if (guidingTo == null) now + outcomeMs else NEVER
        return resolve(now)
    }

    /** Время идёт: истёк срок «где» / итога / ожидания итога. */
    fun tick(now: Long): Boolean = resolve(now)

    /** Когда остров изменится сам (по времени); null — не изменится. */
    fun nextChangeAt(): Long? = listOf(
        outcomeUntil.takeIf { outcome != null && it != NEVER },
        (tripEndedAt + waitOutcomeMs).takeIf { tripEndedAt != NEVER && outcome == null },
        whereUntil.takeIf { where != null && it != NEVER },
    ).filterNotNull().minOrNull()

    /** Сон: остров уходит сразу (лицо спит, показывать нечего). */
    fun clear(): Boolean {
        tripPlace = null
        tripEndedAt = NEVER
        outcome = null
        outcomeUntil = NEVER
        where = null
        whereUntil = NEVER
        return set(null)
    }

    private fun resolve(now: Long): Boolean {
        val trip = guidingTo
        if (trip != null) return set(IslandContent.Trip(trip))
        val ended = tripPlace
        if (ended != null) {
            val kind = outcome
            if (kind != null && now < outcomeUntil) return set(IslandContent.Outcome(kind, ended))
            if (kind == null && tripEndedAt != NEVER && now - tripEndedAt < waitOutcomeMs) return set(IslandContent.Trip(ended))
            tripPlace = null
            tripEndedAt = NEVER
            outcome = null
            outcomeUntil = NEVER
        }
        val w = where
        if (w != null && now < whereUntil) return set(IslandContent.WhereIs(w))
        where = null
        whereUntil = NEVER
        return set(null)
    }

    private fun set(c: IslandContent?): Boolean {
        if (c == content) return false
        content = c
        return true
    }

    companion object {
        const val WHERE_MS = 9_000L
        const val OUTCOME_MS = 2_000L
        const val WAIT_OUTCOME_MS = 600L
        private const val NEVER = Long.MIN_VALUE
    }
}
