package kz.astanahub.robot.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.core.Lang

/** Одно событие SSE: `event:` (по умолчанию `message`) и `data:` (несколько строк склеиваются через `\n`). */
data class SseMessage(val event: String, val data: String)

/**
 * Разбор text/event-stream построчно (WHATWG SSE): событие заканчивается пустой строкой,
 * `:` — комментарий (keep-alive), `id:`/`retry:` не нужны. Незавершённое событие в конце потока отбрасывается.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Строка без перевода строки; возвращает готовое событие, если строка его завершила. */
    fun feed(rawLine: String): SseMessage? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseMessage? {
        val result = if (hasData) SseMessage(event?.ifEmpty { null } ?: "message", data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return result
    }
}

/** SSE-сообщение `/v1/chat` → [ChatEvent]; неизвестное событие или битый JSON → null (пропускаем). */
object ChatEventDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(message: SseMessage): ChatEvent? {
        val obj = runCatching { json.parseToJsonElement(message.data).jsonObject }.getOrNull()
        return when (message.event) {
            "turn" -> obj?.str("turn_id")?.let { ChatEvent.Turn(it, obj.str("request_id")) }
            "emotion" -> obj?.str("name")?.let { ChatEvent.Emotion(it) }
            "phrase" -> obj?.str("text")?.takeIf { it.isNotBlank() }?.let { ChatEvent.Phrase(it) }
            "action" -> obj?.str("name")?.let { name -> ChatEvent.Action(name, obj.args()) }
            "done" -> ChatEvent.Done(
                requestId = obj?.str("request_id"),
                lang = Lang.fromCode(obj?.str("lang")),
                tools = (obj?.get("tools") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            )
            "error" -> ChatEvent.Error(errorOf(obj))
            else -> null
        }
    }

    /** Общий формат ошибок: `{"error": {"code", "message"}, "request_id"}`. */
    private fun errorOf(obj: JsonObject?): BackendError {
        val err = obj?.get("error") as? JsonObject
        val code = err?.str("code")
        return BackendError(
            kind = if (code == null) ErrorKind.UNKNOWN else RetrofitBackendApi.kindFor(code, 0),
            serverCode = code,
            message = err?.str("message"),
            requestId = obj?.str("request_id"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.args(): Map<String, String> {
        val args = get("args") as? JsonObject ?: return emptyMap()
        return args.mapNotNull { (k, v) ->
            val p = v as? JsonPrimitive ?: return@mapNotNull null
            if (p is JsonNull) null else p.contentOrNull?.let { k to it }
        }.toMap()
    }
}
