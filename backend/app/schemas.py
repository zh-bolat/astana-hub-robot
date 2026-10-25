"""Pydantic-схемы по docs/api-contract.md (v1)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

LangField = Literal["ru", "kk", "en"]


def _strip_required(v: str) -> str:
    v = v.strip()
    if not v:
        raise ValueError("must not be blank")
    return v


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    version: str


class AskRequest(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "question": "Где проходит регистрация на хакатон?",
                "lang": "ru",
                "session_id": "6f1c2a9e-7d4b-4c1a-9f3e-2b8d5e6a7c10",
                "place_id": "reception",
            }
        }
    )

    question: str = Field(min_length=1, max_length=500)
    lang: LangField | None = None
    session_id: str = Field(min_length=1, max_length=64)
    place_id: str | None = Field(default=None, max_length=64)

    @field_validator("question", "session_id")
    @classmethod
    def _not_blank(cls, v: str) -> str:
        return _strip_required(v)


class ChatRequest(AskRequest):
    """Запрос /v1/chat: как у /ask плюс `event` (v1.1). С visitor_arrived `question` может быть пустым."""

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "question": "Проводи меня в кафе",
                "lang": "ru",
                "session_id": "6f1c2a9e-7d4b-4c1a-9f3e-2b8d5e6a7c10",
                "place_id": "reception",
                "event": None,
            }
        }
    )

    question: str = Field(default="", max_length=500)
    event: Literal["visitor_arrived"] | None = None

    # Переопределяет одноимённый валидатор AskRequest: пустой question проверяется ниже
    @field_validator("session_id")
    @classmethod
    def _not_blank(cls, v: str) -> str:
        return _strip_required(v)

    @field_validator("question")
    @classmethod
    def _strip_question(cls, v: str) -> str:
        return v.strip()

    @model_validator(mode="after")
    def _question_or_event(self) -> ChatRequest:
        if not self.question and self.event is None:
            raise ValueError("question must not be blank (empty only with event)")
        return self


class LookMeta(BaseModel):
    question: str | None = Field(default=None, max_length=500)
    lang: LangField | None = None
    session_id: str = Field(min_length=1, max_length=64)
    place_id: str | None = Field(default=None, max_length=64)

    @field_validator("question")
    @classmethod
    def _blank_to_none(cls, v: str | None) -> str | None:
        return v.strip() or None if v is not None else None


class AnswerResponse(BaseModel):
    request_id: str
    answer: str
    phrases: list[str]
    lang: LangField
    found: bool
    sources: list[str]


class ChatAction(BaseModel):
    """Действие робота из ответа агента: показать место, проводить, посмотреть камерой."""

    type: Literal["where_is", "guide_to", "look"]
    place_id: str | None = None


Emotion = Literal["neutral", "happy", "thinking", "surprised", "sad", "excited"]


class ChatResponse(AnswerResponse):
    actions: list[ChatAction] = Field(default_factory=list)
    # v1.1: эмоция ответа для лица робота (в SSE — событие emotion перед первой phrase)
    emotion: Emotion = "neutral"
    # v1.2: реплика — шум распознавания; модель не вызывалась, робот молчит и слушает дальше
    ignored: bool = False


class ErrorDetail(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail
    request_id: str | None


class PlaceOut(BaseModel):
    id: str
    map_name: str
    names: dict[str, str]
    synonyms: list[str]
    description: dict[str, str]


class PlacesResponse(BaseModel):
    places: list[PlaceOut]


class QuickQuestionOut(BaseModel):
    id: str
    text: str


class QuickQuestionsResponse(BaseModel):
    items: list[QuickQuestionOut]


MessageKind = Literal["ask", "look", "where_is", "guide_to", "where_am_i"]
LocalKind = Literal["where_is", "guide_to", "where_am_i"]


class ChatMessage(BaseModel):
    id: str
    role: Literal["user", "assistant"]
    kind: MessageKind
    text: str
    lang: LangField
    created_at: str
    request_id: str
    found: bool | None = None
    interrupted: bool | None = None


class SessionMessagesResponse(BaseModel):
    session_id: str
    messages: list[ChatMessage]


class LocalMessageRequest(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "kind": "where_is",
                "question": "Где кафе?",
                "answer": "Кафе справа от вас, метров 15.",
                "lang": "ru",
                "place_id": "cafe",
            }
        }
    )

    kind: LocalKind
    question: str = Field(min_length=1, max_length=500)
    answer: str = Field(min_length=1, max_length=2000)
    lang: LangField
    place_id: str | None = Field(default=None, max_length=64)
    found: bool = True
    interrupted: bool = False

    @field_validator("question", "answer")
    @classmethod
    def _not_blank(cls, v: str) -> str:
        return _strip_required(v)


class LocalMessageResponse(BaseModel):
    request_id: str
