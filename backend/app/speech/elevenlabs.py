"""ElevenLabs: TTS (text-to-speech) и STT (Scribe). Сверено с elevenlabs.io/docs (2026-10):

- TTS: POST {base}/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128, заголовок `xi-api-key`,
  JSON {text, model_id, language_code?, voice_settings?} → байты аудио.
  Казахский (kk) поддерживает только `eleven_v3`; ru/en — `eleven_flash_v2_5` (минимальная задержка).
  `language_code` (ISO 639-1) не поддерживается моделями multilingual_v2 — для них не передаём.
- STT: POST {base}/v1/speech-to-text, multipart: model_id=scribe_v2, file, language_code? (ISO 639-1/3,
  без него — автоопределение), file_format=pcm_s16le_16 (сырой PCM 16 кГц mono — ниже задержка),
  tag_audio_events=false. Ответ: {language_code, language_probability, text, words}.
- voice_settings (TTS и /stream): stability, similarity_boost, style, use_speaker_boost, speed (0.7–1.2).
  Eleven v3 принимает только stability (0.0 Creative / 0.5 Natural / 1.0 Robust): similarity, speaker boost и
  speed для v3 недоступны (elevenlabs.io/docs/eleven-creative/playground/text-to-speech), style для v3
  не документирован — не шлём. Остальным моделям (flash_v2_5, multilingual_v2) — все поля.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass

import httpx

from app.lang import Lang, normalize_lang
from app.speech.base import SpeechError, SttResult, has_letters

# Scribe может вернуть ISO 639-3
_ISO3 = {"rus": "ru", "kaz": "kk", "eng": "en"}
_V3_STABILITY = (0.0, 0.5, 1.0)
# Модели с казахским (ElevenLabs, 2026-10): v3 и v4; flash / turbo / multilingual — unsupported_language
KK_MODEL_PREFIXES = ("eleven_v3", "eleven_v4")


@dataclass(frozen=True)
class VoiceSettings:
    """Настройки голоса ElevenLabs (env ELEVENLABS_STABILITY и т.д.).

    flash / multilingual: stability 0.35, similarity 0.8, style 0.45, speaker boost, speed 1.1 — энергично.
    Eleven v3 (kk) — только своя stability (ELEVENLABS_V3_STABILITY): 0.0 Creative, 0.5 Natural (по умолчанию),
    1.0 Robust; плюс звуковой тег перед текстом (ELEVENLABS_V3_TAG, «[cheerfully]»)."""

    stability: float = 0.35
    similarity_boost: float = 0.8
    style: float = 0.45
    use_speaker_boost: bool = True
    speed: float = 1.1
    v3_stability: float = 0.5
    v3_tag: str = "[cheerfully]"

    def for_model(self, model: str) -> dict:
        """Поля voice_settings, которые модель принимает."""
        if model.startswith("eleven_v3"):
            return {"stability": min(_V3_STABILITY, key=lambda v: abs(v - self.v3_stability))}
        return {
            "stability": self.stability,
            "similarity_boost": self.similarity_boost,
            "style": self.style,
            "use_speaker_boost": self.use_speaker_boost,
            "speed": min(1.2, max(0.7, self.speed)),
        }

    def signature(self, model: str) -> str:
        """Для ключа кеша TTS: другие настройки (и тег v3) — другое аудио."""
        sig = ",".join(f"{k}={v}" for k, v in sorted(self.for_model(model).items()))
        tag = self.v3_tag.strip()
        return f"{sig},tag={tag}" if tag and model.startswith(KK_MODEL_PREFIXES) else sig

    def tag_text(self, model: str, text: str) -> str:
        """Текст запроса синтеза: v3 — с тегом подачи («[cheerfully] Сәлеметсіз бе!»), остальные — как есть."""
        tag = self.v3_tag.strip()
        if tag and model.startswith(KK_MODEL_PREFIXES) and not text.startswith(tag):
            return f"{tag} {text}"
        return text

    @classmethod
    def from_settings(cls, settings) -> VoiceSettings:
        return cls(
            stability=settings.elevenlabs_stability,
            similarity_boost=settings.elevenlabs_similarity,
            style=settings.elevenlabs_style,
            use_speaker_boost=settings.elevenlabs_speaker_boost,
            speed=settings.elevenlabs_speed,
            v3_stability=settings.elevenlabs_v3_stability,
            v3_tag=settings.elevenlabs_v3_tag,
        )


def kk_safe_model(model: str) -> str:
    """Ошибка в env (ELEVENLABS_TTS_MODEL_KK=eleven_flash_v2_5) не должна отправить казахский текст в flash."""
    return model if model.startswith(KK_MODEL_PREFIXES) else "eleven_v3"


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
        voice_settings: VoiceSettings | None = None,
        timeout_s: float = 10.0,
        client: httpx.AsyncClient | None = None,
        tts_models: dict[str, str | None] | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.voices = voices or {}
        self.tts_model_kk = tts_model_kk
        self.tts_model_default = tts_model_default
        # ELEVENLABS_TTS_MODEL_RU / _EN: своя модель для языка (пусто — tts_model_default)
        self.tts_models = {k: v for k, v in (tts_models or {}).items() if v}
        self.output_format = output_format
        self.stt_model = stt_model
        self.voice_settings = voice_settings or VoiceSettings()
        self.client = client or httpx.AsyncClient(
            timeout=timeout_s, headers={"xi-api-key": api_key}
        )

    async def aclose(self) -> None:
        await self.client.aclose()

    # ---------- TTS ----------
    def tts_model(self, lang: Lang) -> str:
        if lang == "kk":
            return kk_safe_model(self.tts_model_kk)
        return self.tts_models.get(lang) or self.tts_model_default

    def voice_id(self, lang: Lang) -> str:
        model = self.tts_model(lang)
        settings = self.voice_settings.signature(model)
        return f"{self.voices[lang]}:{model}:{self.output_format}:{settings}"

    def _tts_body(self, text: str, lang: Lang) -> dict:
        model = self.tts_model(lang)
        body: dict = {
            "text": self.voice_settings.tag_text(model, text),
            "model_id": model,
            "voice_settings": self.voice_settings.for_model(model),
        }
        if "multilingual_v2" not in model:
            body["language_code"] = lang
        return body

    async def tts_stream(self, text: str, lang: Lang) -> AsyncIterator[bytes]:
        """Потоковый синтез: POST /v1/text-to-speech/{voice_id}/stream — куски MP3 по мере готовности.
        Сверено с elevenlabs.io/docs (2026-10): тело и output_format — как у обычного TTS;
        optimize_streaming_latency помечен deprecated — не передаём (задержку снижает модель flash_v2_5).
        Ошибка HTTP до первых байт → SpeechError (эндпоинт отвечает 503)."""
        url = f"{self.base_url}/v1/text-to-speech/{self.voices[lang]}/stream"
        try:
            async with self.client.stream(
                "POST",
                url,
                params={"output_format": self.output_format},
                json=self._tts_body(text, lang),
            ) as r:
                if r.status_code != 200:
                    detail = (await r.aread())[:200].decode("utf-8", "replace")
                    raise SpeechError(f"elevenlabs tts stream http {r.status_code}: {detail}")
                async for chunk in r.aiter_bytes():
                    if chunk:
                        yield chunk
        except httpx.HTTPError as exc:
            raise SpeechError(f"elevenlabs tts stream network error: {type(exc).__name__}") from exc

    async def tts(self, text: str, lang: Lang) -> bytes:
        body = self._tts_body(text, lang)
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
            detected=detected or None,
        )
