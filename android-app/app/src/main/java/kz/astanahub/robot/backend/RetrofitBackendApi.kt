package kz.astanahub.robot.backend

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kz.astanahub.robot.chat.ChatEvent
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.chat.OkHttpChatClient
import kz.astanahub.robot.core.Lang
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Invocation
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber

/** Таймауты клиента из раздела «Таймауты и лимиты» контракта. Ретраев нет. */
data class BackendTimeouts(
    val askMillis: Long = 8_000,
    val lookMillis: Long = 12_000,
    val referenceMillis: Long = 5_000,
    val sttMillis: Long = 6_000,
    val ttsMillis: Long = 5_000,
)

/**
 * HTTP-реализация [BackendApi]: Retrofit + OkHttp + kotlinx.serialization.
 * Стриминг SSE (`Accept: text/event-stream`) — шаг 4.4, здесь только JSON.
 */
class RetrofitBackendApi(
    private val baseUrl: String,
    private val token: String,
    private val timeouts: BackendTimeouts = BackendTimeouts(),
    private val client: OkHttpClient = defaultClient(),
    /** Голос TTS из заголовка `X-TTS-Voice` — для сброса кеша фраз при смене голоса на сервере. */
    private val voiceMonitor: TtsVoiceMonitor? = null,
) : BackendApi {

    private val service: HubService = Retrofit.Builder()
        .baseUrl(normalizeBaseUrl(baseUrl))
        .client(client.newBuilder().addInterceptor(authInterceptor()).build())
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()
        .create(HubService::class.java)

    override suspend fun health(): ApiResult<String> =
        call(timeouts.referenceMillis) { service.health() }.map { "${it.status} ${it.version.orEmpty()}".trim() }

    override suspend fun ask(question: String, context: AskContext): ApiResult<Answer> =
        call(timeouts.askMillis) {
            service.ask(
                AskRequestDto(
                    question = clipQuestion(question),
                    lang = context.lang?.code,
                    sessionId = context.sessionId,
                    placeId = context.placeId,
                ),
            )
        }.map { it.toDomain() }

    override suspend fun look(jpeg: ByteArray, question: String?, context: AskContext): ApiResult<Answer> {
        val meta = json.encodeToString(
            LookMetaDto.serializer(),
            LookMetaDto(question?.let(::clipQuestion), context.lang?.code, context.sessionId, context.placeId),
        )
        val image = MultipartBody.Part.createFormData(
            "image",
            "frame.jpg",
            jpeg.toRequestBody(JPEG_MEDIA_TYPE),
        )
        return call(timeouts.lookMillis) {
            service.look(image, meta.toRequestBody(JSON_MEDIA_TYPE))
        }.map { it.toDomain() }
    }

    override suspend fun places(etag: String?): ApiResult<PlacesResult> = guard(timeouts.referenceMillis) {
        val response = service.places(etag)
        val body = response.body()
        when {
            response.code() == 304 -> ApiResult.Success(PlacesResult(null, etag, notModified = true))
            response.isSuccessful && body != null -> ApiResult.Success(
                PlacesResult(
                    places = body.places.map { it.toDomain() },
                    etag = response.headers()["ETag"],
                    notModified = false,
                ),
            )
            else -> ApiResult.Failure(parseError(response))
        }
    }

    override suspend fun quickQuestions(lang: Lang): ApiResult<List<QuickQuestion>> =
        call(timeouts.referenceMillis) { service.quickQuestions(lang.code) }
            .map { dto -> dto.items.map { QuickQuestion(it.id, it.text) } }

    override suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String): ApiResult<SttResult> {
        val meta = json.encodeToString(SttMetaDto.serializer(), SttMetaDto(lang?.code, sessionId))
        val audio = MultipartBody.Part.createFormData("audio", "speech.wav", wav.toRequestBody(WAV_MEDIA_TYPE))
        return call(timeouts.sttMillis) { service.stt(audio, meta.toRequestBody(JSON_MEDIA_TYPE)) }
            .map { SttResult(it.requestId, it.text.trim(), Lang.fromCode(it.lang), it.confidence) }
    }

    override suspend fun tts(text: String, lang: Lang): ApiResult<ByteArray> =
        call(timeouts.ttsMillis, check = { r -> noteVoice(r); rejectFakeSpeech(r) }) { service.tts(TtsRequestDto(text, lang.code)) }
            .map { body -> body.use { it.bytes() } }

    /**
     * Таймаут [BackendTimeouts.ttsMillis] — до заголовков (ошибка провайдера до первых байт приходит обычным ответом);
     * тело читается потом из [TtsStream] (верхняя граница — callTimeout клиента).
     */
    override suspend fun ttsStream(text: String, lang: Lang): ApiResult<TtsStream> =
        guard(timeouts.ttsMillis) {
            val response = service.ttsStream(TtsRequestDto(text, lang.code))
            val body = response.body()
            if (response.isSuccessful) noteVoice(response)
            val rejected = if (response.isSuccessful) rejectFakeSpeech(response) else null
            when {
                rejected != null -> {
                    body?.close()
                    ApiResult.Failure(rejected)
                }
                response.isSuccessful && body != null -> ApiResult.Success(
                    InputTtsStream(
                        body.byteStream(),
                        onClose = { body.close() },
                        fromCache = response.headers()["X-Cache"].equals("hit", ignoreCase = true),
                    ),
                )
                else -> ApiResult.Failure(parseError(response))
            }
        }

    override suspend fun postTurnMetrics(metrics: TurnMetrics): ApiResult<Unit> =
        guard(timeouts.referenceMillis) {
            val dto = TurnMetricsDto(
                sessionId = metrics.sessionId,
                requestId = metrics.requestId,
                lang = metrics.lang?.code,
                mode = metrics.mode,
                sttFinalMs = metrics.sttFinalMs,
                chatFirstEventMs = metrics.chatFirstEventMs,
                firstPhraseMs = metrics.firstPhraseMs,
                ttsFirstByteMs = metrics.ttsFirstByteMs,
                firstAudioMs = metrics.firstAudioMs,
                vadSilenceMs = metrics.vadSilenceMs,
                fallback = metrics.fallback,
                interrupted = metrics.interrupted,
            )
            val response = service.postTurnMetrics(dto)
            response.body()?.close()
            if (response.isSuccessful) ApiResult.Success(Unit) else ApiResult.Failure(parseError(response))
        }

    override fun openLiveStt(lang: Lang?, sessionId: String): LiveSttSession =
        OkHttpLiveSttSession(client, baseUrl, token, lang, sessionId)

    override val supportsLiveStt: Boolean get() = true

    override fun openLive(lang: Lang, sessionId: String): kz.astanahub.robot.live.LiveConnection =
        kz.astanahub.robot.live.OkHttpLiveConnection(client, baseUrl, token, lang, sessionId)

    private val chatClient by lazy { OkHttpChatClient(baseUrl, token, client) }

    override fun chat(request: ChatRequest): Flow<ChatEvent> = chatClient.chat(request)

    override suspend fun postLocalMessage(sessionId: String, message: LocalMessage): ApiResult<Unit> =
        guard(timeouts.referenceMillis) {
            val dto = LocalMessageDto(
                kind = message.kind,
                question = clipQuestion(message.question),
                answer = message.answer.take(MAX_LOCAL_ANSWER_CHARS),
                lang = message.lang.code,
                placeId = message.placeId,
                found = message.found,
                interrupted = message.interrupted,
            )
            val response = service.postMessage(sessionId, dto)
            response.body()?.close()
            if (response.isSuccessful) ApiResult.Success(Unit) else ApiResult.Failure(parseError(response))
        }

    private fun noteVoice(response: Response<*>) {
        voiceMonitor?.seen(response.headers()[TtsVoiceMonitor.HEADER])
    }

    /** Сервер с FakeSpeechProvider отдаёт тишину и `X-Speech-Provider: fake` — считаем TTS недоступным. */
    private fun rejectFakeSpeech(response: Response<*>): BackendError? =
        if (response.headers()[SPEECH_PROVIDER_HEADER].equals("fake", ignoreCase = true)) {
            BackendError(
                ErrorKind.SPEECH_UNAVAILABLE,
                httpStatus = response.code(),
                message = "speech provider is fake",
                requestId = response.headers()["X-Request-Id"],
            )
        } else null

    private suspend fun <T : Any> call(
        timeoutMillis: Long,
        check: (Response<T>) -> BackendError? = { null },
        block: suspend () -> Response<T>,
    ): ApiResult<T> = guard(timeoutMillis) {
        val response = block()
        val body = response.body()
        val rejected = if (response.isSuccessful) check(response) else null
        if (rejected != null) {
            (body as? java.io.Closeable)?.close()
            ApiResult.Failure(rejected)
        } else if (response.isSuccessful && body != null) {
            ApiResult.Success(body)
        } else {
            ApiResult.Failure(parseError(response))
        }
    }

    /** Таймаут клиента + перевод исключений сети/разбора в [BackendError]. */
    private suspend fun <R> guard(timeoutMillis: Long, block: suspend () -> ApiResult<R>): ApiResult<R> =
        try {
            withTimeout(timeoutMillis) { block() }
        } catch (e: TimeoutCancellationException) {
            ApiResult.Failure(BackendError(ErrorKind.TIMEOUT, message = "client timeout ${timeoutMillis}ms"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            ApiResult.Failure(BackendError(ErrorKind.TIMEOUT, message = e.message))
        } catch (e: IOException) {
            Timber.w(e, "backend network error")
            ApiResult.Failure(BackendError(ErrorKind.NETWORK, message = e.message))
        } catch (e: SerializationException) {
            Timber.e(e, "backend bad response")
            ApiResult.Failure(BackendError(ErrorKind.UNKNOWN, message = e.message))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization бросает IllegalArgumentException на кривом JSON.
            Timber.e(e, "backend bad response")
            ApiResult.Failure(BackendError(ErrorKind.UNKNOWN, message = e.message))
        }

    /** Bearer-токен на всех эндпоинтах, кроме помеченных [NoAuth] (`/health`). */
    private fun authInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val noAuth = original.tag(Invocation::class.java)?.method()?.isAnnotationPresent(NoAuth::class.java) == true
        val request = original.newBuilder().apply {
            if (noAuth) removeHeader("Authorization")
            else if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        chain.proceed(request)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        const val SPEECH_PROVIDER_HEADER = "X-Speech-Provider"

        /** Контракт: `question` — 1–500 символов. */
        const val MAX_QUESTION_CHARS = 500

        /** Контракт `/sessions/…/messages`: `answer` — 1–2000 символов. */
        const val MAX_LOCAL_ANSWER_CHARS = 2000

        /** Обрезка до [MAX_QUESTION_CHARS] без разрыва суррогатной пары (эмодзи). */
        fun clipQuestion(question: String): String {
            val q = question.trim()
            if (q.length <= MAX_QUESTION_CHARS) return q
            var end = MAX_QUESTION_CHARS
            if (Character.isHighSurrogate(q[end - 1])) end--
            return q.substring(0, end).trimEnd()
        }

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Верхняя граница; точные лимиты по эндпоинтам — withTimeout в call().
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        /** `http://host:8000/v1` → `http://host:8000/v1/` (Retrofit требует слеш в конце). */
        fun normalizeBaseUrl(url: String): String = url.trim().let { if (it.endsWith("/")) it else "$it/" }

        internal fun parseError(response: Response<*>): BackendError {
            val status = response.code()
            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
            val envelope = raw?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), it) }.getOrNull()
            }
            val code = envelope?.error?.code
            return BackendError(
                kind = kindFor(code, status),
                httpStatus = status,
                serverCode = code,
                message = envelope?.error?.message,
                requestId = envelope?.requestId ?: response.headers()["X-Request-Id"],
                retryAfterSeconds = response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
            )
        }

        internal fun kindFor(code: String?, status: Int): ErrorKind = when (code) {
            "bad_request" -> ErrorKind.BAD_REQUEST
            "unauthorized" -> ErrorKind.UNAUTHORIZED
            "payload_too_large" -> ErrorKind.PAYLOAD_TOO_LARGE
            "rate_limited" -> ErrorKind.RATE_LIMITED
            "llm_unavailable" -> ErrorKind.LLM_UNAVAILABLE
            "speech_unavailable" -> ErrorKind.SPEECH_UNAVAILABLE
            "timeout" -> ErrorKind.TIMEOUT
            else -> when (status) {
                400, 422 -> ErrorKind.BAD_REQUEST
                401, 403 -> ErrorKind.UNAUTHORIZED
                413 -> ErrorKind.PAYLOAD_TOO_LARGE
                429 -> ErrorKind.RATE_LIMITED
                503 -> ErrorKind.LLM_UNAVAILABLE
                504 -> ErrorKind.TIMEOUT
                else -> ErrorKind.UNKNOWN
            }
        }
    }
}

internal fun PlaceDto.toDomain() = Place(id, mapName, names, synonyms, description)

internal fun AnswerDto.toDomain() = Answer(
    requestId = requestId,
    text = answer,
    phrases = phrases.ifEmpty { listOf(answer) },
    lang = Lang.fromCode(lang),
    found = found,
    citations = CitationParser.parse(citations),
)

internal inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}
