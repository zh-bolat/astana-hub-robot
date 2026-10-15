package kz.astanahub.robot.di

import android.app.Application
import kz.astanahub.robot.fake.FakeCameraGateway
import kz.astanahub.robot.fake.FakeRobotGateway
import kz.astanahub.robot.fake.FakeVoiceGateway

/** Flavor `emulator`: всё железо робота — заглушки, SDK вендора не инициализируется. */
object FlavorGateways {
    fun create(app: Application) = Gateways(
        builtinVoice = FakeVoiceGateway(),
        builtinMic = { enabled -> timber.log.Timber.d("FAKE builtin mic listening=%s", enabled) },
        robot = FakeRobotGateway(),
        camera = FakeCameraGateway(),
    )
}
