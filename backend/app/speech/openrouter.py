"""OpenRouter: STT мультимодальной моделью (по умолчанию Gemini) и TTS моделями ElevenLabs.

- STT: OpenAI-совместимый POST {base}/chat/completions; аудио — часть `input_audio` (base64 WAV),
  ответ — JSON по схеме {lang, text}. Промпт тот же, что у прямого Gemini STT.
Замеры 2026-10-19 (google/gemini-3.1-flash-lite, reasoning minimal, клипы 4–5 с): 1.8–3.9 с, верный
транскрипт ru/kk/en. Инструкция стоит ДО аудио и продублирована системным сообщением: с промптом после аудио
казахский на части записей приходил латиницей (0/8), с промптом до аудио — кириллицей (4/4 + 4/4).
  Повторы и резервные модели — `run_with_fallback`, как у /ask.
- TTS: POST {base}/audio/speech {model, input, voice, response_format: "mp3"} → байты MP3 (audio/mpeg).
  Модели ElevenLabs: kk → `elevenlabs/eleven-v3` (единственная с казахским), ru/en → `elevenlabs/eleven-flash-v2.5`.
  `voice` — voice_id из библиотеки ElevenLabs (без него 400). Замеры 2026-10-19: ru/en ~0.7 с, kk ~2.6–3.0 с.
"""

from __future__ import annotations

import base64

import httpx
from pydantic import BaseModel

from app.lang import Lang, normalize_lang
from app.llm.fallback import RetryableError, run_with_fallback
from app.speech.base import SpeechError, SttResult, has_letters
from app.speech.gemini_stt import stt_prompt
from app.speech.wav import make_wav

_RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "transcript",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "lang": {"type": "string", "enum": ["ru", "kk", "en"]},
                "text": {"type": "string"},
            },
            "required": ["lang", "text"],
            "additionalProperties": False,
        },
    },
}

_SYSTEM = (
    "You are a speech-to-text engine for a kiosk in Kazakhstan. "
    "Russian and Kazakh are always written in Cyrillic, never transliterated into Latin letters."
)

_RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


class _SttJson(BaseModel):
    text: str = ""
    lang: str | None = None


class OpenRouterSpeechProvider:
    name = "openrouter"

    def __init__(
        self,
        api_key: str,
        stt_model: str = "google/gemini-3.1-flash-lite",
        stt_fallback_models: list[str] | None = None,
        voices: dict[str, str] | None = None,
        tts_model_kk: str = "elevenlabs/eleven-v3",
        tts_model_default: str = "elevenlabs/eleven-flash-v2.5",
        base_url: str = "https://openrouter.ai/api/v1",
        deadline_s: float = 5.0,
        hedge_after_s: float = 3.0,
        timeout_s: float = 6.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.models = list(dict.fromkeys([stt_model, *(stt_fallback_models or [])]))
        self.voices = voices or {}
        self.tts_model_kk = tts_model_kk
        self.tts_model_default = tts_model_default
        self.deadline_s = deadline_s
        self.hedge_after_s = hedge_after_s
        self.client = client or httpx.AsyncClient(
            timeout=timeout_s, headers={"Authorization": f"Bearer {api_key}"}
        )

    # ---------- TTS ----------
    def tts_model(self, lang: Lang) -> str:
        return self.tts_model_kk if lang == "kk" else self.tts_model_default

    def voice_id(self, lang: Lang) -> str:
        return f"{self.voices[lang]}:{self.tts_model(lang)}:mp3"

    async def tts(self, text: str, lang: Lang) -> bytes:
        body = {
            "model": self.tts_model(lang),
            "input": text,
            "voice": self.voices[lang],
            "response_format": "mp3",
        }
        try:
            r = await self.client.post(f"{self.base_url}/audio/speech", json=body)
        except httpx.HTTPError as exc:
            raise SpeechError(f"openrouter tts network error: {type(exc).__name__}") from exc
        if r.status_code != 200:
            raise SpeechError(f"openrouter tts http {r.status_code}: {r.text[:200]}")
        if not r.content or not r.headers.get("content-type", "").startswith("audio/"):
            raise SpeechError("openrouter tts returned no audio")
        return r.content

    # ---------- STT ----------
    async def _attempt(self, model: str, wav_b64: str, lang: Lang | None) -> _SttJson:
        body = {
            "model": model,
            "temperature": 0,
            "max_tokens": 512,
            "response_format": _RESPONSE_FORMAT,
            "reasoning": {"effort": "minimal"},
            "messages": [
                {"role": "system", "content": _SYSTEM},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": stt_prompt(lang)},
                        {"type": "input_audio", "input_audio": {"data": wav_b64, "format": "wav"}},
                    ],
                },
            ],
        }
        try:
            r = await self.client.post(f"{self.base_url}/chat/completions", json=body)
        except httpx.HTTPError as exc:
            raise RetryableError(f"openrouter stt {model}: {type(exc).__name__}") from exc
        msg = f"openrouter stt {model}: http {r.status_code}"
        if r.status_code in _RETRYABLE_HTTP:
            raise RetryableError(msg)
        if r.status_code != 200:
            raise SpeechError(f"{msg}: {r.text[:200]}")
        try:
            raw = (r.json()["choices"][0]["message"]["content"] or "").strip()
            return _SttJson.model_validate_json(raw[raw.find("{") : raw.rfind("}") + 1])
        except Exception as exc:
            raise SpeechError(f"openrouter stt {model}: invalid response") from exc

    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        wav_b64 = base64.b64encode(make_wav(pcm, sample_rate)).decode()
        try:
            parsed, _model = await run_with_fallback(
                self.models,
                lambda m: self._attempt(m, wav_b64, lang),
                deadline_s=self.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=2.0,
            )
        except RetryableError as exc:
            raise SpeechError(str(exc)) from exc
        finally:
            wav_b64 = ""  # аудио посетителя не держим дольше запроса
        text = parsed.text.strip()
        result_lang: Lang = lang or normalize_lang(parsed.lang) or "ru"
        if not has_letters(text):
            text = ""
        return SttResult(text=text, lang=result_lang, confidence=None)
