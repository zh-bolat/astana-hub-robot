"""Остаток кредитов ElevenLabs для вкладки робота (docs/kurultai/robot-settings.md, `voice.credits`):
`GET /v1/user/subscription` → `character_limit − character_count`, кэш 5 минут, ошибка → null (повтор через
минуту). Ключ общий с Astana Hub — цифра общая на аккаунт. Нет ключа — запросов нет, credits всегда null.
"""

from __future__ import annotations

import time
from datetime import UTC, datetime

import httpx

from app.config import Settings, secret_text
from app.logging_setup import logger

TTL_S = 300.0
ERROR_TTL_S = 60.0
TIMEOUT_S = 5.0


class VoiceCredits:
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
        ttl_s: float = TTL_S,
    ) -> None:
        self._key = secret_text(settings.elevenlabs_api_key) if settings.elevenlabs_api_key else ""
        self._base = settings.elevenlabs_base_url.rstrip("/")
        self._client = client
        self._ttl = ttl_s
        self.credits: int | None = None
        self.checked_at: datetime | None = None
        self._valid_until = 0.0

    @property
    def provider(self) -> str | None:
        return "elevenlabs" if self._key else None

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=TIMEOUT_S)
        return self._client

    async def get(self) -> tuple[int | None, datetime | None]:
        """(credits, checked_at) из кэша или свежие."""
        if not self._key:
            return None, None
        if time.monotonic() < self._valid_until:
            return self.credits, self.checked_at
        self.checked_at = datetime.now(UTC).replace(microsecond=0)
        try:
            r = await self._http().get(
                f"{self._base}/v1/user/subscription", headers={"xi-api-key": self._key}
            )
            r.raise_for_status()
            data = r.json()
            self.credits = int(data["character_limit"]) - int(data["character_count"])
            self._valid_until = time.monotonic() + self._ttl
        except Exception as exc:
            self.credits = None
            self._valid_until = time.monotonic() + ERROR_TTL_S
            logger.warning("elevenlabs_credits_failed", extra={"error": type(exc).__name__})
        return self.credits, self.checked_at

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
