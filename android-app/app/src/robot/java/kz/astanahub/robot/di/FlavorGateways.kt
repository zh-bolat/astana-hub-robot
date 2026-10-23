package kz.astanahub.robot.di

import android.app.Application
import kz.astanahub.robot.agent.AgentMicControl
import kz.astanahub.robot.agent.AgentVoiceGateway
import kz.astanahub.robot.agent.HubAppAgent
import kz.astanahub.robot.robotapi.PersonApiGateway
import kz.astanahub.robot.robotapi.RobotApiGateway
import kz.astanahub.robot.robotapi.RobotHeadGateway
import kz.astanahub.robot.robotapi.SurfaceShareCameraGateway

/**
 * Flavor `robot`: AgentOS SDK + RobotAPI. Вызывается из Application.onCreate,
 * поэтому здесь создаётся единственный на приложение AppAgent.
 */
object FlavorGateways {
    fun create(app: Application): Gateways {
        val voice = AgentVoiceGateway()
        HubAppAgent.install(app, voice)
        return Gateways(
            builtinVoice = voice,
            builtinMic = AgentMicControl,
            robot = RobotApiGateway(app),
            camera = SurfaceShareCameraGateway(),
            builtinAsrAvailable = true,
            persons = PersonApiGateway(),
            head = RobotHeadGateway(),
        )
    }
}
