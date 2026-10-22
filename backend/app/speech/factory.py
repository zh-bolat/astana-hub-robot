"""Выбор провайдеров STT и TTS (раздельно) по настройкам."""

from __future__ import annotations

from app.config import Settings
from app.lang import Lang
from app.logging_setup import logger
from app.speech.base import SpeechError, SttProvider, TtsProvider
from app.speech.fake import FakeSpeechProvider
from app.speech.streaming import BufferedStreamingStt, FallbackStreamingStt, StreamingStt


def _yandex(settings: Settings):
    from app.speech.yandex import YandexSpeechKitProvider

    if not settings.yandex_api_key:
        raise RuntimeError("YANDEX_API_KEY is required for the yandex speech provider")
    return YandexSpeechKitProvider(
        api_key=settings.yandex_api_key.get_secret_value(),
        folder_id=settings.yandex_folder_id,
        stt_url=settings.yandex_stt_url,
        tts_url=settings.yandex_tts_url,
        voices={
            "ru": settings.yandex_voice_ru,
            "kk": settings.yandex_voice_kk,
            "en": settings.yandex_voice_en,
        },
        timeout_s=max(settings.stt_timeout_s, settings.tts_timeout_s) + 1,
        # lang=null: три языка параллельно, ждём ~3 с, но не дольше общего STT_TIMEOUT_S
        auto_lang_timeout_s=min(3.0, settings.stt_timeout_s),
    )


def _elevenlabs(settings: Settings):
    from app.speech.elevenlabs import ElevenLabsProvider

    if not settings.elevenlabs_api_key:
        raise RuntimeError("ELEVENLABS_API_KEY is required for the elevenlabs provider")
    return ElevenLabsProvider(
        api_key=settings.elevenlabs_api_key.get_secret_value(),
        base_url=settings.elevenlabs_base_url,
        voices={
            "ru": settings.elevenlabs_voice_ru,
            "kk": settings.elevenlabs_voice_kk,
            "en": settings.elevenlabs_voice_en,
        },
        tts_model_kk=settings.elevenlabs_tts_model_kk,
        tts_model_default=settings.elevenlabs_tts_model_default,
        output_format=settings.elevenlabs_output_format,
        stt_model=settings.elevenlabs_stt_model,
        timeout_s=max(settings.stt_timeout_s, settings.tts_timeout_s) + 1,
    )


def _openrouter(settings: Settings):
    from app.speech.openrouter import OpenRouterSpeechProvider

    if not settings.openrouter_api_key:
        raise RuntimeError("OPENROUTER_API_KEY is required for the openrouter speech provider")
    return OpenRouterSpeechProvider(
        api_key=settings.openrouter_api_key.get_secret_value(),
        stt_model=settings.openrouter_stt_model,
        stt_fallback_models=settings.openrouter_stt_fallback_list(),
        voices={
            "ru": settings.openrouter_voice_ru,
            "kk": settings.openrouter_voice_kk,
            "en": settings.openrouter_voice_en,
        },
        tts_model_kk=settings.openrouter_tts_model_kk,
        tts_model_default=settings.openrouter_tts_model_default,
        base_url=settings.openrouter_base_url,
        deadline_s=settings.stt_timeout_s,
        timeout_s=max(settings.stt_timeout_s, settings.tts_timeout_s) + 1,
    )


def build_stt_provider(settings: Settings) -> SttProvider:
    name = settings.resolved_stt_provider()
    if name == "elevenlabs":
        return _elevenlabs(settings)
    if name == "yandex":
        return _yandex(settings)
    if name == "gemini":
        from app.speech.gemini_stt import GeminiSttProvider

        if not settings.gemini_api_key:
            raise RuntimeError("GEMINI_API_KEY is required for the gemini STT provider")
        return GeminiSttProvider(
            api_key=settings.gemini_api_key.get_secret_value(),
            model=settings.gemini_stt_model,
            fallback_models=settings.gemini_stt_fallback_list(),
            deadline_s=settings.stt_timeout_s,
        )
    if name == "openrouter":
        return _openrouter(settings)
    return FakeSpeechProvider()


class FallbackTtsProvider:
    """Основной TTS, а при его ошибке (кончились кредиты, 5xx, сеть) — запасной: робот не замолкает.

    Таймаут не переключает: на него уже ушло всё время запроса (/v1/tts ограничен TTS_TIMEOUT_S).
    Голоса у обоих — одни и те же voice_id ElevenLabs, поэтому кеш общий, по ключу основного.
    """

    def __init__(self, primary: TtsProvider, fallback: TtsProvider) -> None:
        self.primary = primary
        self.fallback = fallback
        self.name = primary.name

    def voice_id(self, lang: Lang) -> str:
        return self.primary.voice_id(lang)

    async def tts(self, text: str, lang: Lang) -> bytes:
        try:
            return await self.primary.tts(text, lang)
        except SpeechError as exc:
            logger.warning(
                "tts_fallback",
                extra={"error": str(exc), "primary": self.primary.name, "used": self.fallback.name},
            )
            return await self.fallback.tts(text, lang)


def _tts_by_name(settings: Settings, name: str) -> TtsProvider:
    if name == "elevenlabs":
        return _elevenlabs(settings)
    if name == "yandex":
        return _yandex(settings)
    if name == "openrouter":
        return _openrouter(settings)
    return FakeSpeechProvider()


def build_tts_provider(settings: Settings) -> TtsProvider:
    name = settings.resolved_tts_provider()
    primary = _tts_by_name(settings, name)
    fallback = settings.tts_fallback_provider
    if not fallback or fallback == name:
        return primary
    return FallbackTtsProvider(primary, _tts_by_name(settings, fallback))


def build_stt_stream_provider(settings: Settings, stt: SttProvider) -> StreamingStt:
    """Live STT; запасной вариант — буферный режим поверх уже выбранного STT."""
    buffered = BufferedStreamingStt(
        stt, timeout_s=settings.stt_timeout_s, silence_peak=settings.stt_silence_peak
    )
    if settings.resolved_stt_stream_provider() != "elevenlabs":
        return buffered
    from app.speech.streaming import ElevenLabsRealtimeStt

    if not settings.elevenlabs_api_key:
        raise RuntimeError("ELEVENLABS_API_KEY is required for the elevenlabs live STT")
    realtime = ElevenLabsRealtimeStt(
        api_key=settings.elevenlabs_api_key.get_secret_value(),
        url=settings.elevenlabs_realtime_url,
        model=settings.elevenlabs_realtime_model,
        silence_s=settings.elevenlabs_realtime_silence_s,
    )
    return FallbackStreamingStt(realtime, buffered)
