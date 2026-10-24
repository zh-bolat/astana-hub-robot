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
        override fun onEmotion(name: String) { log += "emotion:$name" }
        override fun onPhrase(text: String) { log += "phrase:$text" }
        override fun onAction(action: ChatAction) { log += "action:${action.type}:${action.placeId}" }
    }

    private val request = ChatRequest("s", "Привет", Lang.RU)
    private val done = ChatEvent.Done("req", Lang.RU)

    private fun api(flow: Flow<ChatEvent>): BackendApi = mockk { every { chat(any()) } returns flow }

    private fun action(type: String, place: String? = null) = ChatEvent.Action(ChatAction(type, place))

    @Test
    fun `completed turn passes events to the handler in stream order (action before its phrases)`() = runTest {
        val h = Recorder()
        val d = done.copy(actions = listOf(ChatAction("guide_to", "cafe")))
        val outcome = AgentTurnRunner(api(flow {
            emit(ChatEvent.Emotion("happy"))
            emit(action("guide_to", "cafe"))
            emit(ChatEvent.Phrase("Провожаю вас в кафе."))
            emit(d)
        })).run(request, h)
        assertEquals(TurnOutcome.Completed(d), outcome)
        // done.actions не дублируются: действие уже пришло событием
        assertEquals(listOf("emotion:happy", "action:guide_to:cafe", "phrase:Провожаю вас в кафе."), h.log)
    }

    @Test
    fun `actions only in done are passed after the phrases`() = runTest {
        val h = Recorder()
        AgentTurnRunner(api(flow {
            emit(ChatEvent.Phrase("Кафе на первом этаже."))
            emit(done.copy(actions = listOf(ChatAction("where_is", "cafe"))))
        })).run(request, h)
        assertEquals(listOf("phrase:Кафе на первом этаже.", "action:where_is:cafe"), h.log)
    }

    @Test
    fun `no phrase or action within 8 s falls back and closes the stream`() = runTest {
        var closed = false
        val outcome = AgentTurnRunner(api(flow<ChatEvent> {
            delay(3_000)
            emit(ChatEvent.Emotion("thinking")) // эмоция — ещё не ответ
            awaitCancellation()
        }.onCompletion { closed = true })).run(request, Recorder())
        assertEquals(TurnOutcome.Fallback("first_phrase_timeout"), outcome)
        assertEquals(8_000, testScheduler.currentTime)
        assertTrue(closed)
    }

    @Test
    fun `greeting waits only 2 s for a phrase`() = runTest {
        val outcome = AgentTurnRunner(api(flow {
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
            delay(1_000)
            emit(action("look"))
            delay(9_000)
            emit(ChatEvent.Phrase("Сейчас посмотрю."))
            emit(done)
        })).run(request, Recorder())
        assertEquals(TurnOutcome.Completed(done), outcome)
    }

    @Test
    fun `error before any reply falls back, after a reply does not`() = runTest {
        val err = ChatEvent.Error(BackendError(ErrorKind.TIMEOUT, serverCode = "timeout"))
        val before = AgentTurnRunner(api(flow { emit(ChatEvent.Emotion("neutral")); emit(err) })).run(request, Recorder())
        assertEquals(TurnOutcome.Fallback("error", err.error), before)

        val after = AgentTurnRunner(api(flow {
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
        val broken = AgentTurnRunner(api(flow { emit(ChatEvent.Phrase("Раз.")) })).run(request, Recorder())
        assertEquals(TurnOutcome.Broken("stream_ended"), broken)

        val h2 = Recorder()
        AgentTurnRunner(api(flow { emit(ChatEvent.Emotion("happy")); emit(done); emit(ChatEvent.Phrase("лишнее")) }))
            .run(request, h2)
        assertEquals(listOf("emotion:happy"), h2.log)
    }

    @Test
    fun `hung turn is capped`() = runTest {
        val timeouts = ChatTimeouts()
        val plain = AgentTurnRunner(api(flow {
            emit(ChatEvent.Phrase("Раз.")); awaitCancellation()
        }), timeouts).run(request, Recorder())
        assertEquals(TurnOutcome.Broken("turn_cap"), plain)
        assertEquals(timeouts.maxTurnMillis, testScheduler.currentTime)
    }
}
