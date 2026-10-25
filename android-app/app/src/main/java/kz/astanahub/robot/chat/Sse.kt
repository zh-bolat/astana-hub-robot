package kz.astanahub.robot.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
            "emotion" -> obj?.str("name")?.takeIf { it.isNotBlank() }?.let { ChatEvent.Emotion(it) }
            "phrase" -> obj?.str("text")?.takeIf { it.isNotBlank() }?.let { ChatEvent.Phrase(it) }
            "action" -> obj?.action()?.let { ChatEvent.Action(it) }
            "done" -> done(obj)
            "error" -> ChatEvent.Error(errorOf(obj))
            else -> null
        }
    }

    private fun done(obj: JsonObject?) = ChatEvent.Done(
        requestId = obj?.str("request_id"),
        lang = Lang.fromCode(obj?.str("lang")),
        found = obj?.bool("found") ?: true,
        sources = (obj?.get("sources") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        actions = (obj?.get("actions") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.action() }.orEmpty(),
        ignored = obj?.bool("ignored") ?: false,
    )

    /**
     * JSON-режим `/v1/chat` (сервер ответил `application/json` вместо потока): `{answer, phrases?, actions, …, ignored}`
     * → те же события, что и в SSE: фразы, действия, `done`. Ошибка `{"error": …}` → [ChatEvent.Error].
     */
    fun decodeJsonBody(body: String): List<ChatEvent> {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return listOf(ChatEvent.Error(BackendError(ErrorKind.UNKNOWN, message = "bad chat json")))
        if (obj["error"] is JsonObject) return listOf(ChatEvent.Error(errorOf(obj)))
        val done = done(obj)
        if (done.ignored) return listOf(done)
        val events = mutableListOf<ChatEvent>()
        obj.str("emotion")?.takeIf { it.isNotBlank() }?.let { events += ChatEvent.Emotion(it) }
        val phrases = (obj["phrases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        (phrases?.takeIf { it.isNotEmpty() } ?: listOfNotNull(obj.str("answer")?.takeIf { it.isNotBlank() }))
            .forEach { events += ChatEvent.Phrase(it) }
        events += done
        return events
    }

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    /** `{type, place_id}`; без `type` — не действие. */
    private fun JsonObject.action(): ChatAction? {
        val type = str("type")?.takeIf { it.isNotBlank() } ?: return null
        return ChatAction(type, str("place_id")?.takeIf { it.isNotBlank() })
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
}
