package kz.astanahub.robot.robotapi

import com.ainirobot.coreservice.client.person.PersonApi
import com.ainirobot.coreservice.client.person.PersonListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.astanahub.robot.core.sdkCall
import kz.astanahub.robot.presence.PersonGateway
import kz.astanahub.robot.presence.SeenPerson
import timber.log.Timber

/**
 * Люди перед роботом через PersonApi (docs/vendor/RobotAPI_en.md, «Vision Capability»):
 * registerPersonListener → personChanged → getAllPersons(maxDistance). Дополнительно опрос раз в [pollMillis]:
 * personChanged может не прийти, когда человек ушёл (ПРОВЕРИТЬ НА РОБОТЕ).
 * PersonApi подключается вместе с RobotApi (connectServer); до подключения вызовы падают — их глотает [sdkCall].
 * ПРОВЕРИТЬ НА РОБОТЕ: дистанции (м), id лица на 1–3 м, нагрузка опроса, конфликт с камерой (SurfaceShareApi).
 */
class PersonApiGateway(
    private val maxDistanceM: Double = MAX_DISTANCE_M,
    private val pollMillis: Long = POLL_MS,
    private val api: () -> PersonApi = { PersonApi.getInstance() },
) : PersonGateway {

    override val persons: Flow<List<SeenPerson>> = callbackFlow {
        fun snapshot(): List<SeenPerson> =
            sdkCall("PersonApi.getAllPersons") { api().getAllPersons(maxDistanceM) }
                ?.mapNotNull { p -> sdkCall("Person") { SeenPerson(p.id, p.distance) } }
                .orEmpty()

        val listener = object : PersonListener() {
            override fun personChanged() {
                trySend(snapshot())
            }
        }
        var registered = false
        launch {
            while (isActive) {
                if (!registered) {
                    registered = sdkCall("PersonApi.registerPersonListener") { api().registerPersonListener(listener) } == true
                    if (registered) Timber.i("PersonApi listener registered")
                }
                trySend(snapshot())
                delay(pollMillis)
            }
        }
        awaitClose {
            if (registered) sdkCall("PersonApi.unregisterPersonListener") { api().unregisterPersonListener(listener) }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private companion object {
        /** Чуть дальше порога «ушёл» (3 м), чтобы видеть уход. */
        const val MAX_DISTANCE_M = 3.5
        const val POLL_MS = 500L
    }
}
