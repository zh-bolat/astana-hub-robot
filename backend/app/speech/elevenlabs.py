"""ElevenLabs: TTS (text-to-speech) и STT (Scribe). Сверено с elevenlabs.io/docs (2026-10):

- TTS: POST {base}/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128, заголовок `xi-api-key`,
  JSON {text, model_id, language_code?, voice_settings?} → байты аудио.
  Казахский (kk) поддерживает только `eleven_v3`; ru/en — `eleven_flash_v2_5` (минимальная задержка).
  `language_code` (ISO 639-1) не поддерживается моделями multilingual_v2 — для них не передаём.
- STT: POST {base}/v1/speech-to-text, multipart: model_id=scribe_v2, file, language_code? (ISO 639-1/3,
  без него — автоопределение), file_format=pcm_s16le_16 (сырой PCM 16 кГц mono — ниже задержка),
  tag_audio_events=false. Ответ: {language_code, language_probability, text, words}.
"""

from __future__ import annotations

import httpx

from app.lang import Lang, normalize_lang
from app.speech.base import SpeechError, SttResult, has_letters

# Scribe может вернуть ISO 639-3
_ISO3 = {"rus": "ru", "kaz": "kk", "eng": "en"}


class ElevenLabsProvider:
    name = "elevenlabs"

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.elevenlabs.io",
        voices: dict[str, str] | None = None,
        tts_model_kk: str = "eleven_v3",
        tts_model_default: str = "eleven_flash_v2_5",
        output_format: str = "mp3_44100_128",
        stt_model: str = "scribe_v2",
        timeout_s: float = 10.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.voices = voices or {}
        self.tts_model_kk = tts_model_kk
        self.tts_model_default = tts_model_default
        self.output_format = output_format
        self.stt_model = stt_model
        self.client = client or httpx.AsyncClient(
            timeout=timeout_s, headers={"xi-api-key": api_key}
        )

    # ---------- TTS ----------
    def tts_model(self, lang: Lang) -> str:
        return self.tts_model_kk if lang == "kk" else self.tts_model_default

    def voice_id(self, lang: Lang) -> str:
        return f"{self.voices[lang]}:{self.tts_model(lang)}:{self.output_format}"

    async def tts(self, text: str, lang: Lang) -> bytes:
        model = self.tts_model(lang)
        body: dict = {"text": text, "model_id": model}
        if "multilingual_v2" not in model:
            body["language_code"] = lang
        url = f"{self.base_url}/v1/text-to-speech/{self.voices[lang]}"
        try:
            r = await self.client.post(url, params={"output_format": self.output_format}, json=body)
        except httpx.HTTPError as exc:
            raise SpeechError(f"elevenlabs tts network error: {type(exc).__name__}") from exc
        if r.status_code != 200:
            raise SpeechError(f"elevenlabs tts http {r.status_code}: {r.text[:200]}")
        if not r.content:
            raise SpeechError("elevenlabs tts returned empty audio")
        return r.content

    # ---------- STT ----------
    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        data = {
            "model_id": self.stt_model,
            "tag_audio_events": "false",
            "timestamps_granularity": "none",
        }
        if sample_rate == 16000:
            data["file_format"] = "pcm_s16le_16"
            file = ("audio.pcm", pcm, "application/octet-stream")
        else:  # не наш случай (WAV проверяется на 16 кГц), но на всякий случай
            from app.speech.wav import make_wav

            data["file_format"] = "other"
            file = ("audio.wav", make_wav(pcm, sample_rate), "audio/wav")
        if lang:
            data["language_code"] = lang
        try:
            r = await self.client.post(
                f"{self.base_url}/v1/speech-to-text", data=data, files={"file": file}
            )
        except httpx.HTTPError as exc:
            raise SpeechError(f"elevenlabs stt network error: {type(exc).__name__}") from exc
        if r.status_code != 200:
            raise SpeechError(f"elevenlabs stt http {r.status_code}: {r.text[:200]}")
        try:
            payload = r.json()
        except ValueError as exc:
            raise SpeechError("elevenlabs stt returned non-JSON") from exc
        text = str(payload.get("text") or "").strip()
        detected = str(payload.get("language_code") or "").lower()
        result_lang: Lang = lang or normalize_lang(_ISO3.get(detected, detected)) or "ru"
        if not has_letters(text):
            text = ""
        prob = payload.get("language_probability")
        return SttResult(
            text=text,
            lang=result_lang,
            confidence=float(prob) if isinstance(prob, int | float) else None,
        )
