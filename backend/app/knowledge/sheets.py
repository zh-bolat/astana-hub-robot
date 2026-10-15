"""База знаний из Google-таблицы (шаг 3.3), без OAuth.

Таблица открыта «всем, у кого есть ссылка — читатель». Вкладки читаются CSV-экспортом:
    https://docs.google.com/spreadsheets/d/<ID>/gviz/tq?tqx=out:csv&sheet=<имя вкладки>
Формат колонок — backend/README.md и шаблоны в backend/knowledge/sheet_template/.

Поведение:
- при старте — загрузка из таблицы; не вышло → последний снимок (data/knowledge_snapshot.json);
  нет снимка → локальные YAML;
- далее фоновая синхронизация раз в KNOWLEDGE_SYNC_INTERVAL_S; при ошибке остаёмся на последней
  удачной версии (и не затираем базу пустой/битой таблицей);
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


def read_csv(text: str) -> list[dict[str, str]]:
    text = text.lstrip("﻿")
    if text.lstrip().startswith("<"):
        raise SheetError("got HTML instead of CSV (таблица не открыта по ссылке?)")
    reader = csv.DictReader(io.StringIO(text))
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
    facts = facts_from_rows(read_csv(facts_csv))
    if not facts:
        raise SheetError("вкладка фактов пуста — не заменяем базу")
    places = places_from_rows(read_csv(places_csv))
    buttons = buttons_from_rows(read_csv(buttons_csv))
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
        timeout_s: float = 10.0,
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

    def sync_blocking(self) -> bool:
        """Синхронная загрузка (старт сервера, CLI). True — удалось."""
        try:
            with httpx.Client(timeout=self.timeout_s, follow_redirects=True) as client:
                texts = [self._check(client.get(u)) for u in self.urls()]
            self._apply(kb_from_csv(*texts))
            return True
        except (httpx.HTTPError, SheetError, ValueError) as exc:
            self._fail(exc)
            return False

    async def sync(self, client: httpx.AsyncClient) -> bool:
        try:
            responses = await asyncio.gather(*(client.get(u) for u in self.urls()))
            texts = [self._check(r) for r in responses]
            self._apply(kb_from_csv(*texts))
            return True
        except (httpx.HTTPError, SheetError, ValueError) as exc:
            self._fail(exc)
            return False

    async def run_periodic(self, interval_s: float) -> None:
        async with httpx.AsyncClient(timeout=self.timeout_s, follow_redirects=True) as client:
            while True:
                await asyncio.sleep(interval_s)
                await self.sync(client)

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
        if self.sync_blocking():
            return
        if self.load_snapshot():
            return
        logger.error("knowledge_sheet_unavailable_using_yaml")
