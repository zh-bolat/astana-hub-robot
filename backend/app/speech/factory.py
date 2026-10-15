"""Выбор провайдеров STT и TTS (раздельно) по настройкам."""

from __future__ import annotations

from app.config import Settings
from app.speech.base import SttProvider, TtsProvider
from app.speech.fake import FakeSpeechProvider


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
    return FakeSpeechProvider()


def build_tts_provider(settings: Settings) -> TtsProvider:
    name = settings.resolved_tts_provider()
    if name == "elevenlabs":
        return _elevenlabs(settings)
    if name == "yandex":
        return _yandex(settings)
    return FakeSpeechProvider()
