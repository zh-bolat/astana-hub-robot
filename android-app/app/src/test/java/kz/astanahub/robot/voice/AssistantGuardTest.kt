package kz.astanahub.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Встроенный ассистент молчит на переднем плане и возвращается как был в фоне (через фейки SDK). */
class AssistantGuardTest {

    private class FakeAssistant : BuiltinAssistant {
        val calls = mutableListOf<String>()
        override fun takeOver(listen: Boolean) { calls += "takeOver($listen)" }
        override fun release() { calls += "release" }
    }

    /** Фейк флагов AgentCore: значения «как было» у системы — микрофон открыт, план включён. */
    private class FakeFlags : AssistantFlags {
        override var microphoneMuted = false
        override var voiceBar = true
        override var planDisabled = false
    }

    @Test
    fun `foreground with server ASR silences the assistant, background releases it`() {
        val a = FakeAssistant()
        val guard = AssistantGuard(a)
        guard.setBuiltinListening(false) // RoutingVoiceGateway: ASR=server
        assertTrue(a.calls.isEmpty()) // приложение ещё не на экране
        guard.setForeground(true)
        assertEquals(listOf("takeOver(false)"), a.calls)
        assertTrue(guard.isTakenOver())
        guard.setForeground(false)
        assertEquals(listOf("takeOver(false)", "release"), a.calls)
        assertFalse(guard.isTakenOver())
    }

    @Test
    fun `builtin ASR chosen explicitly gives the microphone back while staying silent`() {
        val a = FakeAssistant()
        val guard = AssistantGuard(a)
        guard.setForeground(true)
        guard.setBuiltinListening(true)
        guard.setBuiltinListening(true) // повтор — без лишних вызовов SDK
        assertEquals(listOf("takeOver(false)", "takeOver(true)"), a.calls)
    }

    @Test
    fun `resume re-applies (AgentOS opens the mic itself on foreground)`() {
        val a = FakeAssistant()
        val guard = AssistantGuard(a)
        guard.setForeground(true)
        guard.setForeground(true, force = true)
        assertEquals(listOf("takeOver(false)", "takeOver(false)"), a.calls)
    }

    @Test
    fun `background twice releases once`() {
        val a = FakeAssistant()
        val guard = AssistantGuard(a)
        guard.setForeground(true)
        guard.setForeground(false)
        guard.setForeground(false)
        assertEquals(listOf("takeOver(false)", "release"), a.calls)
    }

    @Test
    fun `AgentOS flags - mic muted and plan disabled in foreground, restored in background`() {
        val flags = FakeFlags()
        val guard = AssistantGuard(FlagAssistant(flags))
        guard.setBuiltinListening(false)
        guard.setForeground(true)
        assertTrue(flags.microphoneMuted)
        assertFalse(flags.voiceBar)
        assertTrue(flags.planDisabled)

        guard.setBuiltinListening(true) // ASR=builtin выбран в диагностике
        assertFalse(flags.microphoneMuted)
        assertTrue(flags.planDisabled) // свой LLM AgentOS всё равно не отвечает

        guard.setForeground(false)
        assertFalse(flags.microphoneMuted)
        assertTrue(flags.voiceBar)
        assertFalse(flags.planDisabled) // как было до приложения
    }

    @Test
    fun `AgentOS flags - saved state is taken before the first take over only`() {
        val flags = FakeFlags().apply { planDisabled = true } // HubAppAgent уже выставил при установке
        val a = FlagAssistant(flags)
        a.takeOver(listen = false)
        a.takeOver(listen = false)
        a.release()
        assertTrue(flags.planDisabled)
        assertFalse(flags.microphoneMuted)
        a.release() // без takeOver — ничего
        assertFalse(flags.microphoneMuted)
    }
}
