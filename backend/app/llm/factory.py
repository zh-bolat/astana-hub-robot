"""Выбор провайдеров по настройкам."""

from __future__ import annotations

from app.config import Settings
from app.llm.base import LLMProvider
from app.llm.fake import FakeProvider


def _gemini(settings: Settings) -> LLMProvider:
    from app.llm.gemini import GeminiProvider

    if not settings.gemini_api_key:
        raise RuntimeError("GEMINI_API_KEY is required for the gemini provider")
    return GeminiProvider(
        api_key=settings.gemini_api_key.get_secret_value(),
        model=settings.gemini_model,
        vision_model=settings.gemini_vision_model,
        fallback_models=settings.gemini_fallback_list(),
        thinking_level=settings.gemini_thinking_level,
        thinking_budget=settings.gemini_thinking_budget,
        hedge_after_s=settings.gemini_hedge_after_s,
        max_output_tokens=settings.llm_max_output_tokens,
    )


def build_text_provider(settings: Settings) -> LLMProvider:
    name = settings.resolved_text_provider()
    if name == "gemini":
        return _gemini(settings)
    if name == "deepseek":
        from app.llm.deepseek import DeepSeekProvider

        if not settings.deepseek_api_key:
            raise RuntimeError("DEEPSEEK_API_KEY is required for the deepseek provider")
        return DeepSeekProvider(
            api_key=settings.deepseek_api_key.get_secret_value(),
            base_url=settings.deepseek_base_url,
            model=settings.deepseek_model,
            max_tokens=settings.llm_max_output_tokens,
        )
    return FakeProvider()


def build_vision_provider(settings: Settings, text_provider: LLMProvider) -> LLMProvider:
    name = settings.resolved_vision_provider()
    if name == "gemini":
        if getattr(text_provider, "name", "") == "gemini":
            return text_provider
        return _gemini(settings)
    return FakeProvider()
