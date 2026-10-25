package kz.astanahub.robot.robot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Журнал навигации для экрана диагностики: порядок, свёртка повторов, предел записей. */
class NavJournalTest {

    @Before
    fun setUp() {
        NavJournal.clear()
        NavJournal.clock = { 0L }
    }

    @After
    fun tearDown() {
        NavJournal.clock = System::currentTimeMillis
        NavJournal.clear()
    }

    @Test
    fun `entries in order, identical consecutive lines collapse with a counter`() {
        NavJournal.log("запрос")
        NavJournal.log("статус 1018")
        NavJournal.log("статус 1018")
        NavJournal.log("итог Arrived")
        assertEquals(listOf("запрос", "статус 1018", "итог Arrived"), NavJournal.texts())
        assertTrue(NavJournal.entries.value[1].format().endsWith("статус 1018 (×2)"))
    }

    @Test
    fun `keeps only the last MAX_ENTRIES`() {
        repeat(NavJournal.MAX_ENTRIES + 50) { NavJournal.log("строка $it") }
        assertEquals(NavJournal.MAX_ENTRIES, NavJournal.entries.value.size)
        assertEquals("строка 50", NavJournal.texts().first())
    }
}
