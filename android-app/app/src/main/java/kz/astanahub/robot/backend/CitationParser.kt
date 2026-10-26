package kz.astanahub.robot.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `citations` (контракт v1.3) → [Citation]. Поле необязательное и только для показа, поэтому разбор мягкий:
 * нет поля, не массив, элемент без названия — просто пропускаем, ответ от этого не ломается.
 */
object CitationParser {

    fun parse(element: JsonElement?): List<Citation> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            Citation(
                id = obj.str("id").orEmpty(),
                type = obj.str("type").orEmpty(),
                title = title,
                url = obj.str("url")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
