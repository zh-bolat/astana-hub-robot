package kz.astanahub.robot.robotos

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.listener.TextListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.SkillCallback
import com.ainirobot.coreservice.client.speech.entity.TTSEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.EchoGate
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.Reconnector
import kz.astanahub.robot.core.sdkCall
import kz.astanahub.robot.voice.BuiltinAssistant
import kz.astanahub.robot.voice.BuiltinMicControl
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.core.LogSafe
import timber.log.Timber

/**
 * Встроенный голос RobotOS 10.4 через SkillApi (robotservice.jar, пример OrionStar RobotSample):
 * TTS — playText(TTSEntity, TextListener), ASR — SkillCallback.onSpeechParResult / onQueryAsrResult.
 * Запрос, который система разобрала сама, приходит в ModuleCallbackApi.onSendRequest — его перехватываем
 * (return true) и тоже отдаём сюда как фразу, чтобы системный помощник не отвечал вместо нас.
 * Все вызовы SkillApi — через [sdkCall]: исключение SDK не роняет приложение.
 * Пока робот говорит сам (и ~300 мс после) результаты ASR игнорируются — это эхо своей речи.
 * ВСЁ — ПРОВЕРИТЬ НА РОБОТЕ: какие колбэки реально приходят, язык ASR/TTS.
 */
