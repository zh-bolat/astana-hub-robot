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
import kz.astanahub.robot.robot.NavJournal

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
            fun status(status: Int, data: String?) {
                when (status) {
                    Definition.STATUS_TRACK_TARGET_SUCCEED -> journal("слежение: цель захвачена (onStatusUpdate $status $data)")
                    Definition.STATUS_GUEST_LOST, Definition.STATUS_GUEST_FARAWAY -> {
                        journal("слежение: цель потеряна / далеко (onStatusUpdate $status $data)")
                        sdkCall("focus onLost") { onLost() }
                    }
                    else -> journal("слежение: onStatusUpdate $status $data")
                }
            }

            fun error(code: Int, message: String?) {
                journal("слежение: onError $code $message")
                sdkCall("focus onLost") { onLost() }
            }

            override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = status(status, data)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusUpdate(status: Int, data: String?) = status(status, data)

            override fun onError(errorCode: Int, errorString: String?, extraData: String?) = error(errorCode, errorString)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(errorCode: Int, errorString: String?) = error(errorCode, errorString)

            fun result(status: Int, response: String?) {
                journal("слежение: onResult $status $response")
                if (status != Definition.ACTION_RESPONSE_STOP_SUCCESS) sdkCall("focus onLost") { onLost() }
            }

            override fun onResult(status: Int, responseString: String?, extraData: String?) = result(status, responseString)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onResult(status: Int, responseString: String?) = result(status, responseString)
        }
        val rc = sdkCall("RobotApi.startFocusFollow") {
            api().startFocusFollow(reqId.getAndIncrement(), faceId, LOST_TIMEOUT_MS, MAX_DISTANCE_M, listener)
        }
        journal("startFocusFollow(face=$faceId) → rc=${rc ?: "исключение SDK"}")
        return rc != null
    }

    override fun stopFocusFollow() {
        val rc = sdkCall("RobotApi.stopFocusFollow") { api().stopFocusFollow(reqId.getAndIncrement()) }
        journal("stopFocusFollow → rc=${rc ?: "исключение SDK"}")
    }

    override fun resetHead() {
        val rc = sdkCall("RobotApi.resetHead") { api().resetHead(reqId.getAndIncrement(), LoggingListener("resetHead")) }
        journal("resetHead → rc=${rc ?: "исключение SDK"}")
    }

    override fun nod(degrees: Int) {
        if (move(degrees)) handler.postDelayed({ move(-degrees) }, NOD_BACK_DELAY_MS)
    }

    override fun turnTo(degrees: Int) {
        // Абсолютно по горизонтали, по вертикали — без изменений. Знак: + — влево (как угол на карте);
        // ПРОВЕРИТЬ НА РОБОТЕ кнопками «Влево / Вправо» экрана диагностики, при обратном знаке — H_SIGN = -1.
        val h = H_SIGN * degrees
        val rc = sdkCall("RobotApi.moveHead") {
            api().moveHead(reqId.getAndIncrement(), MODE_ABSOLUTE, MODE_RELATIVE, h, 0, LoggingListener("moveHead(absolute h=$h)"))
        }
        journal("moveHead(absolute h=$h) → rc=${rc ?: "исключение SDK"}")
    }

    override fun tilt(degrees: Int) {
        if (move(degrees)) handler.postDelayed({ move(-degrees) }, TILT_BACK_DELAY_MS)
    }

    private fun move(vAngle: Int): Boolean {
        val rc = sdkCall("RobotApi.moveHead") {
            api().moveHead(reqId.getAndIncrement(), MODE_RELATIVE, MODE_RELATIVE, 0, vAngle, LoggingListener("moveHead(relative v=$vAngle)"))
        }
        journal("moveHead(relative v=$vAngle) → rc=${rc ?: "исключение SDK"}")
        return rc != null
    }

    private class LoggingListener(private val what: String) : CommandListener() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onResult(result: Int, message: String?) = journal("$what: onResult $result $message")
        override fun onResult(result: Int, message: String?, extraData: String?) = journal("$what: onResult $result $message")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(errorCode: Int, errorString: String?) = journal("$what: onError $errorCode $errorString")
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) = journal("$what: onError $errorCode $errorString")
    }

    private companion object {
        /** Ответы SDK на команды головы самого приложения — в журнал экрана диагностики (без adb не увидеть иначе). */
        fun journal(text: String) = NavJournal.log("голова: $text")

        /** Док: «typically 5-10 seconds», «typically 5 meters»; у нас разговор — ближе. */
        const val LOST_TIMEOUT_MS = 8_000L
        const val MAX_DISTANCE_M = 3.0f
        const val MODE_RELATIVE = "relative"
        const val MODE_ABSOLUTE = "absolute"
        const val NOD_BACK_DELAY_MS = 450L
        const val TILT_BACK_DELAY_MS = 600L

        /** Знак горизонтального угла головы относительно угла на карте (+ — влево). */
        const val H_SIGN = 1
    }
}
