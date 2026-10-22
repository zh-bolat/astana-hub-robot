package kz.astanahub.robot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodEmojiTest {

    @Test
    fun picksEmojiByKeywords() {
        assertEquals("📶", MoodEmoji.forText("Сеть AstanaHub_Guest, пароль на ресепшене."))
        assertEquals("☕", MoodEmoji.forText("Coffee point is on the first floor"))
        assertEquals("🤔", MoodEmoji.forText("Извините, я не расслышал."))
        assertEquals("🎉", MoodEmoji.forText("Ближайшее мероприятие — в пятницу"))
    }

    @Test
    fun firstRuleWins() {
        // «извин» стоит раньше «этаж»: сначала извинение.
        assertEquals("🤔", MoodEmoji.forText("Извините, на каком этаже?"))
    }

    @Test
    fun fallsBackToSmile() {
        assertEquals(MoodEmoji.DEFAULT, MoodEmoji.forText("Astana Hub — технопарк"))
        assertEquals(MoodEmoji.DEFAULT, MoodEmoji.forText(null))
    }
}
