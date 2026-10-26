package kz.astanahub.robot.di

import android.app.Application
import android.os.HandlerThread
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import kz.astanahub.robot.robotapi.PersonApiGateway
import kz.astanahub.robot.robotapi.RobotApiGateway
import kz.astanahub.robot.robotapi.RobotHeadDiagnostics
import kz.astanahub.robot.robotapi.RobotHeadGateway
import kz.astanahub.robot.robotapi.SurfaceShareCameraGateway
import kz.astanahub.robot.robotos.SkillVoiceGateway
import timber.log.Timber

/**
 * Flavor `robotos` (RobotOS 10.4): как в OrionStar RobotSample — RobotApi.connectServer,
 * после подключения setCallback(ModuleCallbackApi) + setResponseThread и SkillApi.connectApi.
 */
object FlavorGateways {
    fun create(app: Application): Gateways {
        val voice = SkillVoiceGateway()
        lateinit var robot: RobotApiGateway
        val callbackThread = HandlerThread("RobotApiCallbacks").apply { start() }
        val moduleCallback = object : ModuleCallbackApi() {
            override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean {
                // true — запрос обработан приложением, системный помощник не отвечает (ПРОВЕРИТЬ НА РОБОТЕ).
                voice.onSystemRequest(reqType, reqText, reqParam)
                return true
            }

            override fun onHWReport(function: Int, type: String?, message: String?) {
                Timber.w("onHWReport %d %s %s", function, type, message)
            }

            override fun onSuspend() = robot.setControlAvailable(false)
            override fun onRecovery() = robot.setControlAvailable(true)
        }
        robot = RobotApiGateway(app, onConnected = {
            RobotApi.getInstance().setCallback(moduleCallback)
            RobotApi.getInstance().setResponseThread(callbackThread)
            voice.connect(app)
        })
        return Gateways(
            builtinVoice = voice,
            assistant = voice,
            robot = robot,
            camera = SurfaceShareCameraGateway(),
            builtinAsrAvailable = true,
            persons = PersonApiGateway(),
            head = RobotHeadGateway(),
            headDiagnostics = RobotHeadDiagnostics(),
        )
    }
}
