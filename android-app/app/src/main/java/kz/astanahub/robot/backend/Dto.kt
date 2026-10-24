package kz.astanahub.robot.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AskRequestDto(
    val question: String,
    val lang: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("place_id") val placeId: String? = null,
)

@Serializable
internal data class LookMetaDto(
    val question: String? = null,
    val lang: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("place_id") val placeId: String? = null,
)

@Serializable
internal data class AnswerDto(
    @SerialName("request_id") val requestId: String? = null,
    val answer: String,
    val phrases: List<String> = emptyList(),
    val lang: String? = null,
    val found: Boolean = true,
    val sources: List<String> = emptyList(),
)

@Serializable
internal data class PlacesDto(val places: List<PlaceDto> = emptyList())

@Serializable
internal data class PlaceDto(
    val id: String,
    @SerialName("map_name") val mapName: String,
    val names: Map<String, String> = emptyMap(),
    val synonyms: List<String> = emptyList(),
    val description: Map<String, String> = emptyMap(),
)

@Serializable
internal data class QuickQuestionsDto(val items: List<QuickQuestionDto> = emptyList())

@Serializable
internal data class QuickQuestionDto(val id: String, val text: String)

@Serializable
internal data class ErrorEnvelopeDto(
    val error: ErrorBodyDto? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
internal data class ErrorBodyDto(val code: String? = null, val message: String? = null)

@Serializable
internal data class SttMetaDto(
    val lang: String? = null,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
internal data class SttResponseDto(
    @SerialName("request_id") val requestId: String? = null,
    val text: String = "",
    val lang: String? = null,
    val confidence: Double? = null,
)

@Serializable
internal data class TtsRequestDto(val text: String, val lang: String)

@Serializable
internal data class HealthDto(val status: String = "", val version: String? = null)

/** Тело `POST /v1/chat`: как у `/ask` плюс `event` (v1.1). */
@Serializable
internal data class ChatRequestDto(
    val question: String,
    val lang: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("place_id") val placeId: String? = null,
    val event: String? = null,
)

/** Тело `POST /v1/sessions/{session_id}/messages` — ответ, который робот дал сам. */
@Serializable
internal data class LocalMessageDto(
    val kind: String,
    val question: String,
    val answer: String,
    val lang: String,
    @SerialName("place_id") val placeId: String? = null,
    val found: Boolean = true,
    val interrupted: Boolean = false,
)
