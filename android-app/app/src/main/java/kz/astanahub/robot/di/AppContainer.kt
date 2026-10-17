package kz.astanahub.robot.di

import android.app.Application
import kz.astanahub.robot.BuildConfig
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.audio.Mp3Player
import kz.astanahub.robot.backend.BackendSettings
import kz.astanahub.robot.backend.BackendUrlPolicy
import kz.astanahub.robot.security.DiagAccess
import kz.astanahub.robot.security.DiagPinGuard
import kz.astanahub.robot.security.DiagSession
import kz.astanahub.robot.security.DiagnosticsGate
import kz.astanahub.robot.security.PrefsLockStore
import kz.astanahub.robot.backend.SwitchableBackendApi
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.backend.UnconfiguredBackendApi
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.LocalizedText
import kz.astanahub.robot.places.PlaceRepository
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.voice.AndroidTtsVoiceGateway
import kz.astanahub.robot.voice.BuiltinMicControl
import kz.astanahub.robot.voice.RoutingVoiceGateway
import kz.astanahub.robot.voice.ServerVoiceGateway
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.voice.VoiceConfig
import kz.astanahub.robot.voice.VoiceSource
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.VoiceSettings

/** Железо робота: реализация выбирается flavor-ом (FlavorGateways в src/emulator и src/robot). */
class Gateways(
    /** Встроенный голос: AgentOS на роботе, текстовая заглушка на эмуляторе. */
    val builtinVoice: VoiceGateway,
    val builtinMic: BuiltinMicControl,
    val robot: RobotGateway,
    val camera: CameraGateway,
)

/** Ручной DI: один экземпляр на приложение, создаётся в [kz.astanahub.robot.AstanaHubApp]. */
class AppContainer(app: Application) {

    val text = LocalizedText(app)

    private val gateways: Gateways = FlavorGateways.create(app)
    val robot: RobotGateway get() = gateways.robot
    val camera: CameraGateway get() = gateways.camera
    val builtinMic: BuiltinMicControl get() = gateways.builtinMic

    /** Куда можно переключить сервер с экрана диагностики: release — https + allowlist, debug — ещё LAN. */
    val backendUrlPolicy = BackendUrlPolicy.fromBuildConfig(
        backendUrl = BuildConfig.BACKEND_URL,
        allowedHostsCsv = BuildConfig.BACKEND_ALLOWED_HOSTS,
        debug = BuildConfig.DEBUG,
    )

    val backendSettings = BackendSettings(app, BuildConfig.BACKEND_URL, backendUrlPolicy)

    /** Доступ к экрану диагностики: PIN из secrets (хеш), блокировка после неверных попыток. */
    val diagnostics = DiagnosticsGate(
        access = DiagAccess.of(BuildConfig.DIAG_PIN_HASH, BuildConfig.IS_ROBOT),
        guard = DiagPinGuard(BuildConfig.DIAG_PIN_HASH, BuildConfig.DIAG_PIN_SALT, PrefsLockStore(app)),
        session = DiagSession(android.os.SystemClock::elapsedRealtime),
    )

    /** Адрес сервера меняется на экране диагностики без пересборки; токен — из BuildConfig. */
    val backend: SwitchableBackendApi = SwitchableBackendApi(
        initialUrl = backendSettings.current(),
        factory = { url -> RetrofitBackendApi(url, BuildConfig.ROBOT_TOKEN) },
        // Без адреса: эмулятор — заглушка, робот — честное «нет связи».
        fallback = { if (BuildConfig.IS_ROBOT) UnconfiguredBackendApi() else FakeBackendApi() },
    )

    val places = PlaceRepository(backend, gateways.robot, java.io.File(app.filesDir, "places.json"))
    val wayFinder = WayFinder(places, gateways.robot)

    val voiceSettings = VoiceSettings(
        app,
        VoiceConfig(
            asr = VoiceSource.fromCode(BuildConfig.VOICE_ASR) ?: VoiceSource.SERVER,
            tts = mapOf(
                Lang.RU to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_RU) ?: VoiceSource.BUILTIN),
                Lang.KK to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_KK) ?: VoiceSource.SERVER),
                Lang.EN to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_EN) ?: VoiceSource.BUILTIN),
            ),
        ),
    )

    val micRecorder = MicRecorder(app, preferStereoLeft = BuildConfig.PLATFORM == "robotos")
    val serverVoice = ServerVoiceGateway(backend, micRecorder, Mp3Player())

    /** Android TextToSpeech: «встроенный» синтез во flavor emulator + тест на экране диагностики. */
    val androidTts = AndroidTtsVoiceGateway(app)

    /** Голос для экрана: распознавание и синтез по [voiceSettings]. */
    val voice: RoutingVoiceGateway = RoutingVoiceGateway(
        builtinAsr = gateways.builtinVoice,
        builtinTts = if (BuildConfig.IS_ROBOT) gateways.builtinVoice else androidTts,
        server = serverVoice,
        settings = voiceSettings,
        micControl = gateways.builtinMic,
        continuousServerAsr = BuildConfig.IS_ROBOT,
    )
}
