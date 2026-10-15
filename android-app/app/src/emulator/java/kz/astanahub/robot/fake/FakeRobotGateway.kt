package kz.astanahub.robot.fake

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose

/** Карта-заглушка: несколько точек с координатами (метры), робот стоит в (0, 0) лицом по оси X. */
class FakeRobotGateway : RobotGateway {

    override val connected: StateFlow<Boolean> = MutableStateFlow(true)

    private val places = listOf(
        MapPlace("Ресепшен", 3.0, 0.0),
        MapPlace("Кафе", 12.0, -8.0),
        MapPlace("Коворкинг", -5.0, 15.0),
        MapPlace("Конференц-зал", 20.0, 6.0),
        MapPlace("Туалет", -4.0, -6.0),
    )

    @Volatile private var cancel: CompletableDeferred<Unit>? = null

    override suspend fun getPlaces(): List<MapPlace> = places

    override suspend fun getPosition(): RobotPose = RobotPose(0.0, 0.0, 0.0)

    override suspend fun navigateTo(placeName: String, onEvent: (NavEvent) -> Unit): NavigationResult {
        if (places.none { it.name == placeName }) return NavigationResult.UnknownPlace
        val stop = CompletableDeferred<Unit>().also { cancel = it }
        try {
            // Имитация: на полпути «препятствие».
            if (withTimeoutOrNull(FAKE_TRIP_MILLIS / 2) { stop.await() } != null) return NavigationResult.Cancelled
            onEvent(NavEvent.OBSTACLE)
            delay(500)
            onEvent(NavEvent.OBSTACLE_CLEARED)
            if (withTimeoutOrNull(FAKE_TRIP_MILLIS / 2) { stop.await() } != null) return NavigationResult.Cancelled
            return NavigationResult.Arrived
        } finally {
            cancel = null
        }
    }

    override fun stopNavigation() {
        cancel?.complete(Unit)
    }

    private companion object {
        const val FAKE_TRIP_MILLIS = 6_000L
    }
}
