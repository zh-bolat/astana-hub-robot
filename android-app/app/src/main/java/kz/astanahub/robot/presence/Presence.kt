package kz.astanahub.robot.presence

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Человек в поле зрения робота (PersonApi `Person`): [id] ≥ 0 — лицо распознано полностью (годится для focus follow). */
data class SeenPerson(val id: Int, val distanceM: Double)

/** Люди перед роботом: снимок списка при каждом изменении (robot — PersonApi, emulator — заглушка). */
interface PersonGateway {
    val persons: Flow<List<SeenPerson>>
}

/** Эмулятор: «подошёл человек» / «ушёл» с экрана диагностики. */
interface PresenceSimulator {
    fun arrive()
    fun leave()
}

sealed interface PresenceEvent {
    /** Человек стоит рядом ≥ dwell. [greet] = false — его уже приветствовали недавно (анти-спам). */
    data class Arrived(val personId: Int, val greet: Boolean) : PresenceEvent

    /** У собеседника появился id лица (для focus follow), например подошёл ближе. */
    data class FaceId(val personId: Int) : PresenceEvent

    /** Никого рядом [PresenceConfig.leaveMillis] — посетитель ушёл. */
    data object Left : PresenceEvent
}

data class PresenceConfig(
    /** Ближе — «подошёл» (задача 14: 1–3 м). */
    val wakeDistanceM: Double = 2.5,
    /** Дальше — «ушёл» (гистерезис, чтобы шаг назад не усыплял робота). */
    val stayDistanceM: Double = 3.0,
    /** Стоит рядом не меньше — не прохожий. */
    val dwellMillis: Long = 700,
    /** Никого рядом столько — ушёл. */
    val leaveMillis: Long = 15_000,
    /** Того же человека (id) повторно не приветствуем. */
    val regreetMillis: Long = 5 * 60_000L,
    /** Человек без id лица: не приветствуем чаще. */
    val anonymousGreetMillis: Long = 60_000,
)

/**
 * Пробуждение по человеку: «подошёл» = кто-то ближе [PresenceConfig.wakeDistanceM] непрерывно ≥ dwell
 * (прохожий не успевает), «ушёл» = никого ближе [PresenceConfig.stayDistanceM] в течение leave.
 * Анти-спам: id из `Person` помним [PresenceConfig.regreetMillis]; без id — не чаще anonymousGreetMillis.
 * Время событий — через `delay` (виртуальное в тестах), отметки приветствий — через [clock].
 */
class PresenceTracker(
    private val config: PresenceConfig = PresenceConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val greetedAt = mutableMapOf<Int, Long>()
    private var lastAnonymousGreeting = Long.MIN_VALUE / 2

    fun track(persons: Flow<List<SeenPerson>>): Flow<PresenceEvent> = channelFlow {
        var present = false
        var currentId = -1
        var arriveJob: Job? = null
        var leaveJob: Job? = null
        var candidateId = -1

        persons.collect { list ->
            val near = list.filter { it.distanceM > 0 && it.distanceM <= config.wakeDistanceM }
                .minByOrNull { it.distanceM }
            val stays = list.any { it.distanceM > 0 && it.distanceM <= config.stayDistanceM }
            if (!present) {
                if (near == null) {
                    arriveJob?.cancel()
                    arriveJob = null
                } else {
                    if (near.id >= 0) candidateId = near.id
                    if (arriveJob == null) {
                        if (near.id < 0) candidateId = -1
                        arriveJob = launch {
                            delay(config.dwellMillis)
                            present = true
                            currentId = candidateId
                            arriveJob = null
                            val greet = shouldGreet(currentId)
                            Timber.i("presence: arrived id=%d greet=%s", currentId, greet)
                            send(PresenceEvent.Arrived(currentId, greet))
                        }
                    }
                }
            } else {
                if (stays) {
                    leaveJob?.cancel()
                    leaveJob = null
                    val withFace = list.filter { it.id >= 0 && it.distanceM > 0 && it.distanceM <= config.stayDistanceM }
                    if (currentId < 0 || withFace.none { it.id == currentId }) {
                        val next = withFace.minByOrNull { it.distanceM }
                        if (next != null && next.id != currentId) {
                            currentId = next.id
                            greetedAt[next.id] = clock()
                            send(PresenceEvent.FaceId(next.id))
                        }
                    }
                } else if (leaveJob == null) {
                    leaveJob = launch {
                        delay(config.leaveMillis)
                        present = false
                        currentId = -1
                        leaveJob = null
                        Timber.i("presence: left")
                        send(PresenceEvent.Left)
                    }
                }
            }
        }
    }

    /** Решение «здороваться ли» + отметка, что поздоровались. */
    private fun shouldGreet(id: Int): Boolean {
        val now = clock()
        if (id >= 0) {
            val last = greetedAt[id]
            greetedAt[id] = now
            greetedAt.entries.removeAll { now - it.value > config.regreetMillis }
            greetedAt[id] = now
            return last == null || now - last > config.regreetMillis
        }
        if (now - lastAnonymousGreeting < config.anonymousGreetMillis) return false
        lastAnonymousGreeting = now
        return true
    }
}
