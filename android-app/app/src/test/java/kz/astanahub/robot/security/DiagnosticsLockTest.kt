package kz.astanahub.robot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLockTest {

    private class MemoryStore : LockStore {
        override var failedAttempts = 0
        override var lockedUntilMillis = 0L
    }

    private val salt = "kz.astanahub.robot/diag-pin/v1"
    private val pinHash = DiagPin.hash("2468", salt)
    private var clock = 1_000_000L
    private val store = MemoryStore()
    private fun guard() = DiagPinGuard(pinHash, salt, store) { clock }

    @Test
    fun `hash matches build script algorithm`() {
        // Эталон: python sha256("salt:pin"), затем ещё 9999 раз sha256 — как diagPinHash в build.gradle.kts.
        assertEquals("cb017d098fe8f0025262398405c85b7080175a2ea0641807435fa5b4b6aeb9e9", pinHash)
    }

    @Test
    fun `correct pin unlocks and resets counter`() {
        val g = guard()
        assertEquals(DiagPinGuard.Result.Wrong(4), g.check("0000"))
        assertEquals(DiagPinGuard.Result.Ok, g.check("2468"))
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `wrong pin counts down attempts`() {
        val g = guard()
        assertEquals(DiagPinGuard.Result.Wrong(4), g.check("1"))
        assertEquals(DiagPinGuard.Result.Wrong(3), g.check("24680"))
        assertEquals(DiagPinGuard.Result.Wrong(2), g.check(""))
    }

    @Test
    fun `five wrong attempts lock for five minutes even for correct pin`() {
        val g = guard()
        repeat(4) { g.check("9999") }
        assertEquals(DiagPinGuard.Result.Locked(DiagPinGuard.LOCK_MILLIS), g.check("9999"))
        clock += 60_000
        val locked = g.check("2468")
        assertTrue(locked is DiagPinGuard.Result.Locked)
        assertEquals(4 * 60_000L, (locked as DiagPinGuard.Result.Locked).remainingMillis)
    }

    @Test
    fun `lock survives restart and expires`() {
        repeat(5) { guard().check("9999") }
        // «Перезапуск»: новый guard с тем же хранилищем.
        val afterRestart = guard()
        assertTrue(afterRestart.lockRemainingMillis() > 0)
        assertTrue(afterRestart.check("2468") is DiagPinGuard.Result.Locked)
        clock += DiagPinGuard.LOCK_MILLIS
        assertEquals(0L, afterRestart.lockRemainingMillis())
        assertEquals(DiagPinGuard.Result.Ok, afterRestart.check("2468"))
    }

    @Test
    fun `clock moved back does not extend lock beyond five minutes`() {
        repeat(5) { guard().check("9999") }
        clock -= 24 * 3_600_000L
        assertEquals(DiagPinGuard.LOCK_MILLIS, guard().lockRemainingMillis())
    }

    @Test
    fun `empty hash never accepts`() {
        val g = DiagPinGuard("", salt, MemoryStore()) { clock }
        assertFalse(g.check("") is DiagPinGuard.Result.Ok)
    }

    @Test
    fun `access depends on pin and flavor`() {
        assertEquals(DiagAccess.PIN, DiagAccess.of(pinHash, isRobot = true))
        assertEquals(DiagAccess.PIN, DiagAccess.of(pinHash, isRobot = false))
        assertEquals(DiagAccess.DISABLED, DiagAccess.of("", isRobot = true))
        assertEquals(DiagAccess.OPEN, DiagAccess.of("", isRobot = false))
    }

    @Test
    fun `session expires after idle and on lock`() {
        var t = 0L
        val s = DiagSession { t }
        assertFalse(s.isUnlocked())
        s.unlock()
        t += DiagSession.IDLE_MILLIS - 1
        assertTrue(s.isUnlocked())
        s.touch()
        t += DiagSession.IDLE_MILLIS - 1
        assertTrue(s.isUnlocked())
        t += 1
        assertFalse(s.isUnlocked())
        s.touch() // касание после истечения не воскрешает
        assertFalse(s.isUnlocked())
        s.unlock(); s.lock()
        assertFalse(s.isUnlocked())
    }

    @Test
    fun `gate combines access and session`() {
        val s = DiagSession { 0L }
        assertTrue(DiagnosticsGate(DiagAccess.OPEN, guard(), s).canEnter())
        assertFalse(DiagnosticsGate(DiagAccess.DISABLED, guard(), s.also { it.unlock() }).canEnter())
        s.lock()
        assertFalse(DiagnosticsGate(DiagAccess.PIN, guard(), s).canEnter())
        s.unlock()
        assertTrue(DiagnosticsGate(DiagAccess.PIN, guard(), s).canEnter())
    }
}
