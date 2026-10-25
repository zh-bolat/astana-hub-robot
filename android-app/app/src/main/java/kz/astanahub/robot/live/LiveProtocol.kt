package kz.astanahub.robot.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.core.Lang

/**
 * Кадры `WS /v1/live` (docs/api-contract.md, раздел «WS /v1/live»).
 * Клиент → сервер: JSON-кадры ниже + бинарный микрофон (PCM 16-bit LE mono 16 кГц).
 */
object LiveFrames {
    fun hello(lang: Lang, placeId: String?, aec: Boolean): String = buildJsonObject {
        put("type", "hello")
        put("lang", lang.code)
        put("place_id", placeId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("aec", aec)
    }.toString()

    fun visitorArrived(): String = """{"type":"visitor_arrived"}"""

    fun interrupt(): String = """{"type":"interrupt"}"""

    fun playbackDone(turnId: String): String = buildJsonObject {
        put("type", "playback_done")
        put("turn_id", turnId)
    }.toString()

    fun state(placeId: String?, navigating: Boolean): String = buildJsonObject {
        put("type", "state")
        put("place_id", placeId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("navigating", navigating)
    }.toString()
}

/** Формат звука ответа из `ready.audio_format`: `pcm_s16le_24000` → PCM 16-bit LE mono 24 кГц. */
data class LiveAudioFormat(val sampleRate: Int) {
    /** Байт на мс звука (16-bit mono). */
    val bytesPerMillis: Double get() = sampleRate * 2 / 1000.0

    companion object {
        val DEFAULT = LiveAudioFormat(24_000)

        /** Неизвестный формат (не PCM s16le) → null: такой звук робот не сыграет. */
        fun parse(name: String?): LiveAudioFormat? {
            val m = Regex("""^pcm_s16le_(\d{4,6})$""").matchEntire(name?.trim()?.lowercase() ?: return null) ?: return null
            val rate = m.groupValues[1].toInt()
            return if (rate in 8_000..48_000) LiveAudioFormat(rate) else null
        }
    }
}

/** Событие сервера `WS /v1/live` (и транспорта: звук, обрыв). */
sealed interface LiveEvent {
    data class Ready(val format: LiveAudioFormat?) : LiveEvent
    data object Listening : LiveEvent
    data object UserSpeaking : LiveEvent
    data class UserPartial(val text: String) : LiveEvent
    data class UserFinal(val text: String, val lang: Lang?) : LiveEvent
    data object Ignored : LiveEvent
    data object Thinking : LiveEvent
    data class TurnStart(val turnId: String?) : LiveEvent
    data class Emotion(val name: String) : LiveEvent
    data class Phrase(val text: String) : LiveEvent
    /** Бинарный кадр: звук ответа в формате из [Ready]. */
    class Audio(val pcm: ByteArray) : LiveEvent
    /** [turnId] — ход, к которому относится действие (контракт v1.x); старый сервер его не шлёт. */
    data class Action(val type: String, val placeId: String?, val turnId: String? = null) : LiveEvent
    data class TurnEnd(val turnId: String?, val lang: Lang?, val found: Boolean?) : LiveEvent
    data class Interrupted(val turnId: String?) : LiveEvent
    /** `{"type":"error"}` с сервера. */
    data class Error(val error: BackendError) : LiveEvent
    /** Соединение закрыто: [error] = null — штатно (1000), иначе обрыв/отказ. */
    data class Closed(val error: BackendError?) : LiveEvent

    companion object {
        /** Текстовый кадр сервера → событие; неизвестный тип или не JSON → null (игнорируем). */
        fun parse(text: String): LiveEvent? {
            val obj: JsonObject = runCatching { RetrofitBackendApi.json.parseToJsonElement(text).jsonObject }
                .getOrNull() ?: return null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            return when (str("type")) {
                "ready" -> Ready(LiveAudioFormat.parse(str("audio_format")))
                "listening" -> Listening
                "user_speaking" -> UserSpeaking
                "user_partial" -> UserPartial(str("text").orEmpty().trim())
                "user_final" -> UserFinal(str("text").orEmpty().trim(), Lang.fromCode(str("lang")))
                "ignored" -> Ignored
                "thinking" -> Thinking
                "turn_start" -> TurnStart(str("turn_id"))
                "emotion" -> str("name")?.let(::Emotion)
                "phrase" -> str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let(::Phrase)
                // Тип действия — в `type_` (поле `type` занято типом кадра); `action` — на всякий случай.
                "action" -> (str("type_") ?: str("action"))?.let { Action(it, str("place_id"), str("turn_id")?.takeIf(String::isNotBlank)) }
                "turn_end" -> TurnEnd(str("turn_id"), Lang.fromCode(str("lang")), (obj["found"] as? JsonPrimitive)?.booleanOrNull)
                "interrupted" -> Interrupted(str("turn_id"))
                "error" -> {
                    val err = obj["error"] as? JsonObject
                    val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: str("code")
                    val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: str("message")
                    Error(BackendError(RetrofitBackendApi.kindFor(code, 0), serverCode = code, message = message))
                }
                else -> null
            }
        }

        /** Код закрытия WebSocket → ошибка (null — штатное закрытие). */
        fun closeError(code: Int, reason: String): BackendError? = when (code) {
            1000 -> null
            1008 -> BackendError(ErrorKind.UNAUTHORIZED, message = "ws closed $code $reason")
            1013 -> BackendError(ErrorKind.RATE_LIMITED, message = "ws closed $code $reason")
            else -> BackendError(ErrorKind.UNKNOWN, message = "ws closed $code $reason")
        }
    }
}
