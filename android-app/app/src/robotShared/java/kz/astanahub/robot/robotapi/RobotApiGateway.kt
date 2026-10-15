package kz.astanahub.robot.robotapi

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Карта и навигация через RobotAPI (docs/vendor/RobotAPI_en.md: «Map and Localization», «Navigation»).
 * Всё — ПРОВЕРИТЬ НА РОБОТЕ (спайк 2.8): формат ответов, поведение connectServer из Application.
 */
class RobotApiGateway(
    context: Context,
    /** Вызывается после handleApiConnected (RobotOS: setCallback, подключение SkillApi). */
    private val onConnected: () -> Unit = {},
) : RobotGateway {

    private val appContext = context.applicationContext
    private val reqId = AtomicInteger(1)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    init {
        connect()
    }

    private fun connect() {
        // Док: управление шасси получает только приложение на переднем плане.
        RobotApi.getInstance().connectServer(appContext, object : ApiListener {
            override fun handleApiDisabled() {
                Timber.w("RobotApi disabled")
                _connected.value = false
            }

            override fun handleApiConnected() {
                Timber.i("RobotApi connected")
                _connected.value = true
                runCatching(onConnected).onFailure { Timber.e(it, "onConnected hook") }
            }

            override fun handleApiDisconnected() {
                Timber.w("RobotApi disconnected")
                _connected.value = false
            }
        })
    }

    /** onSuspend/onRecovery из ModuleCallbackApi: система забрала/вернула управление. */
    fun setControlAvailable(available: Boolean) {
        Timber.i("RobotApi control available=%s", available)
        _connected.value = available && RobotApi.getInstance().isApiConnectedService
    }

    override suspend fun getPlaces(): List<MapPlace> {
        if (!_connected.value) return emptyList()
        val message = command { id, listener -> RobotApi.getInstance().getPlaceList(id, listener) }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(message)
            (0 until array.length()).map { i ->
                val json = array.getJSONObject(i)
                MapPlace(
                    name = json.getString("name"),
                    x = json.getDouble("x"),
                    y = json.getDouble("y"),
                    status = json.optInt("status", 0),
                )
            }
        }.onFailure { Timber.e(it, "getPlaceList parse: %s", message) }.getOrDefault(emptyList())
    }

    override suspend fun getPosition(): RobotPose? {
        if (!_connected.value) return null
        val message = command { id, listener -> RobotApi.getInstance().getPosition(id, listener) } ?: return null
        return runCatching {
            val json = JSONObject(message)
            RobotPose(
                x = json.getDouble(Definition.JSON_NAVI_POSITION_X),
                y = json.getDouble(Definition.JSON_NAVI_POSITION_Y),
                theta = json.getDouble(Definition.JSON_NAVI_POSITION_THETA),
            )
        }.onFailure { Timber.e(it, "getPosition parse: %s", message) }.getOrNull()
    }

    override suspend fun navigateTo(placeName: String, onEvent: (NavEvent) -> Unit): NavigationResult {
        if (!_connected.value) return NavigationResult.Failed(0, "RobotApi not connected")
        val id = reqId.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            // В stub-jar часть 2-аргументных колбэков помечена deprecated, а 3-аргументные не делегируют
            // в них. Переопределяем обе версии (ПРОВЕРИТЬ НА РОБОТЕ, какая реально вызывается).
            val listener = object : ActionListener() {
                fun finishResult(status: Int, response: String?) {
                    val result = when (status) {
                        Definition.RESULT_OK ->
                            if (response == "true") NavigationResult.Arrived
                            else NavigationResult.Failed(status, response)
                        Definition.ACTION_RESPONSE_STOP_SUCCESS -> NavigationResult.Cancelled
                        else -> NavigationResult.Failed(status, response)
                    }
                    if (cont.isActive) cont.resume(result)
                }

                fun finishError(errorCode: Int, errorString: String?) {
                    val result = when (errorCode) {
                        Definition.ERROR_IN_DESTINATION -> NavigationResult.AlreadyThere
                        Definition.ERROR_NOT_ESTIMATE -> NavigationResult.NotLocalized
                        Definition.ERROR_DESTINATION_NOT_EXIST -> NavigationResult.UnknownPlace
                        else -> NavigationResult.Failed(errorCode, errorString)
                    }
                    if (cont.isActive) cont.resume(result)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onResult(status: Int, response: String?) = finishResult(status, response)
                override fun onResult(status: Int, response: String?, extraData: String?) = finishResult(status, response)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(errorCode: Int, errorString: String?) = finishError(errorCode, errorString)
                override fun onError(errorCode: Int, errorString: String?, extraData: String?) =
                    finishError(errorCode, errorString)

                fun status(status: Int, data: String?) {
                    Timber.d("navigation status %d %s", status, data)
                    when (status) {
                        Definition.STATUS_NAVI_AVOID -> onEvent(NavEvent.OBSTACLE)
                        Definition.STATUS_NAVI_AVOID_END -> onEvent(NavEvent.OBSTACLE_CLEARED)
                    }
                }

                override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = status(status, data)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusUpdate(status: Int, data: String?) = status(status, data)
            }
            RobotApi.getInstance().startNavigation(
                id, placeName, COORDINATE_DEVIATION_M, AVOID_TIMEOUT_MS, listener,
            )
            cont.invokeOnCancellation { RobotApi.getInstance().stopNavigation(reqId.getAndIncrement()) }
        }
    }

    override fun stopNavigation() {
        RobotApi.getInstance().stopNavigation(reqId.getAndIncrement())
    }

    /** CommandListener → suspend; null при ошибке или таймауте. */
    private suspend fun command(call: (Int, CommandListener) -> Unit): String? =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                call(reqId.getAndIncrement(), object : CommandListener() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onResult(result: Int, message: String?) {
                        if (cont.isActive) cont.resume(message)
                    }

                    override fun onResult(result: Int, message: String?, extraData: String?) {
                        if (cont.isActive) cont.resume(message)
                    }
                })
            }
        }

    private companion object {
        /** Рекомендованные значения из доки RobotAPI. */
        const val COORDINATE_DEVIATION_M = 0.2
        const val AVOID_TIMEOUT_MS = 30_000L
        const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
