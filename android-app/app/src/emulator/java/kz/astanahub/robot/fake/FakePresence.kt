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
    override fun nod() = Timber.d("FAKE head: nod")
}
