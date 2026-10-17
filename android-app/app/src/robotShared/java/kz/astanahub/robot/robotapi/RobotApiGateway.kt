package kz.astanahub.robot.robotapi

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.Reconnector
import kz.astanahub.robot.core.sdkCall
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
 * Любой вызов RobotApi — через [sdkCall]: исключение SDK превращается в Failed/null, приложение не падает.
 * handleApiDisconnected без последующего onRecovery/handleApiConnected → connectServer с паузой 5→60 с.
 * Всё — ПРОВЕРИТЬ НА РОБОТЕ (спайк 2.8): формат ответов, поведение connectServer из Application,
 * допустим ли повторный connectServer, пока систему «держит» другое приложение.
 */
class RobotApiGateway(
    context: Context,
    /** Вызывается после handleApiConnected (RobotOS: setCallback, подключение SkillApi). */
    private val onConnected: () -> Unit = {},
    private val api: () -> RobotApi = { RobotApi.getInstance() },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    connectOnStart: Boolean = true,
) : RobotGateway {

    private val appContext = context.applicationContext ?: context
    private val reqId = AtomicInteger(1)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** Текущая навигация: завершается, когда RobotApi сообщил итог (в т.ч. после stopNavigation). */
    @Volatile private var activeNavigation: CompletableDeferred<Unit>? = null

    private val reconnector = Reconnector(scope, "RobotApi", isConnected = { _connected.value }, connect = ::connect)

    private val apiListener = object : ApiListener {
        override fun handleApiDisabled() {
            Timber.w("RobotApi disabled")
            _connected.value = false
            reconnector.schedule()
        }

        override fun handleApiConnected() {
            Timber.i("RobotApi connected")
            _connected.value = true
            reconnector.reset()
            sdkCall("onConnected hook") { onConnected() }
        }

        override fun handleApiDisconnected() {
            Timber.w("RobotApi disconnected")
            _connected.value = false
            reconnector.schedule()
        }
    }

    init {
        if (connectOnStart) connect()
    }

    private fun connect() {
        // Док: управление шасси получает только приложение на переднем плане.
        if (sdkCall("RobotApi.connectServer") { api().connectServer(appContext, apiListener) } == null) {
            reconnector.schedule()
        }
    }

    /** onSuspend/onRecovery из ModuleCallbackApi: система забрала/вернула управление. */
    fun setControlAvailable(available: Boolean) {
        Timber.i("RobotApi control available=%s", available)
        if (available) reconnector.reset()
        _connected.value = available && (sdkCall("RobotApi.isApiConnectedService") { api().isApiConnectedService } ?: false)
    }

    /** Переподключение запланировано (для диагностики и тестов). */
    val reconnectPending: Boolean get() = reconnector.pending

    override suspend fun getPlaces(): List<MapPlace> {
        if (!_connected.value) return emptyList()
        val message = command("getPlaceList") { id, listener -> api().getPlaceList(id, listener) }
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
        }.onFailure { Timber.e(it, "getPlaceList parse (len=%d)", message.length) }.getOrDefault(emptyList())
    }

    override suspend fun getPosition(): RobotPose? {
        if (!_connected.value) return null
        val message = command("getPosition") { id, listener -> api().getPosition(id, listener) } ?: return null
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
        val done = CompletableDeferred<Unit>()
        activeNavigation = done
        return suspendCancellableCoroutine { cont ->
            fun finish(result: NavigationResult) {
                done.complete(Unit)
                if (activeNavigation === done) activeNavigation = null
                if (cont.isActive) cont.resume(result)
            }
            // В stub-jar часть 2-аргументных колбэков помечена deprecated, а 3-аргументные не делегируют
            // в них. Переопределяем обе версии (ПРОВЕРИТЬ НА РОБОТЕ, какая реально вызывается).
            val listener = object : ActionListener() {
                fun finishResult(status: Int, response: String?) = finish(
                    when (status) {
                        Definition.RESULT_OK ->
                            if (response == "true") NavigationResult.Arrived
                            else NavigationResult.Failed(status, response)
                        Definition.ACTION_RESPONSE_STOP_SUCCESS -> NavigationResult.Cancelled
                        else -> NavigationResult.Failed(status, response)
                    },
                )

                fun finishError(errorCode: Int, errorString: String?) = finish(
                    when (errorCode) {
                        Definition.ERROR_IN_DESTINATION -> NavigationResult.AlreadyThere
                        Definition.ERROR_NOT_ESTIMATE -> NavigationResult.NotLocalized
                        Definition.ERROR_DESTINATION_NOT_EXIST -> NavigationResult.UnknownPlace
                        else -> NavigationResult.Failed(errorCode, errorString)
                    },
                )

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onResult(status: Int, response: String?) = finishResult(status, response)
                override fun onResult(status: Int, response: String?, extraData: String?) = finishResult(status, response)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(errorCode: Int, errorString: String?) = finishError(errorCode, errorString)
                override fun onError(errorCode: Int, errorString: String?, extraData: String?) =
                    finishError(errorCode, errorString)

                fun status(status: Int, data: String?) {
                    Timber.d("navigation status %d", status)
                    when (status) {
                        Definition.STATUS_NAVI_AVOID -> sdkCall("nav onEvent") { onEvent(NavEvent.OBSTACLE) }
                        Definition.STATUS_NAVI_AVOID_END -> sdkCall("nav onEvent") { onEvent(NavEvent.OBSTACLE_CLEARED) }
                    }
                }

                override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = status(status, data)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusUpdate(status: Int, data: String?) = status(status, data)
            }
            cont.invokeOnCancellation { stopNavigation() }
            val rc = sdkCall("RobotApi.startNavigation") {
                api().startNavigation(id, placeName, COORDINATE_DEVIATION_M, AVOID_TIMEOUT_MS, listener)
            }
            if (rc == null) finish(NavigationResult.Failed(-1, "startNavigation threw"))
            else Timber.d("startNavigation rc=%d", rc)
        }
    }

    override fun stopNavigation() {
        sdkCall("RobotApi.stopNavigation") { api().stopNavigation(reqId.getAndIncrement()) }
    }

    override suspend fun stopNavigationAndWait(timeoutMillis: Long): Boolean {
        val done = activeNavigation
        if (done == null || done.isCompleted) return true
        stopNavigation()
        val confirmed = withTimeoutOrNull(timeoutMillis) { done.await(); true } ?: false
        if (!confirmed) Timber.w("stopNavigation not confirmed in %d ms", timeoutMillis)
        return confirmed
    }

    /** CommandListener → suspend; null при ошибке SDK или таймауте. */
    private suspend fun command(what: String, call: (Int, CommandListener) -> Unit): String? =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : CommandListener() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onResult(result: Int, message: String?) {
                        if (cont.isActive) cont.resume(message)
                    }

                    override fun onResult(result: Int, message: String?, extraData: String?) {
                        if (cont.isActive) cont.resume(message)
                    }
                }
                if (sdkCall("RobotApi.$what") { call(reqId.getAndIncrement(), listener) } == null && cont.isActive) {
                    cont.resume(null)
                }
            }
        }

    private companion object {
        /** Рекомендованные значения из доки RobotAPI. */
        const val COORDINATE_DEVIATION_M = 0.2
        const val AVOID_TIMEOUT_MS = 30_000L
        const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
