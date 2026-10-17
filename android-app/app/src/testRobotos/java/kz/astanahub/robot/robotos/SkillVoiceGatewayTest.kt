package kz.astanahub.robot.robotos

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.listener.TextListener
import com.ainirobot.coreservice.client.speech.SkillApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.core.EchoGate
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.voice.VoiceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillVoiceGatewayTest {

    private val context = mockk<Context>(relaxed = true).also { every { it.applicationContext } returns it }
    private val api = mockk<SkillApi>(relaxed = true)
    private val apiListener = slot<ApiListener>()
    private var now = 0L

    @Test
    fun `connect is idempotent - one listener, no reconnect when connected`() = runTest {
        every { api.addApiEventListener(capture(apiListener)) } returns Unit
        val g = SkillVoiceGateway(backgroundScope, { api }, EchoGate(clock = { now }))
        g.connect(context)
        g.connect(context)
        verify(exactly = 1) { api.addApiEventListener(any()) }
        verify(exactly = 2) { api.connectApi(any()) } // ещё не подключён — пробуем снова

        apiListener.captured.handleApiConnected()
        apiListener.captured.handleApiConnected()
        g.connect(context)
        verify(exactly = 2) { api.connectApi(any()) }
        verify(exactly = 2) { api.unregisterCallBack(any()) } // перед каждой регистрацией — снятие
        assertTrue(g.skillConnected)
    }

    @Test
    fun `sdk exceptions do not crash`() = runTest {
        every { api.addApiEventListener(capture(apiListener)) } returns Unit
        every { api.connectApi(any()) } throws IllegalStateException("no speech service")
        every { api.playText(any<com.ainirobot.coreservice.client.speech.entity.TTSEntity>(), any()) } throws RuntimeException("boom")
        every { api.stopTTS() } throws RuntimeException("boom")
        every { api.setRecognizable(any()) } throws RuntimeException("boom")
        val g = SkillVoiceGateway(backgroundScope, { api }, EchoGate(clock = { now }))
        g.connect(context)
        apiListener.captured.handleApiConnected()

        assertFalse(g.speak(listOf("Привет."), Lang.RU))
        assertEquals(VoiceActivity.IDLE, g.activity.value)
        g.stopSpeaking()
        g.setBuiltinListening(false)
    }

    @Test
    fun `skill api constructor failing leaves a silent but working gateway`() = runTest {
        val g = SkillVoiceGateway(backgroundScope, { throw NoClassDefFoundError("SkillApi") })
        g.connect(context)
        assertFalse(g.speak(listOf("Привет."), Lang.RU))
    }

    @Test
    fun `asr results during own speech and the tail are ignored`() = runTest {
        every { api.addApiEventListener(capture(apiListener)) } returns Unit
        val text = slot<TextListener>()
        every { api.playText(any<com.ainirobot.coreservice.client.speech.entity.TTSEntity>(), capture(text)) } returns Unit
        val g = SkillVoiceGateway(backgroundScope, { api }, EchoGate(tailMillis = 300, clock = { now }))
        g.connect(context)
        apiListener.captured.handleApiConnected()
        val heard = mutableListOf<String>()
        backgroundScope.launch { g.transcripts.collect { heard += it.text } }

        val speech = async { g.speak(listOf("Я робот Astana Hub."), Lang.RU) }
        runCurrent()
        g.onSystemRequest("chat", "я робот", null) // эхо
        text.captured.onComplete()
        assertTrue(speech.await())
        now += 100
        g.onSystemRequest("chat", "астана хаб", null) // хвост эха
        runCurrent()
        assertTrue(heard.isEmpty())

        now += 300
        g.onSystemRequest("chat", "где кафе", null)
        runCurrent()
        assertEquals(listOf("где кафе"), heard)
    }
}
