package kz.astanahub.robot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceFilterTest {

    @Test
    fun `stop commands in three languages`() {
        listOf(
            "Стоп", "стоп!", "Стоп, пожалуйста", "Робот, хватит", "Остановись", "Замолчи", "стоп стоп",
            "Тоқта", "тоқтаңыз", "Жетеді", "Stop", "stop please", "Enough", "Be quiet", "shut up",
        ).forEach { assertTrue(it, UtteranceFilter.isStopCommand(it)) }
    }

    @Test
    fun `phrases that only contain a stop word are not commands`() {
        listOf(
            "Где остановка автобуса?", "стой здесь и жди", "How do I stop the projector?", "", "пожалуйста",
            "Не останавливайся, расскажи ещё",
        ).forEach { assertFalse(it, UtteranceFilter.isStopCommand(it)) }
    }

    @Test
    fun `interjections and short replies are not substantial`() {
        listOf("угу", "ага", "да", "мм", "да да да", "ну ага", "ок", "yeah", "иә", "спасибо большое").forEach {
            assertFalse(it, UtteranceFilter.isSubstantial(it))
        }
    }

    @Test
    fun `three or more real words are substantial`() {
        listOf("А где здесь кафе?", "Кафе қайда орналасқан?", "Where is the cafe", "угу а где туалет находится").forEach {
            assertTrue(it, UtteranceFilter.isSubstantial(it))
        }
    }
}
