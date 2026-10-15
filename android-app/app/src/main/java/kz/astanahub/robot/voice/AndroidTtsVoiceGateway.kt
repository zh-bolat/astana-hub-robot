package kz.astanahub.robot.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.Lang
import timber.log.Timber

/** Поддержка языка движком Android TTS. */
data class TtsLangStatus(val lang: Lang, val locale: Locale, val result: Int) {
    val available: Boolean get() = result >= TextToSpeech.LANG_AVAILABLE
    val label: String get() = when (result) {
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE -> "available"
        TextToSpeech.LANG_AVAILABLE -> "available (язык без региона)"
        TextToSpeech.LANG_MISSING_DATA -> "нет данных (скачать голос)"
        TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"
        else -> "unknown ($result)"
    }
}

/**
 * Озвучка стандартным Android TextToSpeech — запасной голос, когда серверный TTS недоступен
 * (503 speech_unavailable или `X-Speech-Provider: fake`). Распознавания не делает.
 * Казахского нет в движке → говорим по-русски и пишем в лог.
 */
class AndroidTtsVoiceGateway(context: Context) : VoiceGateway {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        Timber.i("Android TTS init ok=%s", ok)
        ready.complete(ok)
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId, false)
            override fun onError(utteranceId: String, errorCode: Int) {
                Timber.w("Android TTS error %d", errorCode)
                finish(utteranceId, false)
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    override val transcripts: Flow<Transcript> = emptyFlow()

    private val _activity = MutableStateFlow(VoiceActivity.IDLE)
    override val activity: StateFlow<VoiceActivity> = _activity

    @Volatile private var stopRequested = false

    private fun finish(id: String, ok: Boolean) {
        pending.remove(id)?.let { if (it.isActive) it.resume(ok) }
    }

    suspend fun isReady(): Boolean = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false

    suspend fun engines(): List<String> {
        if (!isReady()) return emptyList()
        val default = tts.defaultEngine
        return tts.engines.map { e -> "${e.label} (${e.name})" + if (e.name == default) " — по умолчанию" else "" }
    }

    suspend fun languageStatus(): List<TtsLangStatus> {
        if (!isReady()) return emptyList()
        return Lang.entries.map { TtsLangStatus(it, localeFor(it), tts.isLanguageAvailable(localeFor(it))) }
    }

    /** Локаль для языка; kk без поддержки → ru. */
    private fun chooseLocale(lang: Lang): Locale {
        val wanted = localeFor(lang)
        if (tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) return wanted
        val fallback = if (lang == Lang.KK) localeFor(Lang.RU) else Locale.ENGLISH
        Timber.w("Android TTS: %s не поддерживается движком, говорю %s", wanted, fallback)
        return fallback
    }

    override suspend fun speak(phrases: List<String>, lang: Lang): Boolean {
        if (phrases.isEmpty()) return true
        if (!isReady()) {
            Timber.w("Android TTS not ready")
            return false
        }
        stopRequested = false
        _activity.value = VoiceActivity.SPEAKING
        try {
            tts.language = chooseLocale(lang)
            for (phrase in phrases) {
                if (stopRequested) return false
                val ok = suspendCancellableCoroutine { cont ->
                    val id = UUID.randomUUID().toString()
                    pending[id] = cont
                    cont.invokeOnCancellation { pending.remove(id); tts.stop() }
                    if (tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finish(id, false)
                }
                if (!ok) return false
            }
            return true
        } finally {
            _activity.value = VoiceActivity.IDLE
        }
    }

    override fun stopSpeaking() {
        stopRequested = true
        tts.stop()
        pending.keys.toList().forEach { finish(it, false) }
    }

    override fun setLanguage(lang: Lang) = Unit

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L

        fun localeFor(lang: Lang): Locale = when (lang) {
            Lang.RU -> Locale.forLanguageTag("ru-RU")
            Lang.KK -> Locale.forLanguageTag("kk-KZ")
            Lang.EN -> Locale.US
        }
    }
}
