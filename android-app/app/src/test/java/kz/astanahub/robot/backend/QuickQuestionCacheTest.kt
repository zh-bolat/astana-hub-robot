package kz.astanahub.robot.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickQuestionCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `saved per language and survives a new instance, empty list is not saved`() = runBlocking {
        val items = listOf(QuickQuestion("q1", "Что такое Astana Hub?"), QuickQuestion("q2", "Где кафе?"))
        FileQuickQuestionCache(tmp.root, Dispatchers.Unconfined).save(Lang.RU, items)
        FileQuickQuestionCache(tmp.root, Dispatchers.Unconfined).save(Lang.RU, emptyList())

        val reopened = FileQuickQuestionCache(tmp.root, Dispatchers.Unconfined)
        assertEquals(items, reopened.load(Lang.RU))
        assertEquals(emptyList<QuickQuestion>(), reopened.load(Lang.KK))
    }

    @Test
    fun `broken file reads as empty`() = runBlocking {
        java.io.File(tmp.root, "quick_en.json").writeText("{not json")
        assertEquals(emptyList<QuickQuestion>(), FileQuickQuestionCache(tmp.root, Dispatchers.Unconfined).load(Lang.EN))
    }
}
