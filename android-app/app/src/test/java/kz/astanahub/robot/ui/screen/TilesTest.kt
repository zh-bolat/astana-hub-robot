package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Плитки экрана 2, язык и подпись-источник. */
class TilesTest {

    private val questions = listOf(
        QuickQuestion("cafe", "Где кафе?"),
        QuickQuestion("wifi", "Какой пароль от Wi-Fi?"),
        QuickQuestion("events", "Какие мероприятия сегодня?"),
        QuickQuestion("hub", "Что такое Astana Hub?"),
        QuickQuestion("toilet", "Где туалет?"),
    )

    @Test
    fun `home tiles - four questions, then all questions and look, as on the old screen`() {
        val tiles = Tiles.home(questions, "Частые вопросы", "Что это?")
        assertEquals(6, tiles.size)
        assertEquals(listOf("Где кафе?", "Какой пароль от Wi-Fi?", "Какие мероприятия сегодня?", "Что такое Astana Hub?"), tiles.take(4).map { it.label })
        assertEquals(TileAction.Ask("Где кафе?"), tiles[0].action)
        assertEquals(TileAction.Faq, tiles[4].action)
        assertEquals(TileAction.Look, tiles[5].action)
        assertEquals(listOf("q:cafe", "q:wifi", "q:events", "q:hub", "faq", "look"), tiles.map { it.key })
    }

    @Test
    fun `glyphs by meaning - place, wifi, event, info`() {
        val tiles = Tiles.home(questions, "F", "L")
        assertEquals(listOf(TileGlyph.PLACE, TileGlyph.WIFI, TileGlyph.EVENT, TileGlyph.INFO, TileGlyph.FAQ, TileGlyph.LOOK), tiles.map { it.glyph })
        assertEquals(TileGlyph.CHAT, Tiles.glyphFor("Расскажи анекдот"))
    }

    @Test
    fun `few questions from the server - no empty tiles`() {
        val tiles = Tiles.home(questions.take(1), "F", "L")
        assertEquals(listOf("Где кафе?", "F", "L"), tiles.map { it.label })
        assertEquals(listOf("F", "L"), Tiles.home(emptyList(), "F", "L").map { it.label })
    }

    @Test
    fun `labels follow the language (passed in localized)`() {
        val kk = Tiles.home(emptyList(), "Жиі қойылатын сұрақтар", "Бұл не?")
        assertEquals(listOf("Жиі қойылатын сұрақтар", "Бұл не?"), kk.map { it.label })
        assertEquals(listOf(Lang.RU, Lang.KK, Lang.EN), Tiles.languages.map { it.first })
        assertEquals(listOf("RU", "KZ", "EN"), Tiles.languages.map { it.second })
    }

    @Test
    fun `source caption - one, several without repeats, none`() {
        val one: (String) -> String = { "Источник: $it" }
        val many: (String) -> String = { "Источники: $it" }
        assertNull(Tiles.sourceCaption(emptyList(), one, many))
        assertEquals("Источник: Hub Space", Tiles.sourceCaption(listOf(Citation("a", "document", "Hub Space")), one, many))
        val list = listOf(
            Citation("a", "document", "Hub Space"),
            Citation("b", "document", "Hub Space"),
            Citation("c", "fact", "Wi-Fi"),
            Citation("d", "place", "Кафе"),
            Citation("e", "fact", "Парковка"),
        )
        assertEquals("Источники: Hub Space · Wi-Fi · Кафе", Tiles.sourceCaption(list, one, many))
    }
}
