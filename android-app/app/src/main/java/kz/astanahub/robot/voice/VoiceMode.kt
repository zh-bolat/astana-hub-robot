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
 * По умолчанию: ASR — сервер (все языки); TTS ru/en — встроенный, kk — сервер (/v1/tts, при fake/503 — встроенный).
 */
data class VoiceConfig(
    val asr: VoiceSource = VoiceSource.SERVER,
    val tts: Map<Lang, VoiceSource> = DEFAULT_TTS,
) {
    fun ttsFor(lang: Lang): VoiceSource = tts[lang] ?: VoiceSource.BUILTIN

    fun describe(): String = "ASR=${asr.code}; TTS " + Lang.entries.joinToString(", ") { "${it.code}=${ttsFor(it).code}" }

    companion object {
        val DEFAULT_TTS = mapOf(Lang.RU to VoiceSource.BUILTIN, Lang.KK to VoiceSource.SERVER, Lang.EN to VoiceSource.BUILTIN)
    }
}

/** Хранит [VoiceConfig]: умолчание — из BuildConfig (secrets), переопределение — экран диагностики. */
class VoiceSettings(context: Context, private val defaults: VoiceConfig) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_v2", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<VoiceConfig> = _config

    private fun load(): VoiceConfig = VoiceConfig(
        asr = VoiceSource.fromCode(prefs.getString(KEY_ASR, null)) ?: defaults.asr,
        tts = Lang.entries.associateWith { VoiceSource.fromCode(prefs.getString(KEY_TTS + it.code, null)) ?: defaults.ttsFor(it) },
    )

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
    }
}

/** Управление встроенным прослушиванием (AgentOS / SkillApi); на эмуляторе — ничего не делает. */
fun interface BuiltinMicControl {
    fun setBuiltinListening(enabled: Boolean)
}
