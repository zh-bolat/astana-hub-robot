package kz.astanahub.robot.chat

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Таймауты и исходы хода `/v1/chat` на виртуальном времени. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnRunnerTest {

    private class Recorder : TurnHandler {
        val log = mutableListOf<String>()
        override fun onTurn(turnId: String) { log += "turn:$turnId" }
        override fun onEmotion(name: String) { log += "emotion:$name" }
        override fun onPhrase(text: String) { log += "phrase:$text" }
        override fun onAction(action: ChatEvent.Action, turnId: String?) { log += "action:${action.name}:$turnId" }
    }

    private val request = ChatRequest("s", "Привет", Lang.RU)
    private val done = ChatEvent.Done("req", Lang.RU, emptyList())

    private fun api(flow: Flow<ChatEvent>): BackendApi = mockk { every { chat(any()) } returns flow }

    @Test
    fun `completed turn passes every event to the handler`() = runTest {
        val h = Recorder()
        val outcome = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t1", "r"))
            emit(ChatEvent.Emotion("happy"))
            emit(ChatEvent.Phrase("Здравствуйте!"))
            emit(ChatEvent.Action("where_is", mapOf("place_id" to "cafe")))
            emit(done)
        })).run(request, h)
        assertEquals(TurnOutcome.Completed(done), outcome)
        assertEquals(listOf("turn:t1", "emotion:happy", "phrase:Здравствуйте!", "action:where_is:t1"), h.log)
    }

    @Test
    fun `no turn within 4 s falls back and closes the stream`() = runTest {
        var closed = false
        val outcome = AgentTurnRunner(api(flow<ChatEvent> { awaitCancellation() }.onCompletion { closed = true }))
            .run(request, Recorder())
        assertEquals(TurnOutcome.Fallback("turn_timeout"), outcome)
        assertEquals(4_000, testScheduler.currentTime)
        assertTrue(closed)
    }

    @Test
    fun `turn but no phrase within 8 s falls back`() = runTest {
        val outcome = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null))
            delay(3_000)
            emit(ChatEvent.Emotion("thinking")) // эмоция — ещё не ответ
            awaitCancellation()
        })).run(request, Recorder())
        assertEquals(TurnOutcome.Fallback("first_phrase_timeout"), outcome)
        assertEquals(8_000, testScheduler.currentTime)
    }

    @Test
    fun `greeting waits only 2 s for a phrase`() = runTest {
        val outcome = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null))
            delay(2_500)
            emit(ChatEvent.Phrase("Здравствуйте!"))
            emit(done)
        })).run(request, Recorder(), firstPhraseMillis = ChatTimeouts().greetingPhraseMillis)
        assertEquals(TurnOutcome.Fallback("first_phrase_timeout"), outcome)
        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun `an action counts as the first reply`() = runTest {
        val outcome = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null))
            delay(1_000)
            emit(ChatEvent.Action("look", emptyMap()))
            delay(12_000) // описание кадра дольше 8 с — это нормально после look
            emit(ChatEvent.Phrase("Вижу стенд."))
            emit(done)
        })).run(request, Recorder())
        assertEquals(TurnOutcome.Completed(done), outcome)
    }

    @Test
    fun `error before any reply falls back, after a reply does not`() = runTest {
        val err = ChatEvent.Error(BackendError(ErrorKind.LLM_UNAVAILABLE))
        val before = AgentTurnRunner(api(flow { emit(ChatEvent.Turn("t", null)); emit(err) })).run(request, Recorder())
        assertEquals(TurnOutcome.Fallback("error", err.error), before)

        val after = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null))
            emit(ChatEvent.Phrase("Секунду…"))
            emit(err)
        })).run(request, Recorder())
        assertEquals(TurnOutcome.Broken("error", err.error), after)
    }

    @Test
    fun `http failure before the stream falls back`() = runTest {
        val err = ChatEvent.Error(BackendError(ErrorKind.UNAUTHORIZED, httpStatus = 401))
        val outcome = AgentTurnRunner(api(flow { emit(err) })).run(request, Recorder())
        assertTrue(outcome is TurnOutcome.Fallback)
    }

    @Test
    fun `stream ended without done after a phrase is broken, events after done are ignored`() = runTest {
        val h = Recorder()
        val broken = AgentTurnRunner(api(flow { emit(ChatEvent.Turn("t", null)); emit(ChatEvent.Phrase("Раз.")) }))
            .run(request, h)
        assertEquals(TurnOutcome.Broken("stream_ended"), broken)

        val h2 = Recorder()
        AgentTurnRunner(api(flow { emit(ChatEvent.Turn("t", null)); emit(done); emit(ChatEvent.Phrase("лишнее")) }))
            .run(request, h2)
        assertEquals(listOf("turn:t"), h2.log)
    }

    @Test
    fun `hung turn is capped, look gives it more time`() = runTest {
        val timeouts = ChatTimeouts()
        val plain = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null)); emit(ChatEvent.Phrase("Раз.")); awaitCancellation()
        }), timeouts).run(request, Recorder())
        assertEquals(TurnOutcome.Broken("turn_cap"), plain)
        assertEquals(timeouts.maxTurnMillis, testScheduler.currentTime)

        val start = testScheduler.currentTime
        val look = AgentTurnRunner(api(flow {
            emit(ChatEvent.Turn("t", null)); emit(ChatEvent.Action("look", emptyMap())); awaitCancellation()
        }), timeouts).run(request, Recorder())
        assertEquals(TurnOutcome.Broken("turn_cap"), look)
        assertEquals(timeouts.maxLookTurnMillis, testScheduler.currentTime - start)
    }
}
