"""База знаний из Google-таблицы (шаг 3.3), без OAuth.

Таблица открыта «всем, у кого есть ссылка — читатель». Вкладки читаются CSV-экспортом:
    https://docs.google.com/spreadsheets/d/<ID>/gviz/tq?tqx=out:csv&sheet=<имя вкладки>
Формат колонок — backend/README.md и шаблоны в backend/knowledge/sheet_template/.

Поведение:
- при старте сеть не трогаем: последний снимок (KNOWLEDGE_SNAPSHOT_PATH), нет снимка → локальные
  YAML; старт не ждёт Google;
- сразу после старта и далее раз в KNOWLEDGE_SYNC_INTERVAL_S — фоновая синхронизация: все вкладки
  параллельно с общим таймаутом KNOWLEDGE_SYNC_TIMEOUT_S; при ошибке остаёмся на последней
  удачной версии (и не затираем базу пустой/битой таблицей);
- заголовки каждой вкладки проверяются (`REQUIRED_HEADERS`): gviz при неверном имени вкладки
  молча отдаёт первую вкладку — без проверки «Места» могли бы прочитаться из «Фактов»;
- удачная синхронизация сохраняет снимок на диск.
"""

from __future__ import annotations

import asyncio
import csv
import io
import os
from pathlib import Path
from urllib.parse import quote

import httpx

from app.knowledge.models import Fact, KnowledgeBase, LocalizedText, Place, QuickQuestion
from app.knowledge.source import KnowledgeSource, build_kb
from app.logging_setup import logger

_INACTIVE = {"нет", "no", "0", "false", "жоқ", "-"}


class SheetError(Exception):
    pass


def sheet_csv_url(sheet_id: str, tab: str) -> str:
    return (
        f"https://docs.google.com/spreadsheets/d/{quote(sheet_id)}/gviz/tq"
        f"?tqx=out:csv&sheet={quote(tab)}"
    )


# Обязательные колонки вкладок: (все из ...), (хотя бы одна из ...)
REQUIRED_HEADERS: dict[str, tuple[frozenset[str], frozenset[str]]] = {
    "facts": (frozenset({"id"}), frozenset({"answer_ru", "answer_kk", "answer_en"})),
    "places": (frozenset({"id", "map_name"}), frozenset()),
    "buttons": (frozenset({"id", "text_ru"}), frozenset()),
}


def check_headers(kind: str, headers: list[str]) -> None:
    required, any_of = REQUIRED_HEADERS[kind]
    present = set(headers)
    missing = sorted(required - present)
    if any_of and not any_of & present:
        missing.append(" | ".join(sorted(any_of)))
    if missing:
        raise SheetError(
            f"вкладка {kind}: нет колонок {missing} (неверное имя вкладки? gviz отдаёт первую)"
        )


def read_table(text: str) -> tuple[list[str], list[dict[str, str]]]:
    """CSV → (нормализованные заголовки, активные непустые строки)."""
    text = text.lstrip("﻿")
    if text.lstrip().startswith("<"):
        raise SheetError("got HTML instead of CSV (таблица не открыта по ссылке?)")
    reader = csv.DictReader(io.StringIO(text))
    headers = [(h or "").strip().lower() for h in (reader.fieldnames or [])]
    rows = []
    for row in reader:
        clean = {
            (k or "").strip().lower(): (v or "").strip() for k, v in row.items() if k is not None
        }
        if not any(clean.values()):
            continue
        if clean.get("active", "").lower() in _INACTIVE:
            continue
        rows.append(clean)
    return headers, rows


def read_csv(text: str) -> list[dict[str, str]]:
    return read_table(text)[1]


def _tab(kind: str, text: str) -> list[dict[str, str]]:
    headers, rows = read_table(text)
    check_headers(kind, headers)
    return rows


def _list(value: str) -> list[str]:
    sep = ";" if ";" in value else ","
    return [x.strip() for x in value.split(sep) if x.strip()]


def _loc(row: dict[str, str], prefix: str) -> LocalizedText:
    return LocalizedText(
        ru=row.get(f"{prefix}_ru", ""),
        kk=row.get(f"{prefix}_kk", ""),
        en=row.get(f"{prefix}_en", ""),
    )


def facts_from_rows(rows: list[dict[str, str]]) -> list[Fact]:
    facts = []
    for n, row in enumerate(rows, start=2):
        answer = _loc(row, "answer")
        if not row.get("id") or not (answer.ru or answer.kk or answer.en):
            logger.warning("sheet_row_skipped", extra={"tab": "facts", "row": n})
            continue
        facts.append(
            Fact(
                id=row["id"],
                tags=_list(row.get("tags", "")),
                keywords=_list(row.get("keywords", "")),
                question=_loc(row, "question"),
                answer=answer,
            )
        )
    return facts


def places_from_rows(rows: list[dict[str, str]]) -> list[Place]:
    places = []
    for n, row in enumerate(rows, start=2):
        names = _loc(row, "name")
        if not row.get("id") or not row.get("map_name"):
            logger.warning("sheet_row_skipped", extra={"tab": "places", "row": n})
            continue
        places.append(
            Place(
                id=row["id"],
                map_name=row["map_name"],
                names=names
                if (names.ru or names.kk or names.en)
                else LocalizedText(ru=row["map_name"]),
                synonyms=_list(row.get("synonyms", "")),
                description=_loc(row, "description"),
                visual_hints=row.get("visual_hints", ""),
                photos=_list(row.get("photos", "")),
            )
        )
    return places


