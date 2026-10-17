package kz.astanahub.robot.backend

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kz.astanahub.robot.core.Lang
import timber.log.Timber

/** Последние кнопки частых вопросов с сервера — чтобы без сети показать те же кнопки. */
interface QuickQuestionCache {
    suspend fun load(lang: Lang): List<QuickQuestion>
    suspend fun save(lang: Lang, items: List<QuickQuestion>)
}

class InMemoryQuickQuestionCache : QuickQuestionCache {
    private val map = ConcurrentHashMap<Lang, List<QuickQuestion>>()
    override suspend fun load(lang: Lang) = map[lang].orEmpty()
    override suspend fun save(lang: Lang, items: List<QuickQuestion>) {
        map[lang] = items
    }
}

/** Файлы `quick_<lang>.json` в [dir]; запись атомарная (tmp + rename). Пустой список не сохраняется. */
class FileQuickQuestionCache(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QuickQuestionCache {

    @Serializable
    private data class Item(val id: String, val text: String)

    private val serializer = ListSerializer(Item.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(lang: Lang) = File(dir, "quick_${lang.code}.json")

    override suspend fun load(lang: Lang): List<QuickQuestion> = withContext(io) {
        runCatching {
            val f = file(lang)
            if (!f.isFile) emptyList() else json.decodeFromString(serializer, f.readText()).map { QuickQuestion(it.id, it.text) }
        }.onFailure { Timber.e(it, "quick questions cache read") }.getOrDefault(emptyList())
    }

    override suspend fun save(lang: Lang, items: List<QuickQuestion>) {
        if (items.isEmpty()) return
        withContext(io) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, file(lang).name + ".tmp")
                tmp.writeText(json.encodeToString(serializer, items.map { Item(it.id, it.text) }))
                if (!tmp.renameTo(file(lang))) error("rename failed")
            }.onFailure { Timber.e(it, "quick questions cache write") }
        }
    }
}
