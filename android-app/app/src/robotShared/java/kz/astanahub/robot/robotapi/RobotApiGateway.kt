package kz.astanahub.robot.robotapi

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.Reconnector
import kz.astanahub.robot.core.sdkCall
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavJournal
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
 *
 * Навигация (сверено с докой и robotservice.jar через javap):
 * `startNavigation(int reqId, String destName, double coordinateDeviation, long time, ActionListener)` —
 * вариант 1 доки («Default Navigation Speed»: 0.7 м/с, 1.2 рад/с), coordinateDeviation 0.2 м и time 30 с — значения,
 * рекомендованные докой. Итог: onResult(RESULT_OK, "true") — приехали, onResult(ACTION_RESPONSE_STOP_SUCCESS) —
 * остановлено, onError(-113/-116/-108/…) — уже на месте / не локализован / нет точки / прочее.
 * Надёжность: общий таймаут поездки [tripTimeoutMillis] (stopNavigation + Failed); обрыв RobotApi
 * (handleApiDisconnected / handleApiDisabled / onSuspend) завершает активную поездку Failed, а не держит её вечно;
 * отказ «шасси занято» (-1 / -6: например, слежение головой ещё не отпустило шасси) — один повтор через 1 с;
 * статусы «путь не построен» (1025) и «вне карты» (1020) — stopNavigation (как советует дока) и Failed.
 * Каждый шаг — в [NavJournal] (экран диагностики).
 * ПРОВЕРИТЬ НА РОБОТЕ: какой из колбэков (2- или 3-аргументный) реально вызывается, что значит rc < 0 у
 * startNavigation, поведение connectServer из Application.
 */
