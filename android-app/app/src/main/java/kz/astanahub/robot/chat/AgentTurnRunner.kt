package kz.astanahub.robot.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import timber.log.Timber

/** Таймауты робота для `/v1/chat` (раздел «`POST /v1/chat`» контракта) и верхние границы хода. */
data class ChatTimeouts(
    /** Первое событие (`turn`) — не позже. */
    val turnMillis: Long = 4_000,
    /** Первая `phrase` (или действие агента) — не позже, иначе запасной путь `/v1/ask`. */
    val firstPhraseMillis: Long = 8_000,
    /** Приветствие `visitor_arrived`: не ждём дольше — говорим своё. */
    val greetingPhraseMillis: Long = 2_000,
    /** Весь ход (сервер обещает ≤ 20 с; с `look` — ≤ 30 с) + запас на сеть. */
    val maxTurnMillis: Long = 25_000,
    val maxLookTurnMillis: Long = 35_000,
    /** Кадр для `look` — в течение 8 с. */
    val frameMillis: Long = 8_000,
)

/** Обработчик событий хода. Вызовы не должны долго блокировать: озвучка — через очередь. */
interface TurnHandler {
    fun onTurn(turnId: String) = Unit
    fun onEmotion(name: String)
    fun onPhrase(text: String)
    fun onAction(action: ChatEvent.Action, turnId: String?)
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
 * нет `turn` за [ChatTimeouts.turnMillis] или нет первой фразы/действия за `firstPhraseMillis` → поток
 * обрывается и возвращается [TurnOutcome.Fallback]. Отмена корутины обрывает поток (посетитель перебил).
 * Все таймеры — через `delay`, поэтому тесты идут на виртуальном времени.
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
        var turnSeen = false
        var responded = false
        var look = false
        var turnId: String? = null
        var timeout: String? = null
        var outcome: TurnOutcome? = null

        val collector = launch {
            try {
                api.chat(request)
                    .transformWhile { emit(it); it !is ChatEvent.Done && it !is ChatEvent.Error }
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Turn -> {
                                turnSeen = true
                                turnId = event.turnId
                                handler.onTurn(event.turnId)
                            }
                            is ChatEvent.Emotion -> {
                                turnSeen = true
                                handler.onEmotion(event.name)
                            }
                            is ChatEvent.Phrase -> {
                                turnSeen = true
                                responded = true
                                handler.onPhrase(event.text)
                            }
                            is ChatEvent.Action -> {
                                turnSeen = true
                                responded = true
                                if (event.name == ChatEvent.Action.LOOK) look = true
                                handler.onAction(event, turnId)
                            }
                            is ChatEvent.Done -> outcome = TurnOutcome.Completed(event)
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
                delay(timeouts.turnMillis)
                if (!turnSeen) { timeout = "turn_timeout"; collector.cancel() }
            }
            launch {
                delay(firstPhraseMillis)
                if (!responded) { timeout = "first_phrase_timeout"; collector.cancel() }
            }
            launch {
                delay(timeouts.maxTurnMillis)
                if (look) delay((timeouts.maxLookTurnMillis - timeouts.maxTurnMillis).coerceAtLeast(0))
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
