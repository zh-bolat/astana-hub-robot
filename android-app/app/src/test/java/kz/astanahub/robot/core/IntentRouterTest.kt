package kz.astanahub.robot.core

import kz.astanahub.robot.core.IntentRouter.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    private fun check(intent: Intent, vararg phrases: String) =
        phrases.forEach { assertEquals(it, intent, IntentRouter.route(it).intent) }

    @Test
    fun `where is in three languages`() = check(
        Intent.WHERE_IS,
        "Где кафе?",
        "А где здесь туалет?",
        "Подскажите, где находится ресепшен",
        "Как пройти в конференц-зал?",
        "Как найти коворкинг",
        "Куда идти на регистрацию?",
        "Асхана қайда?",
        "Әжетхана қай жерде?",
        "Конференц-зал қайда орналасқан?",
        "Ресепшнге қалай барамын?",
        "Where is the cafe?",
        "Where's the restroom",
        "Where can I find the coworking?",
        "How do I get to the conference hall?",
    )

    @Test
    fun `guide to in three languages`() = check(
        Intent.GUIDE_TO,
        "Проводи меня до кафе",
        "Проводите, пожалуйста, в конференц-зал",
        "Отведи меня к ресепшену",
        "Проведите до туалета",
        "Покажи дорогу в коворкинг",
        "Можете меня проводить к кафе?",
        "Асханаға апар",
        "Мені ресепшнге апарыңызшы",
        "Конференц-залға алып барыңыз",
        "Take me to the cafe",
        "Please guide me to the reception",
        "Show me the way to the restroom",
        "Can you lead us to the conference hall?",
    )

    @Test
    fun `where am i`() = check(
        Intent.WHERE_AM_I,
        "Где я?",
        "Где мы сейчас находимся?",
        "Мен қайдамын?",
        "Біз қай жерде?",
        "Where am I?",
        "where are we",
    )

    @Test
    fun `look phrases`() = check(
        Intent.LOOK,
        "Что это?",
        "а что это за комната?",
        "Что ты видишь",
        "Бұл не?",
        "What is this?",
        "what's that",
    )

    @Test
    fun `hub questions go to ask`() = check(
        Intent.ASK,
        "Что такое Astana Hub?",
        "Что это за программа акселерации Tech Garden и как туда попасть?",
        "What is Astana Hub?",
        "Какой пароль от Wi-Fi?",
        "Astana Hub деген не?",
        "Когда ближайший хакатон?",
        "Расскажи про резидентство",
        "Привет!",
    )

    @Test
    fun `subject is stripped of trigger words`() {
        assertEquals("кафе?", IntentRouter.route("Где кафе?").subject)
        assertEquals("Асхана ?", IntentRouter.route("Асхана қайда?").subject)
        assertTrue(IntentRouter.route("Take me to the cafe").subject.contains("cafe"))
        assertTrue(IntentRouter.route("Проводи меня до кафе").subject.contains("кафе"))
    }
}
