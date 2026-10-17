package kz.astanahub.robot.robotapi

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.robot.NavigationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Исключения RobotApi не роняют приложение; переподключение с паузой; подтверждение остановки. */
@OptIn(ExperimentalCoroutinesApi::class)
class RobotApiGatewayTest {

    private val context = mockk<Context>(relaxed = true).also { every { it.applicationContext } returns it }
    private val api = mockk<RobotApi>(relaxed = true)
    private val listener = slot<ApiListener>()

    private fun TestScope.gateway(): RobotApiGateway {
        every { api.connectServer(any(), capture(listener)) } returns Unit
        return RobotApiGateway(context, api = { api }, scope = backgroundScope)
    }

    @Test
    fun `connectServer throwing does not crash and schedules reconnect`() = runTest {
        every { api.connectServer(any(), any()) } throws IllegalStateException("no RobotService")
        val g = RobotApiGateway(context, api = { api }, scope = backgroundScope)
        assertFalse(g.connected.value)
        assertTrue(g.reconnectPending)
        advanceTimeBy(5_001)
        verify(exactly = 2) { api.connectServer(any(), any()) }
    }

    @Test
    fun `sdk exceptions become empty results instead of crashes`() = runTest {
        val g = gateway()
        listener.captured.handleApiConnected()
        every { api.getPlaceList(any(), any()) } throws RuntimeException("binder died")
        every { api.getPosition(any(), any()) } throws SecurityException("no control")
        every { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), any<ActionListener>()) } throws RuntimeException("boom")
        every { api.stopNavigation(any()) } throws RuntimeException("boom")

        assertEquals(emptyList<Any>(), g.getPlaces())
        assertNull(g.getPosition())
        assertTrue(g.navigateTo("Кафе") is NavigationResult.Failed)
        g.stopNavigation()
        assertTrue(g.stopNavigationAndWait(100))
    }

    @Test
    fun `onConnected hook throwing does not crash`() = runTest {
        every { api.connectServer(any(), capture(listener)) } returns Unit
        val g = RobotApiGateway(context, onConnected = { error("setCallback failed") }, api = { api }, scope = backgroundScope)
        listener.captured.handleApiConnected()
        assertTrue(g.connected.value)
    }

    @Test
    fun `disconnect without recovery reconnects with growing pause, recovery cancels it`() = runTest {
        every { api.isApiConnectedService } returns true
        val g = gateway()
        listener.captured.handleApiConnected()
        listener.captured.handleApiDisconnected()
        advanceTimeBy(5_001)
        verify(exactly = 2) { api.connectServer(any(), any()) }
        advanceTimeBy(10_001)
        verify(exactly = 3) { api.connectServer(any(), any()) }

        g.setControlAvailable(true) // onRecovery
        assertTrue(g.connected.value)
        advanceTimeBy(120_000)
        verify(exactly = 3) { api.connectServer(any(), any()) }
    }

    @Test
    fun `stopNavigationAndWait waits for the navigation result`() = runTest {
        val g = gateway()
        listener.captured.handleApiConnected()
        val nav = slot<ActionListener>()
        every { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), capture(nav)) } returns 0

        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        val stop = async { g.stopNavigationAndWait(3_000) }
        advanceTimeBy(1_000)
        assertFalse(stop.isCompleted)
        verify { api.stopNavigation(any()) }

        nav.captured.onResult(Definition.ACTION_RESPONSE_STOP_SUCCESS, "", "")
        assertTrue(stop.await())
        assertEquals(NavigationResult.Cancelled, trip.await())
    }

    @Test
    fun `stopNavigationAndWait gives up after timeout`() = runTest {
        val g = gateway()
        listener.captured.handleApiConnected()
        every { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), any<ActionListener>()) } returns 0
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        assertFalse(g.stopNavigationAndWait(3_000))
        trip.cancel()
    }
}
