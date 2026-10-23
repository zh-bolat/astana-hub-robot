package kz.astanahub.robot.robotapi

import android.os.Handler
import android.os.Looper
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import java.util.concurrent.atomic.AtomicInteger
import kz.astanahub.robot.core.sdkCall
import kz.astanahub.robot.robot.HeadGateway
import timber.log.Timber

/**
 * Голова через RobotApi (docs/vendor/RobotAPI_en.md: «Focus Following», «Head Gimbal Motion»).
 * Сигнатуры сверены с robotservice.jar (RobotOS) и core-12.2.1.jar (AgentOS) через javap:
 * startFocusFollow(int reqId, int faceId, long lostTimeout, float maxDistance, ActionListener),
 * stopFocusFollow(int), resetHead(int, CommandListener), moveHead(int, String, String, int, int, CommandListener).
 * Все вызовы — через [sdkCall]. ПРОВЕРИТЬ НА РОБОТЕ: что Nova поворачивает корпус при слежении, допустимый
 * диапазон относительного moveHead по вертикали, не мешает ли кивок слежению/навигации.
 */
class RobotHeadGateway(
    private val api: () -> RobotApi = { RobotApi.getInstance() },
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : HeadGateway {

    private val reqId = AtomicInteger(10_000)

    override fun startFocusFollow(faceId: Int, onLost: () -> Unit): Boolean {
        val listener = object : ActionListener() {
            fun status(status: Int) {
                when (status) {
                    Definition.STATUS_TRACK_TARGET_SUCCEED -> Timber.d("focus follow: tracking")
                    Definition.STATUS_GUEST_LOST, Definition.STATUS_GUEST_FARAWAY -> {
                        Timber.i("focus follow: lost (%d)", status)
                        sdkCall("focus onLost") { onLost() }
                    }
                }
            }

            fun error(code: Int, message: String?) {
                Timber.w("focus follow error %d %s", code, message)
                sdkCall("focus onLost") { onLost() }
            }

            override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = status(status)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusUpdate(status: Int, data: String?) = status(status)

            override fun onError(errorCode: Int, errorString: String?, extraData: String?) = error(errorCode, errorString)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(errorCode: Int, errorString: String?) = error(errorCode, errorString)

            fun result(status: Int) {
                Timber.i("focus follow finished: %d", status)
                if (status != Definition.ACTION_RESPONSE_STOP_SUCCESS) sdkCall("focus onLost") { onLost() }
            }

            override fun onResult(status: Int, responseString: String?, extraData: String?) = result(status)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onResult(status: Int, responseString: String?) = result(status)
        }
        val rc = sdkCall("RobotApi.startFocusFollow") {
            api().startFocusFollow(reqId.getAndIncrement(), faceId, LOST_TIMEOUT_MS, MAX_DISTANCE_M, listener)
        }
        Timber.d("startFocusFollow rc=%s", rc)
        return rc != null
    }

    override fun stopFocusFollow() {
        sdkCall("RobotApi.stopFocusFollow") { api().stopFocusFollow(reqId.getAndIncrement()) }
    }

    override fun resetHead() {
        sdkCall("RobotApi.resetHead") { api().resetHead(reqId.getAndIncrement(), LoggingListener("resetHead")) }
    }

    override fun nod() {
        if (move(NOD_DEGREES)) handler.postDelayed({ move(-NOD_DEGREES) }, NOD_BACK_DELAY_MS)
    }

    private fun move(vAngle: Int): Boolean = sdkCall("RobotApi.moveHead") {
        api().moveHead(reqId.getAndIncrement(), MODE_RELATIVE, MODE_RELATIVE, 0, vAngle, LoggingListener("moveHead"))
    } != null

    private class LoggingListener(private val what: String) : CommandListener() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onResult(result: Int, message: String?) = Timber.d("%s: %d %s", what, result, message)
        override fun onResult(result: Int, message: String?, extraData: String?) = Timber.d("%s: %d %s", what, result, message)
    }

    private companion object {
        /** Док: «typically 5-10 seconds», «typically 5 meters»; у нас разговор — ближе. */
        const val LOST_TIMEOUT_MS = 8_000L
        const val MAX_DISTANCE_M = 3.0f
        const val MODE_RELATIVE = "relative"
        const val NOD_DEGREES = 6
        const val NOD_BACK_DELAY_MS = 450L
    }
}
