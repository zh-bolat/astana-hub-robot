package kz.astanahub.robot.robotos

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.listener.TextListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.SkillCallback
import com.ainirobot.coreservice.client.speech.entity.TTSEntity
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.BuiltinMicControl
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import timber.log.Timber

/**
 * Встроенный голос RobotOS 10.4 через SkillApi (robotservice.jar, пример OrionStar RobotSample):
 * TTS — playText(TTSEntity, TextListener), ASR — SkillCallback.onSpeechParResult / onQueryAsrResult.
 * Запрос, который система разобрала сама, приходит в ModuleCallbackApi.onSendRequest — его перехватываем
 * (return true) и тоже отдаём сюда как фразу, чтобы системный помощник не отвечал вместо нас.
 * ВСЁ — ПРОВЕРИТЬ НА РОБОТЕ: какие колбэки реально приходят, язык ASR/TTS.
 */
class SkillVoiceGateway : VoiceGateway, BuiltinMicControl {

    private val skillApi = SkillApi()
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
            if (!s.isNullOrBlank()) _transcripts.tryEmit(Transcript(s, isFinal = false))
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
            Timber.i("SkillCallback.onQueryAsrResult: %s", asrResult)
            emitFinal(asrResult, "asr")
        }

        override fun onError(sid: String?, code: Int, message: String?) {
            Timber.w("SkillCallback.onError %s %d %s", sid, code, message)
        }
    }

    /** Подключение SkillApi — после RobotApi.handleApiConnected (как в RobotSample). */
    fun connect(context: Context) {
        skillApi.addApiEventListener(object : ApiListener {
            override fun handleApiDisabled() {
                Timber.w("SkillApi disabled"); skillConnected = false
            }

            override fun handleApiConnected() {
                Timber.i("SkillApi connected")
                skillConnected = true
                skillApi.registerCallBack(callback)
                applyRecognizable()
            }

            override fun handleApiDisconnected() {
                Timber.w("SkillApi disconnected"); skillConnected = false
            }
        })
        skillApi.connectApi(context.applicationContext)
    }

    /** Из ModuleCallbackApi.onSendRequest: текст запроса, который система распознала. */
    fun onSystemRequest(reqType: String?, reqText: String?, reqParam: String?) {
        Timber.i("onSendRequest type=%s text=%s param=%s", reqType, reqText, reqParam)
        emitFinal(reqText, "request:$reqType")
    }

    /** Одна фраза может прийти и как onQueryAsrResult, и как onSendRequest — не дублируем. */
    private fun emitFinal(text: String?, source: String) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !recognizable) return
        val now = System.currentTimeMillis()
        val last = lastFinal
        if (last != null && last.first == t && now - last.second < DEDUP_WINDOW_MS) return
        lastFinal = t to now
        Timber.i("builtin ASR [%s]: %s", source, t)
        _transcripts.tryEmit(Transcript(t, isFinal = true))
    }

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        if (!skillConnected) {
            Timber.w("SkillApi not connected, TTS skipped")
            return false
        }
        _activity.value = VoiceActivity.SPEAKING
        try {
            for (phrase in phrases) {
                val ok = withTimeoutOrNull(TTS_BASE_TIMEOUT_MS + phrase.length * TTS_MS_PER_CHAR) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : TextListener() {
                            override fun onComplete() { if (cont.isActive) cont.resume(true) }
                            override fun onStop() { if (cont.isActive) cont.resume(false) }
                            override fun onError() { if (cont.isActive) cont.resume(false) }
                        }
                        cont.invokeOnCancellation { runCatching { skillApi.stopTTS() } }
                        // TODO ПРОВЕРИТЬ НА РОБОТЕ: язык/голос TTS (setTTSParams / getSpokemanListByLanguage).
                        skillApi.playText(TTSEntity("sid-" + UUID.randomUUID(), phrase), listener)
                    }
                } ?: false
                if (!ok) return false
            }
            return true
        } finally {
            _activity.value = VoiceActivity.IDLE
        }
    }

    override fun stopSpeaking() {
        if (skillConnected) runCatching { skillApi.stopTTS() }
    }

    override fun setLanguage(lang: Lang) {
        // В robotservice.jar есть setLangRec(LangJson) / setASRParams, формат не документирован — ПРОВЕРИТЬ НА РОБОТЕ.
        Timber.i("SkillApi setLanguage(%s): not implemented", lang.code)
    }

    /** Встроенное распознавание вкл/выкл (на время серверного ASR). */
    override fun setBuiltinListening(enabled: Boolean) {
        recognizable = enabled
        applyRecognizable()
    }

    private fun applyRecognizable() {
        if (!skillConnected) return
        runCatching { skillApi.setRecognizable(recognizable) }
            .onSuccess { Timber.i("SkillApi.setRecognizable(%s)", recognizable) }
            .onFailure { Timber.e(it, "setRecognizable") }
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 3_000L
        const val TTS_BASE_TIMEOUT_MS = 10_000L
        const val TTS_MS_PER_CHAR = 150L
    }
}