class SkillVoiceGateway(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    skillApiFactory: () -> SkillApi = { SkillApi() },
    private val echo: EchoGate = EchoGate(),
) : VoiceGateway, BuiltinMicControl, BuiltinAssistant {

    private val skillApi: SkillApi? = sdkCall("new SkillApi") { skillApiFactory() }
    private val listenerAdded = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    private val reconnector = Reconnector(scope, "SkillApi", isConnected = { skillConnected }, connect = ::connectApi)
    @Volatile var skillConnected: Boolean = false
        private set

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 16)
    override val transcripts: Flow<Transcript> = _transcripts

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    @Volatile private var lastFinal: Pair<String, Long>? = null
    @Volatile private var recognizable = true

    private val callback = object : SkillCallback() {
        override fun onSpeechParResult(s: String?) {
            if (s.isNullOrBlank() || echo.suppressed()) return
            _transcripts.tryEmit(Transcript(s, isFinal = false))
        }

        override fun onStart() {
            Timber.d("SkillCallback.onStart")
            if (_activity.value != VoiceActivity.SPEAKING) _activity.value = VoiceActivity.LISTENING
        }

        override fun onStop() {
            Timber.d("SkillCallback.onStop")
            if (_activity.value == VoiceActivity.LISTENING) _activity.value = VoiceActivity.IDLE
        }

        override fun onVolumeChange(volume: Int) = Unit

        override fun onQueryEnded(status: Int) {
            Timber.d("SkillCallback.onQueryEnded %d", status)
        }

        override fun onQueryAsrResult(asrResult: String?) {
            Timber.i("SkillCallback.onQueryAsrResult: %s", LogSafe.redact(asrResult))
            emitFinal(asrResult, "asr")
        }

        override fun onError(sid: String?, code: Int, message: String?) {
            Timber.w("SkillCallback.onError %s %d %s", sid, code, message)
        }
    }

    private val apiListener = object : ApiListener {
        override fun handleApiDisabled() {
            Timber.w("SkillApi disabled"); skillConnected = false
        }

        override fun handleApiConnected() {
            Timber.i("SkillApi connected")
            skillConnected = true
            reconnector.reset()
            val api = skillApi ?: return
            // Повторное подключение не должно давать второй экземпляр колбэка.
            sdkCall("SkillApi.unregisterCallBack") { api.unregisterCallBack(callback) }
            sdkCall("SkillApi.registerCallBack") { api.registerCallBack(callback) }
            applyRecognizable()
        }

        override fun handleApiDisconnected() {
            Timber.w("SkillApi disconnected"); skillConnected = false
            reconnector.schedule()
        }
    }

    /**
     * Подключение SkillApi — после RobotApi.handleApiConnected (как в RobotSample).
     * Идемпотентно: при каждом переподключении RobotApi вызывается снова, listener регистрируется один раз,
     * уже подключённый SkillApi не трогаем.
     */
    fun connect(context: Context) {
        appContext = context.applicationContext
        val api = skillApi ?: return
        if (listenerAdded.compareAndSet(false, true)) {
            sdkCall("SkillApi.addApiEventListener") { api.addApiEventListener(apiListener) }
        }
        if (skillConnected) {
            Timber.d("SkillApi already connected")
            return
        }
        connectApi()
    }

    private fun connectApi() {
        val api = skillApi ?: return
        val ctx = appContext ?: return
        sdkCall("SkillApi.connectApi") { api.connectApi(ctx) }
    }

    /** Из ModuleCallbackApi.onSendRequest: текст запроса, который система распознала. */
    fun onSystemRequest(reqType: String?, reqText: String?, reqParam: String?) {
        Timber.i("onSendRequest type=%s text=%s param=%s", reqType, LogSafe.redact(reqText), LogSafe.redact(reqParam))
        emitFinal(reqText, "request:$reqType")
    }

    /** Одна фраза может прийти и как onQueryAsrResult, и как onSendRequest — не дублируем. */
    private fun emitFinal(text: String?, source: String) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !recognizable) return
        if (echo.suppressed()) {
            Timber.d("builtin ASR [%s] ignored: robot is speaking (len=%d)", source, t.length)
            return
        }
        val now = System.currentTimeMillis()
        val last = lastFinal
        if (last != null && last.first == t && now - last.second < DEDUP_WINDOW_MS) return
        lastFinal = t to now
        Timber.i("builtin ASR [%s]: %s", source, LogSafe.redact(t))
        _transcripts.tryEmit(Transcript(t, isFinal = true))
    }

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        val api = skillApi
        if (!skillConnected || api == null) {
            Timber.w("SkillApi not connected, TTS skipped")
            return false
        }
        _activity.value = VoiceActivity.SPEAKING
        echo.begin()
        try {
            for (phrase in phrases) {
                val ok = withTimeoutOrNull(TTS_BASE_TIMEOUT_MS + phrase.length * TTS_MS_PER_CHAR) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : TextListener() {
                            override fun onComplete() { if (cont.isActive) cont.resume(true) }
                            override fun onStop() { if (cont.isActive) cont.resume(false) }
                            override fun onError() { if (cont.isActive) cont.resume(false) }
                        }
                        cont.invokeOnCancellation { sdkCall("SkillApi.stopTTS") { api.stopTTS() } }
                        // TODO ПРОВЕРИТЬ НА РОБОТЕ: язык/голос TTS (setTTSParams / getSpokemanListByLanguage).
                        sdkCall("SkillApi.playText") { api.playText(TTSEntity("sid-" + UUID.randomUUID(), phrase), listener) }
                            ?: run { if (cont.isActive) cont.resume(false) }
                    }
                } ?: false
                if (!ok) return false
            }
            return true
        } finally {
            echo.end()
            _activity.value = VoiceActivity.IDLE
        }
    }

    override fun stopSpeaking() {
        val api = skillApi ?: return
        if (skillConnected) sdkCall("SkillApi.stopTTS") { api.stopTTS() }
    }

    override fun setLanguage(lang: Lang) {
        // В robotservice.jar есть setLangRec(LangJson) / setASRParams, формат не документирован — ПРОВЕРИТЬ НА РОБОТЕ.
        Timber.i("SkillApi setLanguage(%s): not implemented", lang.code)
    }

    /**
     * Приложение на переднем плане: системный помощник не распознаёт (setRecognizable(false)), если ASR=server;
     * его запросы и так перехватываются в onSendRequest (return true), playText приложение само не зовёт.
     */
    override fun takeOver(listen: Boolean) = setBuiltinListening(listen)

    /** Ушли в фон: распознавание системному помощнику — как по умолчанию (включено). */
    override fun release() = setBuiltinListening(true)

    /** Встроенное распознавание вкл/выкл (на время серверного ASR). */
    override fun setBuiltinListening(enabled: Boolean) {
        recognizable = enabled
        applyRecognizable()
    }

    private fun applyRecognizable() {
        val api = skillApi ?: return
        if (!skillConnected) return
        sdkCall("SkillApi.setRecognizable") { api.setRecognizable(recognizable) }
            ?.let { Timber.i("SkillApi.setRecognizable(%s)", recognizable) }
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 3_000L
        const val TTS_BASE_TIMEOUT_MS = 10_000L
        const val TTS_MS_PER_CHAR = 150L
    }
}
