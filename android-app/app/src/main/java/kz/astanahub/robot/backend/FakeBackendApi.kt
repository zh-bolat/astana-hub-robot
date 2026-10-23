package kz.astanahub.robot.backend

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatRequest
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

    @Volatile private var lastFrameBytes: Int? = null

    /**
     * Агент-заглушка для эмулятора: приветствие на `visitor_arrived`, «где X» → `where_is`, «проводи» → `guide_to`,
     * «что это» → `look` (ждёт кадр до 8 с), остальное — шаблонный ответ.
     */
    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        val turnId = "trn_fake_${System.nanoTime()}"
        emit(ChatEvent.Turn(turnId, "req_fake"))
        delay(latencyMillis / 2)
        val lang = request.lang ?: Lang.RU
        val text = request.text.lowercase()
        val place = FAKE_PLACES.firstOrNull { p ->
            (p.names.values + p.synonyms + p.mapName).any { it.isNotBlank() && text.contains(it.lowercase()) }
        }
        when {
            request.event == ChatRequest.EVENT_VISITOR_ARRIVED -> {
                emit(ChatEvent.Emotion("happy"))
                emit(ChatEvent.Phrase(fakeText(lang, "Здравствуйте! Я помощник Astana Hub. Чем помочь?",
                    "Сәлеметсіз бе! Мен Astana Hub көмекшісімін. Қалай көмектесейін?",
                    "Hello! I'm the Astana Hub assistant. How can I help?")))
            }
            place != null && GUIDE_WORDS.any { text.contains(it) } -> {
                emit(ChatEvent.Emotion("excited"))
                emit(ChatEvent.Phrase(fakeText(lang, "Пойдёмте, провожу!", "Жүріңіз, бастап барамын!", "Let's go, follow me!")))
                emit(ChatEvent.Action(ChatEvent.Action.GUIDE_TO, mapOf("place_id" to place.id, "map_name" to place.mapName)))
            }
            place != null -> {
                emit(ChatEvent.Action(ChatEvent.Action.WHERE_IS, mapOf("place_id" to place.id, "map_name" to place.mapName)))
            }
            LOOK_WORDS.any { text.contains(it) } -> {
                lastFrameBytes = null
                emit(ChatEvent.Emotion("thinking"))
                emit(ChatEvent.Action(ChatEvent.Action.LOOK, emptyMap()))
                var waited = 0L
                while (lastFrameBytes == null && waited < 8_000) { delay(100); waited += 100 }
                emit(ChatEvent.Emotion("surprised"))
                val kb = lastFrameBytes?.let { it / 1024 }
                emit(ChatEvent.Phrase(
                    if (kb == null) fakeText(lang, "Кадр не пришёл, но я стараюсь!", "Кадр келмеді.", "The frame did not arrive.")
                    else fakeText(lang, "Вижу тестовый кадр на $kb килобайт.", "$kb килобайттық кадрды көріп тұрмын.", "I see a $kb KB test frame."),
                ))
            }
            else -> {
                emit(ChatEvent.Emotion("thinking"))
                delay(latencyMillis)
                emit(ChatEvent.Emotion("happy"))
                splitSentences(fakeText(lang,
                    "Это ответ агента-заглушки на: «${request.text}». Настоящий ответ даст сервер.",
                    "Бұл «${request.text}» сұрағына агент-тұйықтың жауабы.",
                    "This is a stub agent answer to: \"${request.text}\".")).forEach {
                    emit(ChatEvent.Phrase(it))
                    delay(latencyMillis / 3)
                }
            }
        }
        emit(ChatEvent.Done("req_fake", lang, emptyList()))
    }

    override suspend fun chatFrame(turnId: String, jpeg: ByteArray): ApiResult<Unit> {
        lastFrameBytes = jpeg.size
        return ApiResult.Success(Unit)
    }

    override suspend fun postLocalMessage(sessionId: String, message: LocalMessage): ApiResult<Unit> =
        ApiResult.Success(Unit)

    private fun fakeText(lang: Lang, ru: String, kk: String, en: String) = when (lang) {
        Lang.RU -> ru
        Lang.KK -> kk
        Lang.EN -> en
    }

    companion object {
        private val GUIDE_WORDS = listOf("провод", "отвед", "бастап", "take me", "guide")
        private val LOOK_WORDS = listOf("что это", "бұл не", "what is this", "что видишь")

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
