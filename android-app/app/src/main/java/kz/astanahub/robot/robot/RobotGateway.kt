package kz.astanahub.robot.robot

import kotlinx.coroutines.flow.StateFlow

/** Точка карты робота (как её возвращает RobotAPI getPlaceList). */
data class MapPlace(
    val name: String,
    val x: Double,
    val y: Double,
    /** 0 — достижима, 1 — запретная зона, 2 — вне карты (RobotAPI). */
    val status: Int = 0,
) {
    val isReachable: Boolean get() = status == 0
}

data class RobotPose(val x: Double, val y: Double, val theta: Double)

sealed interface NavigationResult {
    data object Arrived : NavigationResult
    data object AlreadyThere : NavigationResult
    data object Cancelled : NavigationResult
    data object NotLocalized : NavigationResult
    data object UnknownPlace : NavigationResult
    data class Failed(val code: Int, val message: String?) : NavigationResult
}

/** События в пути (для фраз «позвольте пройти»). */
enum class NavEvent { OBSTACLE, OBSTACLE_CLEARED }

/** Карта, положение и навигация робота. Работают без сети. */
interface RobotGateway {
    /** Подключён ли RobotAPI (на эмуляторе — всегда true). */
    val connected: StateFlow<Boolean>

    suspend fun getPlaces(): List<MapPlace>

    /** Текущее положение или null, если робот не локализован / API недоступен. */
    suspend fun getPosition(): RobotPose?

    /** Ведёт робота к точке карты [placeName] и ждёт завершения; отмена корутины останавливает навигацию. */
    suspend fun navigateTo(placeName: String, onEvent: (NavEvent) -> Unit = {}): NavigationResult

    fun stopNavigation()

    /**
     * Остановить навигацию и дождаться подтверждения (итог startNavigation) не дольше [timeoutMillis] —
     * перед новым startNavigation. true — подтверждено или ехать было некуда; false — таймаут.
     */
    suspend fun stopNavigationAndWait(timeoutMillis: Long = STOP_CONFIRM_TIMEOUT_MS): Boolean {
        stopNavigation()
        return true
    }

    companion object {
        const val STOP_CONFIRM_TIMEOUT_MS = 3_000L
    }
}
