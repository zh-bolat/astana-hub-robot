"""Снимок публичных данных quryltai.kz для системного промпта Курултая — порт QuryltaiDataSync.java
(android-app/app/src/main/java/kz/kurultai/assistant/QuryltaiDataSync.java).

- Четыре публичных API: законопроекты (до 100), планы, комитеты с депутатами, депутатские запросы (до 200).
- Обновление при старте и раз в QURYLTAI_REFRESH_S (по умолчанию час); после неудачи — не раньше QURYLTAI_RETRY_S.
  Обновление атомарное: упал хоть один запрос — остаётся прежний снимок. Без сети работаем на последнем снимке,
  он же лежит в файле QURYLTAI_SNAPSHOT_PATH (переживает перезапуск сервера).
- prompt_context — текст в том же формате, что QuryltaiDataSync.promptContext: итоги по статусам законопроектов
  (модель ошибается, когда сама пересчитывает длинные списки), законопроекты, недельные и основные планы, комитеты
  с числом членов, до 120 депутатских запросов. Статусы — словами, пустые поля — без «null».
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from collections.abc import Callable
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import httpx

from app.logging_setup import logger

ASTANA_TZ = timezone(timedelta(hours=5))
PROMPT_MAX_BILLS = 100
PROMPT_MAX_REQUESTS = 120
PROMPT_MAX_PLANS = 3
BILLS_PATH = "/core/draft-laws/?limit=100&offset=0"
PLANS_PATH = "/main/work-plans/plans/"
COMMITTEES_PATH = "/core/committees/with-deputies/"
DEPUTY_REQUESTS_PATH = "/core/deputy-requests/?limit=200&offset=0"
USER_AGENT = "KurultaiRobotServer/1.0"

# Номера статусов законопроектов, как их использует quryltai.kz
STATUS_NAMES: dict[int, str] = {
    5: "на рассмотрении",
    6: "одобрен в первом чтении",
    12: "одобрен во втором чтении",
    7: "принят",
    8: "принят на совместном заседании",
    9: "подписан Президентом",
    10: "отозван",
    13: "отклонён",
    11: "возвращён Сенатом",
}


def status_name(code: int) -> str:
    return STATUS_NAMES.get(code, "уточняется")


# ---------- поля JSON без «null» (как text()/optInt() в Java) ----------
def _text(obj: Any, key: str) -> str:
    if not isinstance(obj, dict):
        return ""
    value = obj.get(key)
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return "" if value == "null" else value
    if isinstance(value, (int, float)):
        return str(value)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _int(obj: Any, key: str, default: int = 0) -> int:
    value = obj.get(key) if isinstance(obj, dict) else None
    if isinstance(value, bool) or value is None:
        return default
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def _one_line(text: str) -> str:
    return text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")


def _list(value: Any) -> list:
    return value if isinstance(value, list) else []


def _join_name(person: Any) -> str:
    if not isinstance(person, dict):
        return ""
    parts = [_text(person, "last_name"), _text(person, "first_name"), _text(person, "patronymic")]
    return " ".join(" ".join(parts).split())


def format_time(millis: int) -> str:
    if millis <= 0:
        return "неизвестно"
    return (
        datetime.fromtimestamp(millis / 1000, UTC).astimezone(ASTANA_TZ).strftime("%d.%m.%Y %H:%M")
    )


def format_epoch(seconds: int) -> str:
    if seconds <= 0:
        return "дата не указана"
    return datetime.fromtimestamp(seconds, UTC).astimezone(ASTANA_TZ).strftime("%d.%m.%Y")


# ---------- сборка снимка ----------
def compact_committees(source: Any) -> list[dict]:
    """Комитеты: id, название и активные депутаты (ФИО, должность)."""
    result: list[dict] = []
    for item in _list(source):
        if not isinstance(item, dict):
            continue
        members: list[dict] = []
        for deputy in _list(item.get("deputies")):
            if not isinstance(deputy, dict) or deputy.get("is_active", True) is False:
                continue
            name = _join_name(deputy)
            if name:
                members.append({"name": name, "position": _text(deputy, "position")})
        result.append({"id": _int(item, "id"), "name": _text(item, "name"), "deputies": members})
    return result


def compact_deputy_requests(source: Any) -> list[dict]:
    """Депутатские запросы: номер, тема, дата, авторы через запятую."""
    result: list[dict] = []
    for item in _list(source):
        if not isinstance(item, dict):
            continue
        authors = ", ".join(n for n in (_join_name(p) for p in _list(item.get("authors"))) if n)
        result.append(
            {
                "id": _int(item, "id"),
                "number": _text(item, "registration_number"),
                "title": _one_line(_text(item, "title")),
                "date": format_epoch(_int(item, "request_date")),
                "authors": authors,
            }
        )
    return result


def build_snapshot(
    bills: Any, plans: Any, committees: Any, deputy_requests: Any, updated_at_ms: int
) -> dict:
    """Ответы четырёх API → снимок (те же поля, что кеш QuryltaiDataSync)."""
    bills = bills if isinstance(bills, dict) else {}
    plans = plans if isinstance(plans, dict) else {}
    deputy_requests = deputy_requests if isinstance(deputy_requests, dict) else {}
    return {
        "source": "quryltai.kz",
        "updated_at": updated_at_ms,
        "bill_count": _int(bills, "count"),
        "bills": _list(bills.get("result")),
        "main_plans": _list(plans.get("main_plans")),
        "weekly_plans": _list(plans.get("weekly_plans")),
        "committees": compact_committees(committees),
        "deputy_request_count": _int(deputy_requests, "count"),
        "deputy_requests": compact_deputy_requests(deputy_requests.get("result")),
    }


def _valid(data: Any) -> bool:
    """Снимок текущей схемы (как cacheHasCurrentSchema в Java)."""
    return isinstance(data, dict) and "committees" in data and "deputy_requests" in data


def prompt_context(data: dict | None) -> str:
    """Текст снимка для системного промпта (формат QuryltaiDataSync.promptContext); нет снимка — пусто."""
    if not _valid(data):
        return ""
    assert data is not None
    out: list[str] = ["\n\nОфициальный локальный снимок quryltai.kz. "]
    out.append(f"Обновлён: {format_time(_int(data, 'updated_at'))}. ")
    out.append(f"Всего законопроектов: {_int(data, 'bill_count')}.\n")
    bills = [b for b in _list(data.get("bills")) if isinstance(b, dict)]
    if bills:
        by_status: dict[str, int] = {}
        for bill in bills:
            name = status_name(_int(bill, "status"))
            by_status[name] = by_status.get(name, 0) + 1
        totals = "; ".join(f"{name} — {n}" for name, n in by_status.items())
        out.append(
            f"Итоги по статусам (посчитано по {len(bills)} законопроектам, используй эти числа, "
            f"не пересчитывай): {totals}.\n"
        )
    for bill in bills[:PROMPT_MAX_BILLS]:
        out.append(
            f"Законопроект ID {_int(bill, 'id')}: {_one_line(_text(bill, 'title'))}; "
            f"опубликован {format_epoch(_int(bill, 'publish_date'))}; "
            f"статус: {status_name(_int(bill, 'status'))}.\n"
        )
    for plan in [p for p in _list(data.get("weekly_plans")) if isinstance(p, dict)][
        :PROMPT_MAX_PLANS
    ]:
        out.append(f"Недельный план: {_one_line(_text(plan, 'title'))}.\n")
    for plan in [p for p in _list(data.get("main_plans")) if isinstance(p, dict)][
        :PROMPT_MAX_PLANS
    ]:
        out.append(f"Основной план мероприятий: {_one_line(_text(plan, 'title'))}.\n")
    committees = [c for c in _list(data.get("committees")) if isinstance(c, dict)]
    if committees:
        out.append(f"\nОфициальный состав комитетов (всего комитетов: {len(committees)}):\n")
        for committee in committees:
            deputies = [d for d in _list(committee.get("deputies")) if isinstance(d, dict)]
            members = []
            for deputy in deputies:
                position = _text(deputy, "position")
                members.append(_text(deputy, "name") + (f" — {position}" if position else ""))
            out.append(
                f"{_text(committee, 'name')} (членов: {len(deputies)}): {'; '.join(members)}.\n"
            )
    requests = [r for r in _list(data.get("deputy_requests")) if isinstance(r, dict)]
    if requests:
        total = _int(data, "deputy_request_count", len(requests))
        out.append(f"\nОфициальные депутатские запросы (всего {total}):\n")
        for request in requests[:PROMPT_MAX_REQUESTS]:
            authors = _text(request, "authors")
            out.append(
                f"{_text(request, 'date')} — {_text(request, 'number')}: {_text(request, 'title')}"
                + (f"; авторы: {authors}" if authors else "")
                + ".\n"
            )
    out.append(
        "Этот снимок является официальным, но для иных изменяемых фактов используй веб-поиск."
    )
    return "".join(out)


class QuryltaiSync:
    """Последний снимок quryltai.kz в памяти (+ файл) и фоновое обновление."""

    def __init__(
        self,
        base_url: str = "https://quryltai.kz/api",
        timeout_s: float = 20.0,
        cache_path: Path | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.cache_path = cache_path
        self.clock = clock
        self.data: dict | None = None
        self.last_error: str | None = None
        self._prompt = ""
        self._client = httpx.AsyncClient(
            transport=transport,
            timeout=httpx.Timeout(timeout_s, connect=min(12.0, timeout_s)),
            headers={
                "Accept": "application/json",
                "Accept-Language": "ru",
                "User-Agent": USER_AGENT,
            },
            follow_redirects=True,
        )
        self._lock = asyncio.Lock()

    # ---------- снимок ----------
    @property
    def updated_at_ms(self) -> int:
        return _int(self.data, "updated_at") if self.data else 0

    def prompt_context(self) -> str:
        return self._prompt

    def set_data(self, data: dict) -> None:
        self.data = data
        self._prompt = prompt_context(data)

    def load_cache(self) -> bool:
        """Последний снимок с диска (старт без сети). Битый или старой схемы файл — без снимка."""
        if self.cache_path is None or not self.cache_path.exists():
            return False
        try:
            data = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            logger.warning("quryltai_cache_unreadable", extra={"error": type(exc).__name__})
            return False
        if not _valid(data):
            return False
        self.set_data(data)
        logger.info(
            "quryltai_cache_loaded",
            extra={"updated_at": format_time(self.updated_at_ms), "bills": len(data["bills"])},
        )
        return True

    def _save_cache(self, data: dict) -> None:
        if self.cache_path is None:
            return
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.cache_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, self.cache_path)
        except OSError as exc:  # снимок в памяти есть, файл — только на случай перезапуска
            logger.warning("quryltai_cache_write_failed", extra={"error": type(exc).__name__})

    # ---------- сеть ----------
    async def _get(self, path: str) -> Any:
        resp = await self._client.get(self.base_url + path)
        if resp.status_code < 200 or resp.status_code >= 300:
            raise httpx.HTTPStatusError(
                f"quryltai.kz: HTTP {resp.status_code}", request=resp.request, response=resp
            )
        return resp.json()

    async def refresh(self) -> bool:
        """Скачать все четыре API и заменить снимок; ошибка — прежний снимок остаётся, False."""
        async with self._lock:
            started = time.perf_counter()
            try:
                bills, plans, committees, requests = await asyncio.gather(
                    self._get(BILLS_PATH),
                    self._get(PLANS_PATH),
                    self._get(COMMITTEES_PATH),
                    self._get(DEPUTY_REQUESTS_PATH),
                )
                data = build_snapshot(bills, plans, committees, requests, int(self.clock() * 1000))
            except Exception as exc:  # сеть, HTTP, не JSON — работаем на последнем снимке
                self.last_error = f"{type(exc).__name__}: {exc}"[:300]
                logger.warning(
                    "quryltai_refresh_failed",
                    extra={
                        "error": self.last_error,
                        "has_snapshot": self.data is not None,
                        "ms": int((time.perf_counter() - started) * 1000),
                    },
                )
                return False
            self.last_error = None
            self.set_data(data)
            await asyncio.to_thread(self._save_cache, data)
            logger.info(
                "quryltai_refreshed",
                extra={
                    "bills": len(data["bills"]),
                    "committees": len(data["committees"]),
                    "deputy_requests": len(data["deputy_requests"]),
                    "prompt_chars": len(self._prompt),
                    "ms": int((time.perf_counter() - started) * 1000),
                },
            )
            return True

    def next_delay(self, refresh_s: float, ok: bool | None, retry_s: float) -> float:
        """Сколько ждать до следующей попытки: снимок свежий — до истечения часа; неудача — retry_s."""
        if ok is False:
            return retry_s
        age = self.clock() - self.updated_at_ms / 1000
        return max(1.0, refresh_s - age) if self.data else retry_s

    async def run_periodic(self, refresh_s: float, retry_s: float) -> None:
        """При старте (если снимка нет или он старше refresh_s) и затем раз в refresh_s."""
        ok: bool | None = None
        while True:
            fresh = self.data is not None and self.clock() - self.updated_at_ms / 1000 < refresh_s
            if not fresh:
                ok = await self.refresh()
            await asyncio.sleep(self.next_delay(refresh_s, ok, retry_s))
            ok = None

    async def aclose(self) -> None:
        await self._client.aclose()
