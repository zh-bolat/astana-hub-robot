"""Профили робота: какой токен — такой помощник (docs Kurultai: voice-contract.md, «Этап 2»).

- `astana_hub` — прежний ROBOT_TOKEN: робот-гид Astana Hub (база знаний, места, события). Поведение не меняется.
- `kurultai` — KURULTAI_ROBOT_TOKEN: ИИ-помощник Курултая Республики Казахстан (снимок quryltai.kz, веб-поиск
  на новости), без базы знаний, мест и тулов хаба. Пустой токен — профиль выключен.

Голос, VAD, перебивание, «не мне», лимиты — общие для обоих профилей. Rate limit и лимит live-сессий считаются
по хешу токена, то есть у каждого профиля свой.
"""

from __future__ import annotations

import secrets
from typing import Literal

from app.config import Settings, secret_text

Profile = Literal["astana_hub", "kurultai"]
ASTANA_HUB: Profile = "astana_hub"
KURULTAI: Profile = "kurultai"
PROFILES: tuple[Profile, ...] = (ASTANA_HUB, KURULTAI)


def profile_tokens(settings: Settings) -> list[tuple[Profile, str]]:
    """Включённые профили и их токены (пустой токен — профиля нет)."""
    out: list[tuple[Profile, str]] = []
    hub = secret_text(settings.robot_token)
    if hub:
        out.append((ASTANA_HUB, hub))
    kurultai = secret_text(settings.kurultai_robot_token)
    if kurultai:
        out.append((KURULTAI, kurultai))
    return out


def bearer(header: str) -> str | None:
    """Токен из заголовка Authorization: Bearer …; другая схема или пусто — None."""
    scheme, _, token = header.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
        return None
    return token


def profile_for_token(settings: Settings, token: str | None) -> Profile | None:
    """Профиль по токену (сравнение за постоянное время со всеми токенами); чужой токен — None."""
    if not token:
        return None
    found: Profile | None = None
    raw = token.encode()
    for profile, expected in profile_tokens(settings):
        # без раннего выхода: время проверки не выдаёт, какой из токенов совпал
        if secrets.compare_digest(raw, expected.encode()) and found is None:
            found = profile
    return found
