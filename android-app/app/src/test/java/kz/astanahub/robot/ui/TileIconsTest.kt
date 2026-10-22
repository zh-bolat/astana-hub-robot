package kz.astanahub.robot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileIconsTest {

    @Test
    fun mapsQuestionTextToIcon() {
        assertEquals(TileIcons.Kind.WIFI, TileIcons.kindFor("Как подключиться к Wi-Fi?"))
        assertEquals(TileIcons.Kind.EVENT, TileIcons.kindFor("Какие мероприятия сегодня?"))
        assertEquals(TileIcons.Kind.PLACE, TileIcons.kindFor("Где кофе?"))
        assertEquals(TileIcons.Kind.INFO, TileIcons.kindFor("What is Astana Hub?"))
        assertEquals(TileIcons.Kind.CHAT, TileIcons.kindFor("Можно ли здесь работать?"))
    }
}
