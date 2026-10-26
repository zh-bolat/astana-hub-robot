package kz.astanahub.robot.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kz.astanahub.robot.presence.PersonGateway
import kz.astanahub.robot.presence.PresenceSimulator
import kz.astanahub.robot.presence.SeenPerson
import kz.astanahub.robot.robot.HeadDiagnostics
import kz.astanahub.robot.robot.HeadGateway
import timber.log.Timber

/**
 * Эмулятор: человек «подходит» по кнопке на экране диагностики и сам «уходит» через [stayMillis]
 * (таймер ухода; повторное «подошёл» продлевает). Каждый новый подход — новый id лица.
 */
class FakePersonGateway(
    private val stayMillis: Long = STAY_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PersonGateway, PresenceSimulator {

    private val state = MutableStateFlow<List<SeenPerson>>(emptyList())
    override val persons: Flow<List<SeenPerson>> = state

    private var nextId = 1
    private var leaveTimer: Job? = null

    @Synchronized
    override fun arrive() {
        val current = state.value.firstOrNull()
        if (current == null) state.value = listOf(SeenPerson(nextId++, ARRIVE_DISTANCE_M))
        Timber.i("FAKE person arrived (id=%d), leaves in %d s", state.value.first().id, stayMillis / 1000)
        leaveTimer?.cancel()
        leaveTimer = scope.launch {
            delay(stayMillis)
            leave()
        }
    }

    fun current(): List<SeenPerson> = state.value

    @Synchronized
    override fun leave() {
        leaveTimer?.cancel()
        leaveTimer = null
        if (state.value.isNotEmpty()) Timber.i("FAKE person left")
        state.value = emptyList()
    }

    private companion object {
        const val STAY_MS = 90_000L
        const val ARRIVE_DISTANCE_M = 1.2
    }
}

/** Голова на эмуляторе: только лог (проверить на роботе — RobotHeadGateway). */
class FakeHeadGateway : HeadGateway {
    override fun startFocusFollow(faceId: Int, onLost: () -> Unit): Boolean {
        Timber.d("FAKE head: startFocusFollow(%d)", faceId)
        return true
    }

    override fun stopFocusFollow() = Timber.d("FAKE head: stopFocusFollow")
    override fun resetHead() = Timber.d("FAKE head: resetHead")
    override fun nod(degrees: Int) = Timber.d("FAKE head: nod %d", degrees)
    override fun turnTo(degrees: Int) = Timber.d("FAKE head: turnTo %d", degrees)
    override fun tilt(degrees: Int) = Timber.d("FAKE head: tilt %d", degrees)
}

/** Диагностика головы на эмуляторе: мотора нет — ответ заглушки, люди — из [FakePersonGateway]. */
class FakeHeadDiagnostics(private val persons: FakePersonGateway) : HeadDiagnostics {
    private fun fake(what: String, report: (String) -> Unit) {
        report("$what → rc=0 (заглушка эмулятора)")
        report("$what onResult 1 {\"status\":\"ok\"} (заглушка эмулятора)")
    }

    override fun move(hMode: String, vMode: String, hAngle: Int, vAngle: Int, report: (String) -> Unit) =
        fake("moveHead($hMode, $vMode, h=$hAngle, v=$vAngle)", report)

    override fun reset(report: (String) -> Unit) = fake("resetHead", report)
    override fun status(report: (String) -> Unit) = fake("getHeadStatus", report)
    override fun follow(faceId: Int, report: (String) -> Unit) = fake("startFocusFollow(face=$faceId)", report)
    override fun stopFollow(report: (String) -> Unit) = report("stopFocusFollow → rc=0 (заглушка эмулятора)")

    override fun persons(): List<String> {
        val list = persons.current()
        return listOf("Заглушка: людей ${list.size}") + list.map { "id=${it.id}  ${it.distanceM} м" }
    }

    override fun nearestFaceId(): Int? = persons.current().minByOrNull { it.distanceM }?.id
}
