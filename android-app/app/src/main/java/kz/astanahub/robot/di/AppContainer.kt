package kz.astanahub.robot.di

import android.app.Application
import kz.astanahub.robot.BuildConfig
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.live.AndroidAudioSink
import kz.astanahub.robot.live.AndroidLiveMicrophone
import kz.astanahub.robot.live.LiveSession
import kz.astanahub.robot.live.LiveSessionFactory
import kz.astanahub.robot.audio.Mp3Player
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendSettings
import kz.astanahub.robot.backend.FileQuickQuestionCache
import okhttp3.OkHttpClient
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
import kz.astanahub.robot.voice.AssistantGuard
import kz.astanahub.robot.voice.BuiltinAssistant
import kz.astanahub.robot.voice.FileAudioStore
import kz.astanahub.robot.voice.PhraseAudioCache
import kz.astanahub.robot.voice.PhraseCacheWarmer
import kz.astanahub.robot.audio.VadConfig
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kz.astanahub.robot.voice.RoutingVoiceGateway
import kz.astanahub.robot.voice.ServerVoiceGateway
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.voice.VoiceConfig
import kz.astanahub.robot.voice.VoiceSource
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatSettings
import kz.astanahub.robot.presence.PersonGateway
import kz.astanahub.robot.presence.PresenceConfig
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.presence.PresenceSimulator
import kz.astanahub.robot.presence.PresenceTracker
import kz.astanahub.robot.robot.HeadController
import kz.astanahub.robot.robot.HeadGateway

/** Железо робота: реализация выбирается flavor-ом (FlavorGateways в src/emulator и src/robot). */
class Gateways(
    /** Встроенный голос: AgentOS на роботе, текстовая заглушка на эмуляторе. */
    val builtinVoice: VoiceGateway,
    /** Встроенный ассистент (SkillApi / AgentOS): глушится, пока приложение на переднем плане. */
    val assistant: BuiltinAssistant,
    val robot: RobotGateway,
    val camera: CameraGateway,
    /** Есть настоящее встроенное распознавание (robot/robotos) — на него откат без связи. */
    val builtinAsrAvailable: Boolean = false,
    /** Люди перед роботом (PersonApi / заглушка); null — пробуждения по человеку нет. */
    val persons: PersonGateway? = null,
    val head: HeadGateway? = null,
    /** Эмулятор: «подошёл / ушёл» с экрана диагностики. */
    val presenceSimulator: PresenceSimulator? = null,
)

/** Ручной DI: один экземпляр на приложение, создаётся в [kz.astanahub.robot.AstanaHubApp]. */
class AppContainer(app: Application) {

    val text = LocalizedText(app)

    private val gateways: Gateways = FlavorGateways.create(app)
    val robot: RobotGateway get() = gateways.robot
    val camera: CameraGateway get() = gateways.camera

    /** Встроенный ассистент молчит, пока приложение видно; микрофон ему — только при ASR=builtin. */
    val assistantGuard = AssistantGuard(gateways.assistant)

    /**
     * Виден ли хоть один экран приложения (ForegroundTracker в [kz.astanahub.robot.AstanaHubApp]). Не видно —
     * постоянное прослушивание и живая сессия выключены: робот не слушает и не отвечает «из фона».
     */
    val appForeground = kotlinx.coroutines.flow.MutableStateFlow(false)
    val builtinMic: AssistantGuard get() = assistantGuard
    val presenceSimulator: PresenceSimulator? get() = gateways.presenceSimulator

    /** Голова: один контроллер на приложение (слежение, сброс, кивки). */
    val head: HeadController? = gateways.head?.let { HeadController(it) }

