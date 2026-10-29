package kz.astanahub.robot.backend

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Голос серверного TTS: заголовок `X-TTS-Voice` в ответах `/v1/tts` (docs/api-contract.md, раздел `/tts`).
 * [RetrofitBackendApi] сообщает каждый увиденный голос; кеш готовых фраз сверяет его со своим ([VoiceStamp]) и при
 * смене голоса на сервере синтезирует фразы заново — иначе внутри ответов звучали бы куски старым голосом.
 */
class TtsVoiceMonitor {
    private val _voice = MutableStateFlow<String?>(null)

    /** Последний голос из ответа сервера; null — ещё не видели (или сервер заголовок не шлёт). */
    val voice: StateFlow<String?> = _voice

    fun seen(header: String?) {
        val v = header?.trim()
        if (!v.isNullOrEmpty()) _voice.value = v
    }

    companion object {
        const val HEADER = "X-TTS-Voice"
    }
}

/** Каким голосом синтезирован кеш фраз (лежит рядом с кешем). */
interface VoiceStamp {
    fun get(): String?
    fun set(voice: String)
}

class FileVoiceStamp(private val file: File) : VoiceStamp {
    override fun get(): String? = runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    override fun set(voice: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(voice)
        }
    }
}

class MemoryVoiceStamp(private var value: String? = null) : VoiceStamp {
    override fun get(): String? = value
    override fun set(voice: String) { value = voice }
}
