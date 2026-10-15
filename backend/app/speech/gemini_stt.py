"""STT через Gemini: аудио → строгий транскрипт + язык (ru/kk/en), JSON-режим.

Выбор модели (замеры 2026-10-15 на ключе проекта, клипы 2–3 с):
- gemini-3.1-flash-lite + JSON-схема {text, lang}: ~2.4–2.9 с, верный транскрипт kk/ru/en, язык определяется.
  На тишине/шуме иногда «галлюцинирует» ("00:00") — отсекаем порогом тишины до вызова и фильтром
  `has_letters` после.
- gemini-3.5-transcribe: JSON-режим не поддерживается (400), текст приходит в части `audio_transcription`,
  язык не возвращает; в замерах часто 503 и до 18 с. Поэтому по умолчанию не используется.
Повторы/резервные модели — тот же `run_with_fallback`, что и для /ask.
"""

from __future__ import annotations

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel

from app.lang import Lang, normalize_lang
from app.llm.fallback import RetryableError, run_with_fallback
from app.speech.base import SpeechError, SttResult, has_letters
from app.speech.wav import make_wav

_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "text": {"type": "STRING"},
        "lang": {"type": "STRING", "enum": ["ru", "kk", "en"]},
    },
    "required": ["text", "lang"],
    "propertyOrdering": ["lang", "text"],
}

_LANG_NAMES = {"ru": "Russian", "kk": "Kazakh", "en": "English"}
_RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


def stt_prompt(lang: Lang | None) -> str:
    lang_line = (
        f"The speech is in {_LANG_NAMES[lang]}."
        if lang
        else "The speech is in Russian, Kazakh or English — detect which."
    )
    return (
        "Transcribe the speech in this audio verbatim. "
        f"{lang_line} "
        "Write Kazakh in Cyrillic with Kazakh letters (ә ғ қ ң ө ұ ү һ і). "
        'Return JSON {"lang": "ru"|"kk"|"en", "text": transcript}. '
        'If there is no intelligible speech (silence, noise, music), return "text": "". '
        "Output only the transcript: no translation, no comments, no timestamps."
    )


class _SttJson(BaseModel):
    text: str = ""
    lang: str | None = None


class GeminiSttProvider:
    name = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        fallback_models: list[str] | None = None,
        deadline_s: float = 5.0,
        hedge_after_s: float = 3.0,
    ) -> None:
        self.client = genai.Client(
            api_key=api_key, http_options=types.HttpOptions(retry_options=None)
        )
        self.models = list(dict.fromkeys([model, *(fallback_models or [])]))
        self.deadline_s = deadline_s
        self.hedge_after_s = hedge_after_s

    async def _attempt(self, model: str, wav: bytes, lang: Lang | None) -> _SttJson:
        config = types.GenerateContentConfig(
            temperature=0,
            max_output_tokens=512,
            response_mime_type="application/json",
            response_schema=_SCHEMA,
            thinking_config=types.ThinkingConfig(thinking_level="MINIMAL"),
        )
        audio = types.Part.from_bytes(data=wav, mime_type="audio/wav")
        try:
            resp = await self.client.aio.models.generate_content(
                model=model, contents=[audio, stt_prompt(lang)], config=config
            )
        except genai_errors.APIError as exc:
            msg = f"gemini stt {model}: {exc.code} {exc.status or ''}".strip()
            if exc.code in _RETRYABLE_HTTP:
                raise RetryableError(msg) from exc
            raise SpeechError(msg) from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            raise RetryableError(f"gemini stt {model}: {type(exc).__name__}") from exc
        raw = (resp.text or "").strip()
        try:
            return _SttJson.model_validate_json(raw[raw.find("{") : raw.rfind("}") + 1])
        except Exception as exc:
            raise SpeechError(f"gemini stt {model}: invalid JSON") from exc

    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        wav = make_wav(pcm, sample_rate)
        try:
            parsed, _model = await run_with_fallback(
                self.models,
                lambda m: self._attempt(m, wav, lang),
                deadline_s=self.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=2.0,
            )
        except RetryableError as exc:
            raise SpeechError(str(exc)) from exc
        finally:
            wav = b""  # аудио посетителя не держим дольше запроса
        text = parsed.text.strip()
        result_lang: Lang = lang or normalize_lang(parsed.lang) or "ru"
        if not has_letters(text):
            text = ""
        return SttResult(text=text, lang=result_lang, confidence=None)
