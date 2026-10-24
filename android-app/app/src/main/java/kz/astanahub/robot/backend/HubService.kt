package kz.astanahub.robot.backend

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Запрос без заголовка Authorization (контракт: `/health` вызывается без токена). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class NoAuth

/** Retrofit-описание эндпоинтов. Пути относительные: baseUrl оканчивается на `/v1/`. */
internal interface HubService {
    @POST("ask")
    suspend fun ask(@Body body: AskRequestDto): Response<AnswerDto>

    @Multipart
    @POST("look")
    suspend fun look(
        @Part image: MultipartBody.Part,
        @Part("meta") meta: RequestBody,
    ): Response<AnswerDto>

    @NoAuth
    @GET("health")
    suspend fun health(): Response<HealthDto>

    @GET("places")
    suspend fun places(@Header("If-None-Match") etag: String?): Response<PlacesDto>

    @GET("quick-questions")
    suspend fun quickQuestions(@Query("lang") lang: String): Response<QuickQuestionsDto>

    @Multipart
    @POST("stt")
    suspend fun stt(
        @Part audio: MultipartBody.Part,
        @Part("meta") meta: RequestBody,
    ): Response<SttResponseDto>

    /** Тело — audio/mpeg; Retrofit буферизует ResponseBody целиком (без @Streaming). */
    @POST("tts")
    suspend fun tts(@Body body: TtsRequestDto): Response<ResponseBody>

    /** `?stream=true`: тело читается по мере прихода (@Streaming — Retrofit не буферизует). */
    @Streaming
    @POST("tts")
    suspend fun ttsStream(@Body body: TtsRequestDto, @Query("stream") stream: Boolean = true): Response<ResponseBody>

    @POST("metrics/turn")
    suspend fun postTurnMetrics(@Body body: TurnMetricsDto): Response<ResponseBody>

    @POST("sessions/{sessionId}/messages")
    suspend fun postMessage(@Path("sessionId") sessionId: String, @Body body: LocalMessageDto): Response<ResponseBody>
}
