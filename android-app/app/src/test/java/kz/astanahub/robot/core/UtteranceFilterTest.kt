package kz.astanahub.robot.core

import org.junit.Assert.assertEquals
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

    // --- шум вместо реплики (во всех фазах) ---

    private val places = NoiseContext(placeWords = setOf("кафе", "ресепшен", "ресепшн", "reception", "коворкинг", "асхана"))

    @Test
    fun `noise table - not sent`() {
        listOf(
            // реальные логи робота
            "Ну…", "Смеётся.", "М-м-м, не-не-не, да ну его!", "Да, я.",
            // междометия RU / KZ / EN, повторы, дефисы, многоточия
            "ну", "Ну-у-у...", "м", "мм", "Мммм…", "угу", "Ага.", "э", "Э-э-э", "хм", "Хмм?", "ой", "а", "А?",
            "ага-ага", "угу угу", "Ой-ой-ой!", "Ха-ха-ха", "хахаха", "иә-иә", "Әй", "Апырай",
            "uh", "um", "Hmm.", "Uh-huh", "Hahaha", "oh", "Mm-hmm",
            // пометки распознавания
            "(смеётся)", "[музыка]", "(неразборчиво)", "[Music]", "(laughs)", "*кашляет*", "♪♪", "[аплодисменты] (смех)",
            // только знаки / цифры
            "...", "?!", "123", "1 2 3", "—",
            // одно слово вне белого списка
            "Что?", "Кофе", "Слушай", "Окно", "where", "Нормально",
            // служебные слова
            "вот это", "ну вот", "и так", "да ну его",
            // да / нет без вопроса робота — шум
            "да", "Нет.", "иә", "жоқ", "yes", "No", "хорошо", "ок",
            "",
        ).forEach { assertTrue("«$it» — шум", UtteranceFilter.isNoise(it, places)) }
    }

    @Test
    fun `meaningful short replies - sent`() {
        listOf(
            "Привет", "Привет!", "Сәлем", "Сәлеметсіз бе", "Hello", "hi", "Спасибо", "Рахмет", "Thanks", "Thank you",
            "Пока", "Стоп", "Тоқта", "Stop", "Помогите",
            "Где кафе?", "Кафе қайда?", "Where is the cafe?", "Ну, где туалет?", "Что это?", "Бұл не?", "What is this?",
            "Расскажи про Astana Hub", "Как попасть на мероприятие", "Привет робот",
            "(смеётся) где кафе?", "Ну… а где ресепшен?",
        ).forEach { assertFalse("«$it» — осмысленно", UtteranceFilter.isNoise(it, places)) }
    }

    @Test
    fun `yes and no are answers only right after the robot asked a question`() {
        val asked = places.copy(robotAskedQuestion = true)
        listOf("да", "Да, я.", "Нет", "иә", "Иә-иә", "жоқ", "Yes", "no", "Yeah", "Хорошо", "Да, давайте", "Ок").forEach {
            assertFalse("«$it» после вопроса", UtteranceFilter.isNoise(it, asked))
            assertTrue("«$it» без вопроса", UtteranceFilter.isNoise(it, places))
        }
        // междометие и после вопроса — шум
        listOf("Ну…", "м-м", "(смеётся)", "угу").forEach { assertTrue(it, UtteranceFilter.isNoise(it, asked)) }
    }

    @Test
    fun `place name in one word is always meaningful`() {
        listOf("Кафе", "кафе?", "Ресепшен", "Ресепшн", "Reception", "Коворкинг", "Асхана").forEach {
            assertFalse(it, UtteranceFilter.isNoise(it, places))
        }
        assertTrue("без списка мест одно слово — шум", UtteranceFilter.isNoise("Кафе", NoiseContext()))
    }

    @Test
    fun `noise reasons for the log`() {
        assertEquals(NoiseReason.MARKS_ONLY, UtteranceFilter.noiseReason("Смеётся."))
        assertEquals(NoiseReason.MARKS_ONLY, UtteranceFilter.noiseReason("[музыка]"))
        assertEquals(NoiseReason.INTERJECTION, UtteranceFilter.noiseReason("Ну…"))
        assertEquals(NoiseReason.ACK_WITHOUT_QUESTION, UtteranceFilter.noiseReason("Да, я."))
        assertEquals(NoiseReason.SINGLE_WORD, UtteranceFilter.noiseReason("Слушай"))
        assertEquals(NoiseReason.EMPTY, UtteranceFilter.noiseReason("123"))
        assertEquals(null, UtteranceFilter.noiseReason("Где кафе?"))
    }
}
