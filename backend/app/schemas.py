"""Pydantic-схемы по docs/api-contract.md (v1)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

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
