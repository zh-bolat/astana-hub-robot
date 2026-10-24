package kz.astanahub.robot.voice

import java.io.File
import java.security.MessageDigest
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseSplitter
import timber.log.Timber

/** Хранилище MP3 по ключу (файлы приложения; в тестах — память). Потокобезопасно. */
interface AudioStore {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
    fun contains(key: String): Boolean
    fun keys(): Set<String>
    fun remove(key: String)
}

/** MP3 в `filesDir/voice_cache/<key>.mp3`; запись атомарная (tmp + rename). */
class FileAudioStore(private val dir: File) : AudioStore {
    private fun file(key: String) = File(dir, "$key.mp3")

    override fun get(key: String): ByteArray? = runCatching { file(key).takeIf { it.isFile }?.readBytes() }
        .onFailure { Timber.w(it, "voice cache read") }.getOrNull()

    @Synchronized
    override fun put(key: String, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file(key))) {
                file(key).delete()
                tmp.renameTo(file(key))
            }
        }.onFailure { Timber.w(it, "voice cache write") }
    }

    override fun contains(key: String) = file(key).isFile

    override fun keys(): Set<String> =
        dir.listFiles()?.filter { it.name.endsWith(".mp3") }?.map { it.name.removeSuffix(".mp3") }?.toSet().orEmpty()

    override fun remove(key: String) {
        file(key).delete()
    }
}

class MemoryAudioStore : AudioStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override fun get(key: String) = map[key]
    override fun put(key: String, bytes: ByteArray) { map[key] = bytes }
    override fun contains(key: String) = map.containsKey(key)
    override fun keys(): Set<String> = map.keys.toSet()
    override fun remove(key: String) { map.remove(key) }
}

/**
 * Кеш фраз голосом ElevenLabs (синтезированы через `/v1/tts` заранее, см. [PhraseCacheWarmer]).
 * Ключ — язык + текст фразы (без лишних пробелов). Локальные фразы робота играются отсюда — и без сети.
 */
class PhraseAudioCache(private val store: AudioStore) {

    fun key(text: String, lang: Lang): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${lang.code}|${normalize(text)}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(text: String, lang: Lang): ByteArray? = store.get(key(text, lang))?.takeIf { it.isNotEmpty() }

    fun has(text: String, lang: Lang): Boolean = store.contains(key(text, lang))

    fun put(text: String, lang: Lang, bytes: ByteArray) {
        if (bytes.isNotEmpty()) store.put(key(text, lang), bytes)
    }

    /** Удалить всё, кроме [keep] (после полного обновления: старые тексты больше не нужны). */
    fun retainOnly(keep: Set<String>) {
        store.keys().filter { it !in keep }.forEach(store::remove)
    }

    fun size(): Int = store.keys().size

    /**
     * Фраза для очереди озвучки с учётом кеша: целиком в кеше — как есть; иначе по предложениям, где
     * закешированные предложения идут отдельными фразами (играются из файла, пока остальное синтезируется),
     * а соседние незакешированные склеиваются обратно. Без закешированных предложений — как есть.
     */
    fun segment(phrase: String, lang: Lang): List<String> {
        if (has(phrase, lang)) return listOf(phrase)
        val sentences = PhraseSplitter.sentences(phrase)
        if (sentences.size < 2 || sentences.none { has(it, lang) }) return listOf(phrase)
        val out = mutableListOf<String>()
        val pending = StringBuilder()
        for (s in sentences) {
            if (has(s, lang)) {
                if (pending.isNotEmpty()) { out += pending.toString(); pending.clear() }
                out += s
            } else {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(s)
            }
        }
        if (pending.isNotEmpty()) out += pending.toString()
        return out
    }

    companion object {
        private val spaces = Regex("""\s+""")
        fun normalize(text: String): String = text.trim().replace(spaces, " ")
    }
}
