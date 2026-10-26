package kz.astanahub.robot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitLockTest {
    @Test
    fun `exit mode follows pin and flavor`() {
        val hash = DiagPin.hash("2468", "salt")
        assertEquals(ExitMode.PIN, ExitMode.of(DiagAccess.of(hash, isRobot = true)))
        assertEquals(ExitMode.PIN, ExitMode.of(DiagAccess.of(hash, isRobot = false)))
        assertEquals(ExitMode.HIDDEN, ExitMode.of(DiagAccess.of("", isRobot = true)))
        assertEquals(ExitMode.CONFIRM, ExitMode.of(DiagAccess.of("", isRobot = false)))
    }

    @Test
    fun `buffer collects digits and masks them`() {
        val b = PinBuffer()
        "2468".forEach { assertTrue(b.append(it)) }
        assertEquals("2468", b.value)
        assertEquals("●●●●", b.masked())
        assertTrue(b.isComplete)
    }

    @Test
    fun `buffer is incomplete below four digits`() {
        val b = PinBuffer()
        "246".forEach { b.append(it) }
        assertFalse(b.isComplete)
    }

    @Test
    fun `backspace and clear`() {
        val b = PinBuffer()
        "123".forEach { b.append(it) }
        b.backspace()
        assertEquals("12", b.value)
        b.clear()
        assertEquals("", b.value)
        b.backspace() // на пустом ничего не ломается
        assertEquals(0, b.length)
    }

    @Test
    fun `buffer stops at max length`() {
        val b = PinBuffer()
        repeat(PinBuffer.MAX_LENGTH) { assertTrue(b.append('1')) }
        assertFalse(b.append('2'))
        assertEquals(PinBuffer.MAX_LENGTH, b.length)
    }

    @Test
    fun `latin letters are accepted in lower case`() {
        val b = PinBuffer()
        "K7M2".forEach { assertTrue(b.append(it)) }
        assertEquals("k7m2", b.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `symbols are rejected`() {
        PinBuffer().append('#')
    }

    @Test
    fun `letter pin is checked without regard to case`() {
        val store = object : LockStore {
            override var failedAttempts = 0
            override var lockedUntilMillis = 0L
        }
        val guard = DiagPinGuard(DiagPin.hash("k7m2", "salt"), "salt", store, now = { 1_000L })
        assertEquals(DiagPinGuard.Result.Ok, guard.check("K7M2"))
        assertEquals(DiagPinGuard.Result.Ok, guard.check("k7m2"))
    }

    @Test
    fun `wrong exit pin keeps the app and shares the attempt counter`() {
        val store = object : LockStore {
            override var failedAttempts = 0
            override var lockedUntilMillis = 0L
        }
        val guard = DiagPinGuard(DiagPin.hash("2468", "salt"), "salt", store, now = { 1_000L })
        assertEquals(DiagPinGuard.Result.Wrong(4), guard.check("1111"))
        assertEquals(1, store.failedAttempts)
        assertEquals(DiagPinGuard.Result.Ok, guard.check("2468"))
        assertEquals(0, store.failedAttempts)
    }
}
