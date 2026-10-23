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


# ---------- Агентский чат /v1/chat ----------


class RobotState(BaseModel):
    nearest_place_id: str | None = Field(default=None, max_length=64)
    navigating: bool = False


class ChatRequest(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "session_id": "6f1c2a9e-7d4b-4c1a-9f3e-2b8d5e6a7c10",
                "text": "Где тут можно поработать?",
                "lang": "ru",
                "input": "voice",
                "event": None,
                "robot_state": {"nearest_place_id": "reception", "navigating": False},
            }
        }
    )

    session_id: str = Field(min_length=1, max_length=64)
    # Пусто допустимо только вместе с event (visitor_arrived)
    text: str = Field(default="", max_length=500)
    lang: LangField | None = None
    input: Literal["voice", "text", "button"] | None = None
    event: Literal["visitor_arrived"] | None = None
    robot_state: RobotState | None = None

    @field_validator("session_id")
    @classmethod
    def _session_not_blank(cls, v: str) -> str:
        return _strip_required(v)

    @field_validator("text")
    @classmethod
    def _strip_text(cls, v: str) -> str:
        return v.strip()

    @model_validator(mode="after")
    def _text_or_event(self) -> ChatRequest:
        if not self.text and self.event is None:
            raise ValueError("text must not be blank (empty text is allowed only with event)")
        return self
