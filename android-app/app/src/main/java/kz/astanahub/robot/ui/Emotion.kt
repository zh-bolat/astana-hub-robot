package kz.astanahub.robot.ui

/** Выражение лица: из `emotion` агента (`/v1/chat`) и из событий на роботе (подошёл человек, «спасибо», ошибка). */
enum class Emotion(val code: String) {
    NEUTRAL("neutral"),
    HAPPY("happy"),
    THINKING("thinking"),
    SURPRISED("surprised"),
    SAD("sad"),
    EXCITED("excited"),
    ;

    companion object {
        /** Неизвестное имя → null (лицо не меняем). */
        fun fromName(name: String?): Emotion? = entries.firstOrNull { it.code.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * Режим экрана:
 * SLEEP — «спящее» лицо (никого нет), FACE — лицо-тамагочи на весь экран и крупные субтитры (разговор),
 * MENU — прежний экран с плитками, ответом и частыми вопросами (кнопкой или жестом из FACE).
 */
enum class ScreenMode { SLEEP, FACE, MENU }

/**
 * Тамагочи: настроение по событиям на роботе (задача 16), когда агент эмоцию не прислал.
 * Возвращает null — событие настроение не меняет.
 */
object MoodRules {
    private val praise = Regex("спасибо|благодар|молодец|класс|супер|рахмет|керемет|thank|great|awesome|cool")
    private val greeting = Regex("привет|здравств|сәлем|салем|hello|hi\\b")

    /** Реплика посетителя: похвала → радость, приветствие → восторг. */
    fun forUtterance(text: String): Emotion? {
        val t = text.lowercase()
        return when {
            praise.containsMatchIn(t) -> Emotion.HAPPY
            greeting.containsMatchIn(t) -> Emotion.EXCITED
            else -> null
        }
    }

    /** Подошёл человек. */
    val visitorArrived: Emotion = Emotion.EXCITED

    /** Нет связи / ошибка сервера / не понял. */
    val trouble: Emotion = Emotion.SAD

    /** «Что это?» — смотрит. */
    val looking: Emotion = Emotion.SURPRISED
}
