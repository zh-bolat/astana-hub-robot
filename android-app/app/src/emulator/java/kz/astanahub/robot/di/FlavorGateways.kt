package kz.astanahub.robot.di

import android.app.Application
import kz.astanahub.robot.fake.FakeCameraGateway
import kz.astanahub.robot.fake.FakeHeadDiagnostics
import kz.astanahub.robot.fake.FakeHeadGateway
import kz.astanahub.robot.fake.FakePersonGateway
import kz.astanahub.robot.fake.FakeRobotGateway
import kz.astanahub.robot.fake.FakeVoiceGateway

/** Flavor `emulator`: всё железо робота — заглушки, SDK вендора не инициализируется. */
object FlavorGateways {
    fun create(app: Application): Gateways {
        val persons = FakePersonGateway()
        return Gateways(
            builtinVoice = FakeVoiceGateway(),
            assistant = kz.astanahub.robot.fake.FakeAssistant(),
            robot = FakeRobotGateway(),
            camera = FakeCameraGateway(),
            persons = persons,
            head = FakeHeadGateway(),
            headDiagnostics = FakeHeadDiagnostics(persons),
            presenceSimulator = persons,
        )
    }
}