class RobotApiGateway(
    context: Context,
    /** Вызывается после handleApiConnected (RobotOS: setCallback, подключение SkillApi). */
    private val onConnected: () -> Unit = {},
    private val api: () -> RobotApi = { RobotApi.getInstance() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    connectOnStart: Boolean = true,
    private val tripTimeoutMillis: Long = RobotGateway.TRIP_TIMEOUT_MS,
    private val journal: (String) -> Unit = NavJournal::log,
) : RobotGateway {

    private val appContext = context.applicationContext ?: context
    private val reqId = AtomicInteger(1)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /**
     * Одна попытка startNavigation. [result] завершает RobotApi (onResult/onError) или само приложение
     * (таймаут, обрыв связи); [stopNavigationAndWait] ждёт именно его — подтверждения от робота.
     */
    private class Attempt(val reqId: Int, val place: String) {
        val result = CompletableDeferred<NavigationResult>()
        /** Были статусы от навигации — робот принял задачу (такую попытку не повторяем). */
        @Volatile var progressed = false
        /** Путь не построен / вне карты: остановили сами, итог — эта ошибка, а не Cancelled. */
        @Volatile var pendingFailure: NavigationResult.Failed? = null

        fun complete(r: NavigationResult): Boolean = result.complete(r)
    }

    /** Текущая попытка навигации; null — не едем. */
    @Volatile private var active: Attempt? = null

    private val reconnector = Reconnector(scope, "RobotApi", isConnected = { _connected.value }, connect = ::connect)

    private val apiListener = object : ApiListener {
        override fun handleApiDisabled() {
            Timber.w("RobotApi disabled")
            journal("RobotApi: handleApiDisabled")
            _connected.value = false
            failActive(RobotGateway.CODE_API_DISCONNECTED, "RobotApi disabled")
            reconnector.schedule()
        }

        override fun handleApiConnected() {
            Timber.i("RobotApi connected")
            journal("RobotApi: подключён (handleApiConnected)")
            _connected.value = true
            reconnector.reset()
            sdkCall("onConnected hook") { onConnected() }
        }

        override fun handleApiDisconnected() {
            Timber.w("RobotApi disconnected")
            journal("RobotApi: handleApiDisconnected")
            _connected.value = false
            failActive(RobotGateway.CODE_API_DISCONNECTED, "RobotApi disconnected")
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
        journal(if (available) "RobotApi: управление возвращено (onRecovery)" else "RobotApi: управление забрала система (onSuspend)")
        if (available) {
            reconnector.reset()
        } else {
            // Док: после onSuspend все вызовы API недействительны — итога поездки не будет.
            failActive(RobotGateway.CODE_CONTROL_SUSPENDED, "control suspended by the system")
        }
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
        journal("навигация «$placeName»: запрос")
        if (!_connected.value) {
            journal("навигация «$placeName»: RobotApi не подключён — не едем")
            return NavigationResult.Failed(RobotGateway.CODE_NOT_CONNECTED, "RobotApi not connected")
        }
        logLocalization(placeName)
        val outcome = withTimeoutOrNull(tripTimeoutMillis) { runAttempts(placeName, onEvent) }
        if (outcome == null) {
            // Итога нет за общий таймаут (колбэк потерялся, робот застрял): остановка уже отправлена при отмене.
            val failed = NavigationResult.Failed(RobotGateway.CODE_TRIP_TIMEOUT, "no result in ${tripTimeoutMillis / 1000} s")
            active?.takeIf { it.place == placeName }?.complete(failed)
            journal("навигация «$placeName»: нет итога за ${tripTimeoutMillis / 1000} с — Failed ${RobotGateway.CODE_TRIP_TIMEOUT}")
            return failed
        }
        journal("навигация «$placeName»: итог ${describe(outcome)}")
        return outcome
    }

    /** Попытки startNavigation: «шасси занято» (-1 / -6) до старта движения — один повтор. */
    private suspend fun runAttempts(placeName: String, onEvent: (NavEvent) -> Unit): NavigationResult {
        var retries = BUSY_RETRIES
        while (true) {
            val attempt = startAttempt(placeName, onEvent)
            val r = try {
                attempt.result.await()
            } catch (e: CancellationException) {
                // «Стоп», новая поездка или общий таймаут: просим робота остановиться; подтверждение (итог
                // попытки) ждёт stopNavigationAndWait.
                if (!attempt.result.isCompleted) {
                    journal("навигация «$placeName»: отмена → stopNavigation")
                    stopNavigation()
                }
                throw e
            }
            if (r is NavigationResult.Failed && r.code in BUSY_CODES && retries > 0 && !attempt.progressed) {
                retries--
                journal("навигация «$placeName»: код ${r.code} ${codeName(r.code)} — повтор через $BUSY_RETRY_DELAY_MS мс")
                if (r.code == ACTION_RESPONSE_ALREADY_RUN) stopNavigation()
                delay(BUSY_RETRY_DELAY_MS)
                continue
            }
            return r
        }
    }

    private fun startAttempt(placeName: String, onEvent: (NavEvent) -> Unit): Attempt {
        val id = reqId.getAndIncrement()
        val attempt = Attempt(id, placeName)
        attempt.result.invokeOnCompletion { if (active === attempt) active = null }
        active = attempt
        // В stub-jar часть 2-аргументных колбэков помечена deprecated, а 3-аргументные не делегируют
        // в них. Переопределяем обе версии (ПРОВЕРИТЬ НА РОБОТЕ, какая реально вызывается — видно в журнале).
        val listener = object : ActionListener() {
            fun result(status: Int, response: String?) {
                journal("reqId=$id onResult status=$status ${codeName(status)} response=${response.short()}")
                attempt.complete(
                    when (status) {
                        Definition.RESULT_OK ->
                            if (response == "true") NavigationResult.Arrived
                            else NavigationResult.Failed(status, "result ok, response=$response")
                        Definition.ACTION_RESPONSE_STOP_SUCCESS -> attempt.pendingFailure ?: NavigationResult.Cancelled
                        else -> NavigationResult.Failed(status, response)
                    },
                )
            }

            fun error(errorCode: Int, errorString: String?) {
                journal("reqId=$id onError code=$errorCode ${codeName(errorCode)} ${errorString.short()}")
                attempt.complete(
                    when (errorCode) {
                        Definition.ERROR_IN_DESTINATION -> NavigationResult.AlreadyThere
                        Definition.ERROR_NOT_ESTIMATE -> NavigationResult.NotLocalized
                        Definition.ERROR_DESTINATION_NOT_EXIST -> NavigationResult.UnknownPlace
                        else -> NavigationResult.Failed(errorCode, errorString)
                    },
                )
            }

            fun status(status: Int, data: String?) {
                attempt.progressed = true
                journal("reqId=$id статус $status ${codeName(status)}${data?.takeIf { it.isNotBlank() }?.let { " data=${it.short()}" }.orEmpty()}")
                when (status) {
                    Definition.STATUS_NAVI_AVOID -> sdkCall("nav onEvent") { onEvent(NavEvent.OBSTACLE) }
                    Definition.STATUS_NAVI_AVOID_END -> sdkCall("nav onEvent") { onEvent(NavEvent.OBSTACLE_CLEARED) }
                    STATUS_NAVI_GLOBAL_PATH_FAILED, STATUS_NAVI_OUT_MAP -> failByStatus(attempt, status)
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onResult(status: Int, response: String?) = result(status, response)
            override fun onResult(status: Int, response: String?, extraData: String?) = result(status, response)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(errorCode: Int, errorString: String?) = error(errorCode, errorString)
            override fun onError(errorCode: Int, errorString: String?, extraData: String?) = error(errorCode, errorString)

            override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = status(status, data)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusUpdate(status: Int, data: String?) = status(status, data)
        }
        val rc = sdkCall("RobotApi.startNavigation") {
            api().startNavigation(id, placeName, COORDINATE_DEVIATION_M, AVOID_TIMEOUT_MS, listener)
        }
        when {
            rc == null -> {
                journal("reqId=$id startNavigation «$placeName»: исключение SDK")
                attempt.complete(NavigationResult.Failed(RobotGateway.CODE_SDK_EXCEPTION, "startNavigation threw"))
            }
            rc < 0 -> {
                // Отрицательный код — отказ (ACTION_RESPONSE_*); колбэка может не быть вовсе — не ждём его.
                journal("reqId=$id startNavigation «$placeName»: rc=$rc ${codeName(rc)} — отказ")
                if (!attempt.result.isCompleted) attempt.complete(NavigationResult.Failed(rc, "startNavigation rc=$rc ${codeName(rc)}"))
            }
            else -> journal(
                "reqId=$id startNavigation(«$placeName», deviation=$COORDINATE_DEVIATION_M м, avoidTimeout=${AVOID_TIMEOUT_MS / 1000} с) rc=$rc",
            )
        }
        Timber.d("startNavigation rc=%s", rc)
        return attempt
    }

    /** Дока: при 1025 (путь не построен) / 1020 (вне карты) «recommended to call stopNavigation». Итог — Failed. */
    private fun failByStatus(attempt: Attempt, status: Int) {
        if (attempt.pendingFailure != null || attempt.result.isCompleted) return
        val failure = NavigationResult.Failed(status, codeName(status))
        attempt.pendingFailure = failure
        journal("reqId=${attempt.reqId}: ${codeName(status)} — stopNavigation, итог Failed $status")
        stopNavigation()
        // Подтверждение остановки не пришло — не ждём вечно.
        scope.launch {
            delay(RobotGateway.STOP_CONFIRM_TIMEOUT_MS)
            if (attempt.complete(failure)) journal("reqId=${attempt.reqId}: остановка не подтверждена — Failed $status")
        }
    }

    /** Связь с RobotApi / управление потеряны: итога поездки не будет — завершаем её сами. */
    private fun failActive(code: Int, why: String) {
        val attempt = active ?: return
        if (attempt.complete(NavigationResult.Failed(code, why))) {
            journal("reqId=${attempt.reqId} «${attempt.place}»: $why — поездка прервана (Failed $code)")
        }
    }

    /** Перед поездкой — в журнал, локализован ли робот (дока: без локализации навигация не начнётся). */
    private fun logLocalization(placeName: String) {
        scope.launch {
            val message = command("isRobotEstimate") { id, listener -> api().isRobotEstimate(id, listener) }
            journal("навигация «$placeName»: isRobotEstimate=${message ?: "нет ответа"}")
        }
    }

    override fun stopNavigation() {
        val id = reqId.getAndIncrement()
        val rc = sdkCall("RobotApi.stopNavigation") { api().stopNavigation(id) }
        journal("stopNavigation(reqId=$id) rc=${rc ?: "исключение SDK"}")
    }

    override suspend fun stopNavigationAndWait(timeoutMillis: Long): Boolean {
        val attempt = active
        if (attempt == null || attempt.result.isCompleted) return true
        stopNavigation()
        val confirmed = withTimeoutOrNull(timeoutMillis) { attempt.result.await(); true } ?: false
        if (!confirmed) {
            Timber.w("stopNavigation not confirmed in %d ms", timeoutMillis)
            journal("stopNavigation: нет подтверждения за $timeoutMillis мс")
        }
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

    companion object {
        /** Значения, рекомендованные докой RobotAPI (startNavigation): радиус прибытия 0.2 м, таймаут объезда 30 с. */
        const val COORDINATE_DEVIATION_M = 0.2
        const val AVOID_TIMEOUT_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L

        // Коды из доки (Navigation Status / Error Code Definition) и robotservice.jar — числами, чтобы общий код
        // компилировался и с core-jar AgentOS, где части констант может не быть.
        private const val ACTION_RESPONSE_ALREADY_RUN = -1
        private const val ACTION_RESPONSE_REQUEST_RES_ERROR = -6
        private const val STATUS_NAVI_OUT_MAP = 1020
        private const val STATUS_NAVI_GLOBAL_PATH_FAILED = 1025

        /** «Интерфейс уже вызван» / «шасси занято другим вызовом» — повторяем один раз. */
        private val BUSY_CODES = setOf(ACTION_RESPONSE_ALREADY_RUN, ACTION_RESPONSE_REQUEST_RES_ERROR)
        private const val BUSY_RETRIES = 1
        private const val BUSY_RETRY_DELAY_MS = 1_000L

        private val CODE_NAMES = mapOf(
            0 to "ACTION_RESPONSE_SUCCESS",
            1 to "RESULT_OK",
            2 to "RESULT_FAILURE",
            3 to "ACTION_RESPONSE_STOP_SUCCESS",
            -1 to "ACTION_RESPONSE_ALREADY_RUN",
            -2 to "ACTION_RESPONSE_NOT_SUPPORT_ACTION",
            -3 to "ACTION_RESPONSE_START_ERROR",
            -4 to "ACTION_RESPONSE_STOP_RES_ERROR",
            -5 to "ACTION_RESPONSE_PARAMETER_ERROR",
            -6 to "ACTION_RESPONSE_REQUEST_RES_ERROR (шасси занято)",
            -7 to "ACTION_RESPONSE_SEND_STOP_CMD_ERROR",
            -8 to "ACTION_RESPONSE_PERMISSION_DENIED",
            -9 to "ACTION_RESPONSE_RES_UNAVAILBALE",
            -104 to "ERROR_NAVIGATION",
            -108 to "ERROR_DESTINATION_NOT_EXIST (нет точки)",
            -109 to "ERROR_DESTINATION_CAN_NOT_ARRAIVE (объезд не удался)",
            -113 to "ERROR_IN_DESTINATION (уже на месте)",
            -116 to "ERROR_NOT_ESTIMATE (не локализован)",
            -120 to "ERROR_NAVIGATION_FAILED",
            -125 to "ERROR_MULTI_ROBOT_WAITING_TIMEOUT",
            -136 to "ERROR_NAVIGATION_AVOID_TIMEOUT",
            -137 to "ERROR_NAVIGATION_RESET_HEAD",
            1014 to "STATUS_START_NAVIGATION",
            1018 to "STATUS_NAVI_AVOID (препятствие)",
            1019 to "STATUS_NAVI_AVOID_END",
            1020 to "STATUS_NAVI_OUT_MAP (вне карты)",
            1023 to "STATUS_NAVI_OBSTACLES_AVOID",
            1025 to "STATUS_NAVI_GLOBAL_PATH_FAILED (путь не построен)",
            1026 to "STATUS_NAVI_OBSTACLES_DISAPPEAR",
            1029 to "STATUS_NAVI_GLOBAL_PATH_SUCCESS",
            1034 to "STATUS_NAVI_MULTI_ROBOT_WAITING",
            1035 to "STATUS_NAVI_MULTI_ROBOT_WAITING_END",
            1036 to "STATUS_NAVI_GO_STRAIGHT",
            1037 to "STATUS_NAVI_TURN_LEFT",
            1038 to "STATUS_NAVI_TURN_RIGHT",
            1039 to "STATUS_NAVI_SET_PRIORITY_FAILED",
            1044 to "STATUS_NAVI_WHEEL_SLIP",
            1048 to "STATUS_NAVI_REPLACE_DESTINATION",
            1049 to "STATUS_NAVI_NEAR_DESTINATION",
            1051 to "STATUS_NAVI_AVOID_IMMEDIATELY",
            1052 to "STATUS_NAVI_ARRIVED_REPLACE_DESTINATION",
            RobotGateway.CODE_NOT_CONNECTED to "app: RobotApi не подключён",
            RobotGateway.CODE_SDK_EXCEPTION to "app: исключение SDK",
            RobotGateway.CODE_TRIP_TIMEOUT to "app: таймаут поездки",
            RobotGateway.CODE_API_DISCONNECTED to "app: RobotApi отключился",
            RobotGateway.CODE_CONTROL_SUSPENDED to "app: управление забрала система",
        )

        /** Имя кода RobotApi для журнала (неизвестный — пусто). */
        fun codeName(code: Int): String = CODE_NAMES[code] ?: ""

        fun describe(r: NavigationResult): String = when (r) {
            NavigationResult.Arrived -> "Arrived"
            NavigationResult.AlreadyThere -> "AlreadyThere"
            NavigationResult.Cancelled -> "Cancelled"
            NavigationResult.NotLocalized -> "NotLocalized"
            NavigationResult.UnknownPlace -> "UnknownPlace"
            is NavigationResult.Failed -> "Failed ${r.code} ${codeName(r.code)} ${r.message.orEmpty()}".trim()
        }

        private fun String?.short(): String = this?.let { if (it.length > 120) it.take(120) + "…" else it } ?: "null"
    }
}
