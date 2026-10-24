package kz.astanahub.robot.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.core.PhraseSplitter

/** Кусок озвучки: одна или несколько подряд идущих фраз (целыми предложениями), вместе ≤ 300 символов. */
data class SpeechChunk(val phrases: List<String>) {
    val text: String = phrases.joinToString(" ")
}

/** Что звучит сейчас — для субтитров и индикатора «думаю» на экране. Колбэки — без долгой работы. */
interface SpeechListener {
    fun onChunkStart(chunk: SpeechChunk) = Unit

    /** [completed] — доиграно до конца (false — стоп, перебивание, ошибка). */
    fun onChunkDone(chunk: SpeechChunk, completed: Boolean) = Unit

    /** Пришёл первый байт синтеза куска (или кусок взят из кеша) — для таймингов хода. */
    fun onTtsFirstByte(chunk: SpeechChunk) = Unit

    companion object {
        val NONE = object : SpeechListener {}
    }
}

/**
 * Очередь фраз одного хода: фразы приходят по одной (SSE `phrase`), синтез забирает их кусками.
 * Кусок склеивается из фраз, которые уже лежат в очереди к моменту запроса, по границам фраз (≤ [maxChars],
 * контракт `/v1/tts` и AgentOS TTS). Потокобезопасна: добавлять можно из любого потока.
 */
class PhraseQueue(private val maxChars: Int = PhraseSplitter.MAX_PHRASE_LENGTH) {
    private val lock = Any()
    private val items = ArrayDeque<String>()
    private var closed = false

    /** Счётчик изменений: ожидание новой фразы без потерь при отмене (StateFlow.first ничего не забирает). */
    private val changes = MutableStateFlow(0L)

    fun add(phrase: String) {
        val p = phrase.trim()
        if (p.isEmpty()) return
        synchronized(lock) { items.addLast(p) }
        changes.update { it + 1 }
    }

    /** Больше фраз не будет: [nextChunk] вернёт null, когда очередь опустеет. */
    fun close() {
        synchronized(lock) { closed = true }
        changes.update { it + 1 }
    }

    /** Вернуть неозвученные фразы в начало (ошибка синтеза — их доскажет встроенный голос). */
    fun pushFront(phrases: List<String>) {
        if (phrases.isEmpty()) return
        synchronized(lock) { phrases.asReversed().forEach(items::addFirst) }
        changes.update { it + 1 }
    }

    /** Что ещё не забрано (для лога/фолбэка). */
    fun pending(): List<String> = synchronized(lock) { items.toList() }

    /**
     * Следующий кусок; ждёт первую фразу, null — очередь закрыта и пуста.
     * @param single взять только первую фразу (ничего не играет — старт речи без задержки на склейку).
     * @param coalesceMillis сколько подождать ещё фраз после первой, если кусок не полон и [shouldWait] = true
     *   (пока звучит предыдущий кусок; старт речи это не задерживает).
     */
    suspend fun nextChunk(
        single: Boolean = false,
        coalesceMillis: Long = 0,
        shouldWait: () -> Boolean = { true },
    ): SpeechChunk? {
        val parts = mutableListOf<String>()
        while (true) {
            val seen = changes.value
            val (head, isClosed) = synchronized(lock) { items.removeFirstOrNull() to closed }
            if (head != null) {
                parts += head
                break
            }
            if (isClosed) return null
            changes.first { it != seen }
        }
        if (single) return SpeechChunk(parts)
        var full = drainInto(parts)
        if (!full && coalesceMillis > 0 && shouldWait()) {
            withTimeoutOrNull(coalesceMillis) {
                while (true) {
                    val seen = changes.value
                    full = drainInto(parts)
                    if (full || synchronized(lock) { closed }) break
                    changes.first { it != seen }
                }
            }
        }
        return SpeechChunk(parts)
    }

    /** Добирает фразы, пока влезают; true — следующая не влезает (кусок полон). Не приостанавливается. */
    private fun drainInto(parts: MutableList<String>): Boolean = synchronized(lock) {
        var length = parts.sumOf { it.length } + (parts.size - 1).coerceAtLeast(0)
        var full = false
        while (items.isNotEmpty()) {
            val next = items.first()
            if (length + 1 + next.length > maxChars) {
                full = true
                break
            }
            items.removeFirst()
            parts += next
            length += 1 + next.length
        }
        full
    }
}
