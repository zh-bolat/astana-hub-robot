package kz.astanahub.robot.voice

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.BackendError

/** Итог озвучки очереди. */
sealed interface PipelineResult {
    data object Done : PipelineResult
    data object Interrupted : PipelineResult

    /** Синтез не удался; фразы неудачного куска возвращены в начало очереди. */
    data class Failed(val error: BackendError) : PipelineResult
}

/** Результат подготовки куска (синтез + prepare плеера). */
sealed interface Prepared<out T> {
    data class Ready<T>(val value: T) : Prepared<T>
    data class Failed(val error: BackendError) : Prepared<Nothing>
}

/**
 * Озвучка очереди фраз без пауз между ними.
 *
 * [lookahead] — серверный TTS: пока звучит кусок N, синтезируется и готовится кусок N+1 (ровно 1 вперёд —
 * канал без буфера). Когда N доиграл, N+1 уже подготовлен и стартует сразу, без ожидания сети.
 * Ничего не звучит → берётся одна первая фраза (старт речи без задержки на склейку); пока звучит —
 * склеиваются все фразы, что уже в очереди, и ещё до [coalesceMillis] ждём новых.
 *
 * [sequential] — встроенный TTS (SkillApi / AgentOS / Android TTS): предзагрузки нет, но фразы, накопившиеся
 * к концу предыдущего куска, уходят одним вызовом ≤ 300 символов.
 */
object SpeechPipeline {
    const val COALESCE_MS = 150L

    private class Item<T>(val chunk: SpeechChunk, val result: Prepared<T>)

    suspend fun <T : Any> lookahead(
        queue: PhraseQueue,
        prepare: suspend (SpeechChunk) -> Prepared<T>,
        play: suspend (T) -> Boolean,
        /** Освободить подготовленный, но не сыгранный звук. Должен быть идемпотентным. */
        release: (T) -> Unit,
        listener: SpeechListener = SpeechListener.NONE,
        isStopped: () -> Boolean = { false },
        coalesceMillis: Long = COALESCE_MS,
    ): PipelineResult = coroutineScope {
        fun releaseItem(item: Item<T>) {
            (item.result as? Prepared.Ready<T>)?.let { release(it.value) }
        }
        // Куски, отданные проигрывателю и ещё не доигранные: > 0 — что-то звучит (или вот-вот зазвучит).
        val outstanding = AtomicInteger(0)
        val ready = Channel<Item<T>>(Channel.RENDEZVOUS, onUndeliveredElement = ::releaseItem)
        val producer = launch {
            try {
                while (true) {
                    val busy = outstanding.get() > 0
                    val chunk = queue.nextChunk(
                        single = !busy,
                        coalesceMillis = coalesceMillis,
                        shouldWait = { outstanding.get() > 0 },
                    ) ?: break
                    val item = Item(chunk, prepare(chunk))
                    outstanding.incrementAndGet()
                    try {
                        ready.send(item)
                    } catch (e: Throwable) {
                        releaseItem(item)
                        throw e
                    }
                    if (item.result is Prepared.Failed) break
                }
            } finally {
                ready.close()
            }
        }
        try {
            for (item in ready) {
                when (val r = item.result) {
                    is Prepared.Failed -> {
                        queue.pushFront(item.chunk.phrases)
                        return@coroutineScope PipelineResult.Failed(r.error)
                    }
                    is Prepared.Ready -> {
                        if (isStopped()) {
                            release(r.value)
                            return@coroutineScope PipelineResult.Interrupted
                        }
                        listener.onChunkStart(item.chunk)
                        val ok = try {
                            play(r.value)
                        } finally {
                            outstanding.decrementAndGet()
                        }
                        listener.onChunkDone(item.chunk, ok && !isStopped())
                        if (!ok || isStopped()) return@coroutineScope PipelineResult.Interrupted
                    }
                }
            }
            PipelineResult.Done
        } finally {
            producer.cancel()
            ready.cancel()
        }
    }

    suspend fun sequential(
        queue: PhraseQueue,
        speak: suspend (SpeechChunk) -> Boolean,
        listener: SpeechListener = SpeechListener.NONE,
        isStopped: () -> Boolean = { false },
    ): PipelineResult {
        while (true) {
            val chunk = queue.nextChunk() ?: return PipelineResult.Done
            if (isStopped()) return PipelineResult.Interrupted
            listener.onChunkStart(chunk)
            val ok = speak(chunk)
            listener.onChunkDone(chunk, ok && !isStopped())
            if (!ok || isStopped()) return PipelineResult.Interrupted
        }
    }
}
