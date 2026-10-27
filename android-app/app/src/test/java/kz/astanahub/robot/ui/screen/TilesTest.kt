package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Плитки экрана 2, язык и подпись-источник. */
class TilesTest {

    private val ru = TileTexts(
        where = "Где что находится", events = "Мероприятия", eventsQuestion = "Какие ближайшие мероприятия?",
        about = "Об Astana Hub", aboutQuestion = "Расскажи про Astana Hub", wifi = "Wi-Fi и сервисы",
        wifiQuestion = "Как подключиться к Wi-Fi?", faq = "Частые вопросы", look = "Что это?",
    )

    @Test
    fun `home tiles - the six topics of the prototype, each leads to our logic`() {
        val tiles = Tiles.home(ru)
        assertEquals(
            listOf("Где что находится", "Мероприятия", "Об Astana Hub", "Wi-Fi и сервисы", "Частые вопросы", "Что это?"),
            tiles.map { it.label },
        )
        assertEquals(TileAction.Where, tiles[0].action)
        assertEquals(TileAction.Ask("Какие ближайшие мероприятия?", display = "Мероприятия"), tiles[1].action)
        assertEquals(TileAction.Ask("Расскажи про Astana Hub", display = "Об Astana Hub"), tiles[2].action)
        assertEquals(TileAction.Ask("Как подключиться к Wi-Fi?", display = "Wi-Fi и сервисы"), tiles[3].action)
        assertEquals(TileAction.Faq, tiles[4].action)
        assertEquals(TileAction.Look, tiles[5].action)
        assertEquals(listOf("where", "events", "about", "wifi", "faq", "look"), tiles.map { it.key })
    }

    @Test
    fun `glyphs as the prototype - place, event, info, wifi, help, camera`() {
        assertEquals(
            listOf(TileGlyph.PLACE, TileGlyph.EVENT, TileGlyph.INFO, TileGlyph.WIFI, TileGlyph.FAQ, TileGlyph.LOOK),
            Tiles.home(ru).map { it.glyph },
        )
        assertEquals(TileGlyph.CHAT, Tiles.glyphFor("Расскажи анекдот"))
    }

    @Test
    fun `languages`() {
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
