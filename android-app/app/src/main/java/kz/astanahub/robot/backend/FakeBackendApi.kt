package kz.astanahub.robot.backend

import kotlinx.coroutines.delay
import kz.astanahub.robot.core.Lang

/**
 * Заглушка backend для эмулятора без сервера (BACKEND_URL пуст) и для тестов.
 * Отвечает шаблонно, с небольшой задержкой, чтобы был виден индикатор «думаю».
 */
class FakeBackendApi(private val latencyMillis: Long = 600) : BackendApi {

    override suspend fun health(): ApiResult<String> = ApiResult.Success("fake")


    override suspend fun ask(question: String, context: AskContext): ApiResult<Answer> {
        delay(latencyMillis)
        val lang = context.lang ?: Lang.RU
        val text = when (lang) {
            Lang.RU -> "Это тестовый ответ заглушки на вопрос: «$question». Настоящий ответ придёт с сервера."
            Lang.KK -> "Бұл «$question» сұрағына сынақ жауабы. Нақты жауап серверден келеді."
            Lang.EN -> "This is a stub answer to: \"$question\". The real answer will come from the server."
        }
        return ApiResult.Success(Answer("fake", text, splitSentences(text), lang, found = true))
    }

    override suspend fun look(jpeg: ByteArray, question: String?, context: AskContext): ApiResult<Answer> {
        delay(latencyMillis)
        val lang = context.lang ?: Lang.RU
        val text = when (lang) {
            Lang.RU -> "Я вижу тестовый кадр размером ${jpeg.size / 1024} килобайт. Это заглушка камеры."
            Lang.KK -> "Мен ${jpeg.size / 1024} килобайттық сынақ кадрын көріп тұрмын. Бұл камера тұйығы."
            Lang.EN -> "I see a ${jpeg.size / 1024} KB test frame. This is a camera stub."
        }
        return ApiResult.Success(Answer("fake", text, splitSentences(text), lang, found = true))
    }

    override suspend fun places(etag: String?): ApiResult<PlacesResult> =
        ApiResult.Success(PlacesResult(FAKE_PLACES, etag = "fake-1", notModified = etag == "fake-1"))

    override suspend fun quickQuestions(lang: Lang): ApiResult<List<QuickQuestion>> {
        delay(latencyMillis / 3)
        val items = when (lang) {
            Lang.RU -> listOf("Что такое Astana Hub?", "Какой пароль от Wi-Fi?", "Где кафе?", "Где ресепшен?", "Что это?")
            Lang.KK -> listOf("Astana Hub деген не?", "Wi-Fi құпиясөзі қандай?", "Кафе қайда?", "Ресепшн қайда?", "Бұл не?")
            Lang.EN -> listOf("What is Astana Hub?", "What is the Wi-Fi password?", "Where is the cafe?", "Where is reception?", "What is this?")
        }
        return ApiResult.Success(items.mapIndexed { i, t -> QuickQuestion("q$i", t) })
    }

    /** Без сервера распознавать и синтезировать нечем — как 503 speech_unavailable. */
    override suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String): ApiResult<SttResult> =
        ApiResult.Failure(BackendError(ErrorKind.SPEECH_UNAVAILABLE, message = "fake backend"))

    override suspend fun tts(text: String, lang: Lang): ApiResult<ByteArray> =
        ApiResult.Failure(BackendError(ErrorKind.SPEECH_UNAVAILABLE, message = "fake backend"))

    companion object {
        /** Совпадают с точками FakeRobotGateway (map_name). */
        val FAKE_PLACES = listOf(
            Place("reception", "Ресепшен", mapOf("ru" to "Ресепшен", "kk" to "Ресепшн", "en" to "Reception"),
                listOf("стойка", "регистрация", "администратор", "тіркеу", "front desk"), emptyMap()),
            Place("cafe", "Кафе", mapOf("ru" to "Кафе", "kk" to "Кафе", "en" to "Cafe"),
                listOf("кафешка", "асхана", "столовая", "кофе", "поесть", "coffee", "canteen"), emptyMap()),
            Place("coworking", "Коворкинг", mapOf("ru" to "Коворкинг", "kk" to "Коворкинг", "en" to "Coworking"),
                listOf("опенспейс", "open space", "рабочее место"), emptyMap()),
            Place("hall", "Конференц-зал", mapOf("ru" to "Конференц-зал", "kk" to "Конференц-зал", "en" to "Conference hall"),
                listOf("зал", "конференц зал", "сахна", "event hall", "auditorium"), emptyMap()),
            Place("wc", "Туалет", mapOf("ru" to "Туалет", "kk" to "Әжетхана", "en" to "Restroom"),
                listOf("уборная", "санузел", "дәретхана", "toilet", "wc", "bathroom"), emptyMap()),
        )
    }

    private fun splitSentences(text: String) = text.split(Regex("""(?<=[.!?])\s+"""))
}

/** На роботе без BACKEND_URL — честная ошибка «нет связи» вместо заглушки. */
class UnconfiguredBackendApi : BackendApi {
    private val failure = ApiResult.Failure(BackendError(ErrorKind.NOT_CONFIGURED, message = "BACKEND_URL is empty"))
    override suspend fun ask(question: String, context: AskContext) = failure
    override suspend fun look(jpeg: ByteArray, question: String?, context: AskContext) = failure
    override suspend fun places(etag: String?) = failure
    override suspend fun quickQuestions(lang: Lang) = failure
    override suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String) = failure
    override suspend fun tts(text: String, lang: Lang) = failure
}