    /** События «подошёл / ушёл»; трекер один на приложение — анти-спам переживает пересоздание экрана. */
    val presence: Flow<PresenceEvent>? = gateways.persons?.let { persons ->
        PresenceTracker(PresenceConfig()).track(persons.persons)
            .shareIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SharingStarted.WhileSubscribed(), 0)
    }

    /** Режим разговора: agent (`/v1/chat`), legacy или live (`WS /v1/live`); меняется на экране диагностики. */
    val chatSettings = ChatSettings(app, ChatMode.fromCode(BuildConfig.CHAT_MODE) ?: ChatMode.AGENT)

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

    /** Один OkHttpClient на приложение: пул соединений и потоки общие; клиенты — через newBuilder() от него. */
    val httpClient: OkHttpClient = RetrofitBackendApi.defaultClient()

    /** Адрес сервера меняется на экране диагностики без пересборки; токен — из BuildConfig. */
    val backend: SwitchableBackendApi = SwitchableBackendApi(
        initialUrl = backendSettings.current(),
        factory = { url -> RetrofitBackendApi(url, BuildConfig.ROBOT_TOKEN, client = httpClient) },
        // Без адреса: эмулятор — заглушка, робот — честное «нет связи».
        fallback = { if (BuildConfig.IS_ROBOT) UnconfiguredBackendApi() else FakeBackendApi() },
    )

    val places = PlaceRepository(backend, gateways.robot, java.io.File(app.filesDir, "places.json"))
    val wayFinder = WayFinder(places, gateways.robot)

    /** Кнопки частых вопросов с сервера — на диске, чтобы показать их без сети. */
    val quickQuestionCache = FileQuickQuestionCache(app.filesDir)

    val voiceSettings = VoiceSettings(
        app,
        VoiceConfig(
            asr = VoiceSource.fromCode(BuildConfig.VOICE_ASR) ?: VoiceSource.SERVER,
            tts = mapOf(
                Lang.RU to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_RU) ?: VoiceSource.SERVER),
                Lang.KK to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_KK) ?: VoiceSource.SERVER),
                Lang.EN to (VoiceSource.fromCode(BuildConfig.VOICE_TTS_EN) ?: VoiceSource.SERVER),
            ),
            builtinFallback = BuildConfig.VOICE_BUILTIN_FALLBACK,
            vadEndSilenceMillis = BuildConfig.VAD_END_SILENCE_MS,
            ttsStreaming = BuildConfig.TTS_STREAMING,
        ),
    )

    /** Фразы робота голосом ElevenLabs в файлах приложения (играются и без сети). */
    val phraseCache = PhraseAudioCache(FileAudioStore(java.io.File(app.filesDir, "voice_cache")))

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val micRecorder = MicRecorder(app, preferStereoLeft = BuildConfig.PLATFORM == "robotos")
    val serverVoice = ServerVoiceGateway(
        backend = backend,
        recorder = micRecorder,
        player = Mp3Player(),
        vadConfig = {
            val c = voiceSettings.config.value
            VadConfig(endSilenceMillis = c.vadEndSilenceMillis).withSensitivity(c.micSensitivity)
        },
        cache = phraseCache,
        streaming = { voiceSettings.config.value.ttsStreaming },
    )

    /** Микрофон живого режима (VOICE_COMMUNICATION + AEC/NS, если есть). */
    val liveMicrophone = AndroidLiveMicrophone(app, preferStereoLeft = BuildConfig.PLATFORM == "robotos")

    /** Живой голосовой режим WS /v1/live (ChatMode.LIVE): одна сессия на посетителя. */
    val liveFactory = LiveSessionFactory { scope, sessionId, lang ->
        LiveSession(
            parent = scope,
            connect = { backend.openLive(lang, sessionId) },
            microphone = liveMicrophone,
            sink = AndroidAudioSink(),
            clock = android.os.SystemClock::elapsedRealtime,
        )
    }

    /** Синтез кеша фраз: при старте — недостающие, при смене сервера — все заново, при восстановлении связи — недостающие. */
    val phraseCacheWarmer = PhraseCacheWarmer(backend, phraseCache, text, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * Android TextToSpeech: «встроенный» синтез во flavor emulator + тест на экране диагностики.
     * Лениво: на роботе движок поднимается, только если его открыли в диагностике.
     */
    private val androidTtsLazy = lazy { AndroidTtsVoiceGateway(app) }
    val androidTts: AndroidTtsVoiceGateway by androidTtsLazy
    val androidTtsCreated: Boolean get() = androidTtsLazy.isInitialized()

    /** Голос для экрана: распознавание и синтез по [voiceSettings]. */
    val voice: RoutingVoiceGateway = RoutingVoiceGateway(
        builtinAsr = gateways.builtinVoice,
        builtinTts = if (BuildConfig.IS_ROBOT) gateways.builtinVoice else androidTts,
        server = serverVoice,
        settings = voiceSettings,
        micControl = assistantGuard,
        continuousServerAsr = BuildConfig.IS_ROBOT,
        builtinAsrAvailable = gateways.builtinAsrAvailable,
        healthCheck = { backend.health() is ApiResult.Success },
        cache = phraseCache,
        phrases = text,
    )

    init {
        phraseCacheWarmer.warmUp(force = false)
        appScope.launch {
            backend.url.drop(1).distinctUntilChanged().collect { phraseCacheWarmer.warmUp(force = true) }
        }
        appScope.launch {
            voice.connection.filter { it }.collect { phraseCacheWarmer.warmUp(force = false) }
        }
    }
}
