package kz.astanahub.robot.core

import java.io.File
import java.util.Date
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    private fun tempDir(): File = createTempDirectory("crash").toFile().also { it.deleteOnExit() }

    @Test
    fun `save then load - header, exception and stack, previous crash is kept`() {
        val dir = tempDir()
        assertNull(CrashReport.load(dir))

        CrashReport.save(dir, Thread.currentThread(), IllegalStateException("first"), "robotos debug 1.0", Date(0))
        CrashReport.save(dir, Thread.currentThread(), RuntimeException("second"), "robotos debug 1.0", Date(0))

        val last = CrashReport.load(dir)!!
        assertTrue(last.startsWith("1970-01-01"))
        assertTrue("сборка=robotos debug 1.0" in last)
        assertTrue("java.lang.RuntimeException: second" in last)
        assertTrue("CrashReportTest" in last) // стек
        val previous = File(File(dir, CrashReport.DIR), CrashReport.PREVIOUS).readText()
        assertTrue("first" in previous)

        CrashReport.clear(dir)
        assertNull(CrashReport.load(dir))
    }

    @Test
    fun `head keeps only the first lines`() {
        val report = (1..50).joinToString("\n") { "line $it" }
        assertEquals(3, CrashReport.head(report, 3).lines().size)
    }
}
