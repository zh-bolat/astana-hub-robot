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
import kz.astanahub.robot.robot.RobotGateway
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
    private val journal = mutableListOf<String>()

    private fun TestScope.gateway(tripTimeoutMillis: Long = RobotGateway.TRIP_TIMEOUT_MS): RobotApiGateway {
        every { api.connectServer(any(), capture(listener)) } returns Unit
        return RobotApiGateway(
            context, api = { api }, scope = backgroundScope, tripTimeoutMillis = tripTimeoutMillis,
            journal = { journal += it },
        )
    }

    /** Подключённый шлюз; startNavigation отвечает [rcs] по очереди, слушатели копятся в [navs]. */
    private fun TestScope.connected(
        navs: MutableList<ActionListener>,
        rcs: List<Int> = listOf(0),
        tripTimeoutMillis: Long = RobotGateway.TRIP_TIMEOUT_MS,
    ): RobotApiGateway {
        val g = gateway(tripTimeoutMillis)
        listener.captured.handleApiConnected()
        every { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), capture(navs)) } returnsMany rcs
        return g
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
    fun `startNavigation uses the documented default-speed variant with recommended values`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs)
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        verify { api.startNavigation(any<Int>(), "Кафе", 0.2, 30_000L, any<ActionListener>()) }
        navs.single().onResult(Definition.RESULT_OK, "true", "")
        assertEquals(NavigationResult.Arrived, trip.await())
        assertTrue(journal.toString(), journal.any { it.contains("startNavigation") && it.contains("rc=0") })
        assertTrue(journal.any { it.contains("итог Arrived") })
    }

    @Test
    fun `result codes map to outcomes, statuses and errors are journaled with names`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs, rcs = listOf(0, 0, 0, 0))
        suspend fun trip(answer: (ActionListener) -> Unit): NavigationResult {
            val t = async { g.navigateTo("Кафе") }
            runCurrent()
            answer(navs.last())
            return t.await()
        }
        assertEquals(NavigationResult.NotLocalized, trip { it.onError(Definition.ERROR_NOT_ESTIMATE, "not estimate", "") })
        assertEquals(NavigationResult.AlreadyThere, trip { it.onError(Definition.ERROR_IN_DESTINATION, "in destination", "") })
        assertEquals(NavigationResult.Failed(1, "result ok, response=false"), trip { it.onResult(Definition.RESULT_OK, "false", "") })
        assertEquals(
            NavigationResult.Failed(-109, "can not arrive"),
            trip {
                it.onStatusUpdate(Definition.STATUS_NAVI_AVOID, "", "")
                it.onError(-109, "can not arrive", "")
            },
        )
        assertTrue(journal.any { it.contains("ERROR_NOT_ESTIMATE") })
        assertTrue(journal.any { it.contains("статус 1018 STATUS_NAVI_AVOID") })
    }

    @Test
    fun `no result from RobotApi - trip ends Failed by the overall timeout with stopNavigation`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs, tripTimeoutMillis = 10_000)
        val trip = async { g.navigateTo("Кафе") }
        advanceTimeBy(9_000); runCurrent()
        assertFalse(trip.isCompleted)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(RobotGateway.CODE_TRIP_TIMEOUT, (trip.await() as NavigationResult.Failed).code)
        verify { api.stopNavigation(any()) }
        assertTrue("next stop does not hang on the dead trip", g.stopNavigationAndWait(100))
    }

    @Test
    fun `RobotApi disconnect during the trip ends it Failed instead of hanging`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs)
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        listener.captured.handleApiDisconnected()
        assertEquals(RobotGateway.CODE_API_DISCONNECTED, (trip.await() as NavigationResult.Failed).code)
    }

    @Test
    fun `RobotApi disabled during the trip ends it Failed`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs)
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        listener.captured.handleApiDisabled()
        assertEquals(RobotGateway.CODE_API_DISCONNECTED, (trip.await() as NavigationResult.Failed).code)
    }

    @Test
    fun `system takes control (onSuspend) during the trip ends it Failed`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs)
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        g.setControlAvailable(false)
        assertEquals(RobotGateway.CODE_CONTROL_SUSPENDED, (trip.await() as NavigationResult.Failed).code)
        assertTrue(journal.any { it.contains("onSuspend") })
    }

    @Test
    fun `chassis busy rc -6 - one retry after a second, then drives`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs, rcs = listOf(-6, 0))
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        verify(exactly = 1) { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), any<ActionListener>()) }
        advanceTimeBy(1_001); runCurrent()
        verify(exactly = 2) { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), any<ActionListener>()) }
        navs.last().onResult(Definition.RESULT_OK, "true", "")
        assertEquals(NavigationResult.Arrived, trip.await())
        assertTrue(journal.any { it.contains("rc=-6") && it.contains("отказ") })
    }

    @Test
    fun `rejected start (negative rc) does not wait for a callback`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs, rcs = listOf(-8))
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        assertEquals(-8, (trip.await() as NavigationResult.Failed).code)
    }

    @Test
    fun `path planning failed status - stopNavigation as the doc advises, outcome Failed not Cancelled`() = runTest {
        val navs = mutableListOf<ActionListener>()
        val g = connected(navs)
        val trip = async { g.navigateTo("Кафе") }
        runCurrent()
        navs.single().onStatusUpdate(1025, "", "")
        verify { api.stopNavigation(any()) }
        navs.single().onResult(Definition.ACTION_RESPONSE_STOP_SUCCESS, "", "")
        assertEquals(1025, (trip.await() as NavigationResult.Failed).code)
    }

    @Test
    fun `not connected - Failed at once, nothing sent`() = runTest {
        val g = gateway()
        assertEquals(RobotGateway.CODE_NOT_CONNECTED, (g.navigateTo("Кафе") as NavigationResult.Failed).code)
        verify(exactly = 0) { api.startNavigation(any<Int>(), any<String>(), any<Double>(), any<Long>(), any<ActionListener>()) }
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
