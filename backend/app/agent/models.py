"""Модели агента через LiteLLM (`LitellmModel` расширения Agents SDK).

AGENT_MODEL + AGENT_FALLBACK_MODELS — строки LiteLLM. Ключ берётся из настроек по префиксу
(anthropic/ → ANTHROPIC_API_KEY, gemini/ → GEMINI_API_KEY, openrouter/ → OPENROUTER_API_KEY) и
передаётся в LiteLLM явно; модель без ключа пропускается (с предупреждением в логе).
"""

from __future__ import annotations

from dataclasses import dataclass

from agents import Model, ModelSettings

from app.config import Settings
from app.logging_setup import logger

_KEYS = {
    "anthropic/": "anthropic_api_key",
    "gemini/": "gemini_api_key",
    "openrouter/": "openrouter_api_key",
}


@dataclass
class AgentModel:
    label: str  # для лога: «litellm:gemini/gemini-3.1-flash-lite»
    model: Model
    settings: ModelSettings


def _key_for(settings: Settings, name: str) -> tuple[bool, str | None]:
    """(нужен ли ключ, ключ)."""
    for prefix, attr in _KEYS.items():
        if name.startswith(prefix):
            secret = getattr(settings, attr)
            return True, secret.get_secret_value() if secret else None
    return False, None


def model_settings_for(name: str, settings: Settings) -> ModelSettings:
    extra: dict = {}
    effort = settings.agent_reasoning_effort.strip().lower()
    if effort == "auto":
        effort = "minimal" if name.startswith("gemini/") or "gemini-3" in name else ""
    if effort:
        extra["reasoning_effort"] = effort
        # LiteLLM 1.83 не знает gemini-3.1-flash-lite как «думающую» модель и при drop_params
        # молча выбрасывает reasoning_effort (модель думает по умолчанию — +секунды задержки)
        extra["allowed_openai_params"] = ["reasoning_effort"]
    # Запрос к провайдеру не живёт дольше хода (ретраи LiteLLM на 503 могли висеть минутами)
    extra["timeout"] = settings.agent_turn_timeout_s + settings.agent_look_extra_s
    if name.startswith("anthropic/"):
        # Кеш промпта Claude: неизменные instructions + описания тулов — кешируемый префикс
        extra["cache_control_injection_points"] = [{"location": "message", "role": "system"}]
    return ModelSettings(
        temperature=0.3,
        max_tokens=settings.agent_max_output_tokens,
        include_usage=True,
        extra_args=extra or None,
    )


def build_agent_models(settings: Settings) -> list[AgentModel]:
    import litellm
    from agents.extensions.models.litellm_model import LitellmModel

    litellm.suppress_debug_info = True
    litellm.drop_params = True  # параметр, которого нет у провайдера, не роняет запрос
    if hasattr(litellm, "telemetry"):
        litellm.telemetry = False

    out: list[AgentModel] = []
    for name in settings.agent_model_list():
        needs_key, key = _key_for(settings, name)
        if needs_key and not key:
            logger.warning("agent_model_skipped_no_key", extra={"model": name})
            continue
        out.append(
            AgentModel(
                label=f"litellm:{name}",
                model=LitellmModel(model=name, api_key=key),
                settings=model_settings_for(name, settings),
            )
        )
    return out
