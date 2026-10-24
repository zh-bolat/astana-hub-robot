package kz.astanahub.robot.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.core.Lang

/** Откуда голос: встроенный (робот: AgentOS / SkillApi; emulator: Android TTS) или наш backend. */
enum class VoiceSource(val code: String) {
    BUILTIN("builtin"),
    SERVER("server"),
    ;

    companion object {
        fun fromCode(code: String?): VoiceSource? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}

/**
 * Настройка голоса: распознавание — один источник; синтез — по языкам.
 * По умолчанию робот говорит только голосом ElevenLabs через сервер (/v1/tts) на всех языках; встроенный голос
 * (AgentOS / SkillApi / Android TTS) — только если выбран явно или включён [builtinFallback].
 */
data class VoiceConfig(
    val asr: VoiceSource = VoiceSource.SERVER,
    val tts: Map<Lang, VoiceSource> = DEFAULT_TTS,
    /** Сбой /v1/tts → встроенный голос (и откат на встроенный ASR без связи). false — текст молча + фраза из кеша. */
    val builtinFallback: Boolean = false,
    /** Тишина после речи, после которой фраза посетителя закончена (VAD приложения). */
    val vadEndSilenceMillis: Int = VadConfigDefaults.END_SILENCE_MS,
    /** `/v1/tts?stream=true`: воспроизведение с первых байт. */
    val ttsStreaming: Boolean = true,
) {
    fun ttsFor(lang: Lang): VoiceSource = tts[lang] ?: VoiceSource.SERVER

    fun describe(): String = "ASR=${asr.code}; TTS " + Lang.entries.joinToString(", ") { "${it.code}=${ttsFor(it).code}" } +
        "; builtinFallback=$builtinFallback; vad=${vadEndSilenceMillis}ms; stream=$ttsStreaming"

    companion object {
        val DEFAULT_TTS = Lang.entries.associateWith { VoiceSource.SERVER }
    }
}

object VadConfigDefaults {
    const val END_SILENCE_MS = kz.astanahub.robot.audio.VadConfig.DEFAULT_END_SILENCE_MS
    const val MIN_END_SILENCE_MS = 300
    const val MAX_END_SILENCE_MS = 1500
}

/** Хранит [VoiceConfig]: умолчание — из BuildConfig (secrets), переопределение — экран диагностики. */
class VoiceSettings(context: Context, private val defaults: VoiceConfig) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_v2", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<VoiceConfig> = _config

    private fun load(): VoiceConfig = VoiceConfig(
        asr = VoiceSource.fromCode(prefs.getString(KEY_ASR, null)) ?: defaults.asr,
        tts = Lang.entries.associateWith { VoiceSource.fromCode(prefs.getString(KEY_TTS + it.code, null)) ?: defaults.ttsFor(it) },
        builtinFallback = if (prefs.contains(KEY_FALLBACK)) prefs.getBoolean(KEY_FALLBACK, false) else defaults.builtinFallback,
        vadEndSilenceMillis = prefs.getInt(KEY_VAD, defaults.vadEndSilenceMillis),
        ttsStreaming = if (prefs.contains(KEY_STREAM)) prefs.getBoolean(KEY_STREAM, true) else defaults.ttsStreaming,
    )

    fun setBuiltinFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALLBACK, enabled).apply()
        _config.value = _config.value.copy(builtinFallback = enabled)
    }

    fun setVadEndSilence(millis: Int) {
        val v = millis.coerceIn(VadConfigDefaults.MIN_END_SILENCE_MS, VadConfigDefaults.MAX_END_SILENCE_MS)
        prefs.edit().putInt(KEY_VAD, v).apply()
        _config.value = _config.value.copy(vadEndSilenceMillis = v)
    }

    fun setTtsStreaming(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STREAM, enabled).apply()
        _config.value = _config.value.copy(ttsStreaming = enabled)
    }

    fun setAsr(source: VoiceSource) {
        prefs.edit().putString(KEY_ASR, source.code).apply()
        _config.value = _config.value.copy(asr = source)
    }

    fun setTts(lang: Lang, source: VoiceSource) {
        prefs.edit().putString(KEY_TTS + lang.code, source.code).apply()
        _config.value = _config.value.copy(tts = _config.value.tts + (lang to source))
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _config.value = defaults
    }

    private companion object {
        const val KEY_ASR = "asr"
        const val KEY_TTS = "tts_"
        const val KEY_FALLBACK = "builtin_fallback"
        const val KEY_VAD = "vad_end_silence_ms"
        const val KEY_STREAM = "tts_streaming"
    }
}

/** Управление встроенным прослушиванием (AgentOS / SkillApi); на эмуляторе — ничего не делает. */
fun interface BuiltinMicControl {
    fun setBuiltinListening(enabled: Boolean)
}
