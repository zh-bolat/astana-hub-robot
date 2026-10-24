package kz.astanahub.robot.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import timber.log.Timber

/** Таймауты робота для `/v1/chat` (раздел «`POST /v1/chat`», дополнения v1.1) и верхняя граница хода. */
data class ChatTimeouts(
    /** Первая `phrase` или `action` — не позже, иначе запасной путь `/v1/ask` + локальные правила. */
    val firstPhraseMillis: Long = 8_000,
    /** Приветствие `visitor_arrived`: не ждём дольше — говорим своё. */
    val greetingPhraseMillis: Long = 2_000,
    /** Весь поток до `done` (сервер ограничивает ответ 4 фразами) + запас на сеть. */
    val maxTurnMillis: Long = 25_000,
    /** Первой фразы нет столько — короткое «хм…» из кеша голоса (раз за ход). */
    val fillerMillis: Long = 1_200,
)

/** Обработчик событий хода. Вызовы не должны долго блокировать: озвучка — через очередь. */
interface TurnHandler {
    fun onEmotion(name: String)
    fun onPhrase(text: String)
    fun onAction(action: ChatAction)
}

sealed interface TurnOutcome {
    data class Completed(val done: ChatEvent.Done) : TurnOutcome

    /** Агент ничего не сказал и не сделал (таймаут, ошибка, обрыв) — нужен запасной путь. */
    data class Fallback(val reason: String, val error: BackendError? = null) : TurnOutcome

    /** Агент уже ответил частично, потом ошибка/таймаут: запасной путь не нужен (был бы повтор). */
    data class Broken(val reason: String, val error: BackendError? = null) : TurnOutcome
}

/**
 * Один ход агента: читает SSE, раздаёт события [TurnHandler] и следит за таймаутами контракта:
 * нет первой фразы или действия за `firstPhraseMillis` → поток обрывается и возвращается [TurnOutcome.Fallback].
 * Действия, которые пришли только в `done.actions` (без событий `action`), тоже отдаются обработчику — после фраз.
 * Отмена корутины обрывает поток (посетитель перебил). Таймеры — через `delay` (тесты на виртуальном времени).
 */
class AgentTurnRunner(
    private val api: BackendApi,
    private val timeouts: ChatTimeouts = ChatTimeouts(),
) {
    suspend fun run(
        request: ChatRequest,
        handler: TurnHandler,
        firstPhraseMillis: Long = timeouts.firstPhraseMillis,
    ): TurnOutcome = coroutineScope {
        var responded = false
        var actionsSeen = false
        var timeout: String? = null
        var outcome: TurnOutcome? = null

        val collector = launch {
            try {
                api.chat(request)
                    .transformWhile { emit(it); it !is ChatEvent.Done && it !is ChatEvent.Error }
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Emotion -> handler.onEmotion(event.name)
                            is ChatEvent.Phrase -> {
                                responded = true
                                handler.onPhrase(event.text)
                            }
                            is ChatEvent.Action -> {
                                responded = true
                                actionsSeen = true
                                handler.onAction(event.action)
                            }
                            is ChatEvent.Done -> {
                                if (!actionsSeen) event.actions.forEach(handler::onAction)
                                outcome = TurnOutcome.Completed(event)
                            }
                            is ChatEvent.Error -> outcome =
                                if (responded) TurnOutcome.Broken("error", event.error)
                                else TurnOutcome.Fallback("error", event.error)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "chat stream failed")
                outcome = if (responded) TurnOutcome.Broken("exception") else TurnOutcome.Fallback("exception")
            }
        }

        val watchdogs = launch {
            launch {
                delay(firstPhraseMillis)
                if (!responded) { timeout = "first_phrase_timeout"; collector.cancel() }
            }
            launch {
                delay(timeouts.maxTurnMillis)
                timeout = "turn_cap"
                collector.cancel()
            }
        }
        collector.join()
        watchdogs.cancel()

        outcome ?: run {
            val reason = timeout ?: "stream_ended"
            Timber.w("chat turn: %s (responded=%s)", reason, responded)
            if (responded) TurnOutcome.Broken(reason) else TurnOutcome.Fallback(reason)
        }
    }
}
