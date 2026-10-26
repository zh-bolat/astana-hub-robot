package kz.astanahub.robot.robotapi

import android.os.Handler
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.person.PersonApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Голова и люди через SDK: верные вызовы, исключения SDK не роняют приложение. */
class RobotHeadGatewayTest {

    private val api = mockk<RobotApi>(relaxed = true)
    private val handler = mockk<Handler>(relaxed = true)
    private val gateway = RobotHeadGateway(api = { api }, handler = handler)

    @Test
    fun `focus follow starts with face id and reports a lost target`() {
        val listener = slot<ActionListener>()
        every { api.startFocusFollow(any(), any(), any(), any(), capture(listener)) } returns 0
        var lost = 0
        assertTrue(gateway.startFocusFollow(7) { lost++ })
        verify { api.startFocusFollow(any(), 7, 8_000L, 3.0f, any()) }
        listener.captured.onStatusUpdate(Definition.STATUS_TRACK_TARGET_SUCCEED, null, null)
        assertEquals(0, lost)
        listener.captured.onStatusUpdate(Definition.STATUS_GUEST_LOST, null, null)
        assertEquals(1, lost)
        listener.captured.onResult(Definition.ACTION_RESPONSE_STOP_SUCCESS, null, null) // ручная остановка — не потеря
        assertEquals(1, lost)
    }

    @Test
    fun `sdk exceptions do not crash`() {
        every { api.startFocusFollow(any(), any(), any(), any(), any<ActionListener>()) } throws RuntimeException("no chassis")
        every { api.stopFocusFollow(any()) } throws RuntimeException("x")
        every { api.resetHead(any(), any()) } throws RuntimeException("x")
        every { api.moveHead(any(), any(), any(), any(), any(), any<CommandListener>()) } throws RuntimeException("x")
        assertFalse(gateway.startFocusFollow(1) {})
        gateway.stopFocusFollow()
        gateway.resetHead()
        gateway.nod(14)
        gateway.tilt(6)
        gateway.turnTo(30)
        verify(exactly = 0) { handler.postDelayed(any(), any()) }
    }

    @Test
    fun `nod moves the head down relatively and schedules the way back`() {
        gateway.nod(14)
        verify { api.moveHead(any(), "relative", "relative", 0, 14, any()) }
        verify { handler.postDelayed(any(), 450L) }
    }

    @Test
    fun `turn moves the head horizontally to an absolute angle, vertical untouched`() {
        gateway.turnTo(-25)
        verify { api.moveHead(any(), "absolute", "relative", -25, 0, any()) }
    }

    @Test
    fun `persons come from getAllPersons with distance limit`() = runTest {
        val personApi = mockk<PersonApi>(relaxed = true)
        val person = mockk<com.ainirobot.coreservice.client.listener.Person> {
            every { id } returns 3
            every { distance } returns 1.4
        }
        every { personApi.getAllPersons(any<Double>()) } returns listOf(person)
        val list = PersonApiGateway(api = { personApi }).persons.first()
        assertEquals(listOf(kz.astanahub.robot.presence.SeenPerson(3, 1.4)), list)
        verify { personApi.getAllPersons(3.5) }
    }
}