def buttons_from_rows(rows: list[dict[str, str]]) -> list[QuickQuestion]:
    items = []
    for n, row in enumerate(rows, start=2):
        text = _loc(row, "text")
        if not row.get("id") or not (text.ru or text.kk or text.en):
            logger.warning("sheet_row_skipped", extra={"tab": "buttons", "row": n})
            continue
        items.append(QuickQuestion(id=row["id"], text=text))
    return items


def kb_from_csv(facts_csv: str, places_csv: str, buttons_csv: str) -> KnowledgeBase:
    facts_rows, places_rows, buttons_rows = (
        _tab("facts", facts_csv),
        _tab("places", places_csv),
        _tab("buttons", buttons_csv),
    )
    facts = facts_from_rows(facts_rows)
    if not facts:
        raise SheetError("вкладка фактов пуста — не заменяем базу")
    places = places_from_rows(places_rows)
    buttons = buttons_from_rows(buttons_rows)
    try:
        return build_kb(facts, places, buttons)
    except ValueError as exc:
        raise SheetError(str(exc)) from exc


class SheetKnowledgeSource:
    """Источник «Google-таблица» с фоновой синхронизацией и снимком на диске."""

    def __init__(
        self,
        sheet_id: str,
        tabs: tuple[str, str, str],
        snapshot_path: Path,
        fallback: KnowledgeSource,
        timeout_s: float = 5.0,
    ) -> None:
        self.sheet_id = sheet_id
        self.tabs = tabs
        self.snapshot_path = Path(snapshot_path)
        self.fallback = fallback
        self.timeout_s = timeout_s
        self._kb: KnowledgeBase | None = None
        self.last_error: str | None = None

    # --- интерфейс KnowledgeSource ---
    def fingerprint(self) -> str:
        if self._kb is None:
            return "fallback:" + self.fallback.fingerprint()
        return "sheet:" + self._kb.version

    def load(self) -> KnowledgeBase:
        return self._kb if self._kb is not None else self.fallback.load()

    # --- синхронизация ---
    def urls(self) -> list[str]:
        return [sheet_csv_url(self.sheet_id, tab) for tab in self.tabs]

    def _apply(self, kb: KnowledgeBase) -> bool:
        changed = self._kb is None or self._kb.version != kb.version
        self._kb = kb
        self.last_error = None
        if changed:
            self._save_snapshot(kb)
        logger.info(
            "knowledge_sheet_synced",
            extra={"kb_version": kb.version, "facts": len(kb.facts), "changed": changed},
        )
        return changed

    def _fail(self, exc: Exception) -> None:
        self.last_error = f"{type(exc).__name__}: {exc}"
        logger.error("knowledge_sheet_sync_failed", extra={"error": self.last_error})

    @staticmethod
    def _check(r: httpx.Response) -> str:
        if r.status_code != 200:
            raise SheetError(f"http {r.status_code} for sheet tab")
        return r.text

    def new_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=self.timeout_s, follow_redirects=True)

    async def _fetch_all(self, client: httpx.AsyncClient) -> list[str]:
        responses = await asyncio.wait_for(
            asyncio.gather(*(client.get(u) for u in self.urls())), timeout=self.timeout_s
        )
        return [self._check(r) for r in responses]

    async def sync(self, client: httpx.AsyncClient) -> bool:
        """Загружает все вкладки параллельно (общий таймаут timeout_s). True — база обновлена."""
        try:
            texts = await self._fetch_all(client)
            self._apply(kb_from_csv(*texts))
            return True
        except TimeoutError:
            self._fail(SheetError(f"timeout {self.timeout_s}s loading sheet tabs"))
            return False
        except (httpx.HTTPError, SheetError, ValueError) as exc:
            self._fail(exc)
            return False

    def sync_blocking(self) -> bool:
        """Синхронная обёртка для CLI (вне event loop)."""

        async def once() -> bool:
            async with self.new_client() as client:
                return await self.sync(client)

        return asyncio.run(once())

    async def run_periodic(self, interval_s: float, client: httpx.AsyncClient) -> None:
        """Синк сразу после старта, затем раз в interval_s (interval_s <= 0 — только один раз)."""
        while True:
            await self.sync(client)
            if interval_s <= 0:
                return
            await asyncio.sleep(interval_s)

    # --- снимок ---
    def _save_snapshot(self, kb: KnowledgeBase) -> None:
        try:
            self.snapshot_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.snapshot_path.with_suffix(".tmp")
            tmp.write_text(kb.model_dump_json(), encoding="utf-8")
            os.replace(tmp, self.snapshot_path)
        except OSError as exc:
            logger.warning("knowledge_snapshot_write_failed", extra={"error": str(exc)})

    def load_snapshot(self) -> bool:
        try:
            kb = KnowledgeBase.model_validate_json(self.snapshot_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        self._kb = kb
        logger.warning("knowledge_from_snapshot", extra={"kb_version": kb.version})
        return True

    def initial_load(self) -> None:
        """Старт без сети: снимок, иначе YAML. Таблица подтянется фоновой синхронизацией."""
        if self.load_snapshot():
            return
        logger.warning("knowledge_sheet_no_snapshot_using_yaml")
