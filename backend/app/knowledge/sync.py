"""Синхронизация базы знаний в Postgres и обновление кеша сервера.

- `seed_yaml` — YAML → БД (разработка; автосид при старте, если БД пуста и таблица не задана);
- `KnowledgeSyncer.sync_sheet` — Google-таблица → БД (фоном раз в KNOWLEDGE_SYNC_INTERVAL_S);
- `KnowledgeSyncer.run_refresh` — фоном раз в KNOWLEDGE_RELOAD_INTERVAL_S сверяет kb_sync.version
  и перечитывает базу в память (новые данные подхватываются без перезапуска; так же видны синки,
  сделанные другим контейнером или через CLI).
Вся работа с БД — в потоках (`asyncio.to_thread`), event loop не блокируется.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import httpx

from app.knowledge.pg import DbKnowledgeSource, KnowledgeRepo, SyncResult
from app.knowledge.sheets import SheetError, SheetFetcher
from app.knowledge.source import YamlKnowledgeSource
from app.logging_setup import logger


def seed_yaml(repo: KnowledgeRepo, yaml_source: YamlKnowledgeSource) -> SyncResult:
    started = datetime.now(UTC)
    try:
        kb = yaml_source.load()
    except Exception as exc:
        repo.record_error("yaml", exc, started)
        raise
    return repo.apply(kb, "yaml", started_at=started)


def seed_yaml_if_empty(repo: KnowledgeRepo, yaml_source: YamlKnowledgeSource) -> bool:
    """Сид из YAML, только если в БД ещё нет ни одного факта. True — засеяно."""
    if not repo.is_empty():
        return False
    result = seed_yaml(repo, yaml_source)
    logger.info("knowledge_seeded_from_yaml", extra={"kb_version": result.version})
    return True


class KnowledgeSyncer:
    def __init__(
        self,
        repo: KnowledgeRepo,
        source: DbKnowledgeSource,
        fetcher: SheetFetcher | None = None,
        yaml_seed: YamlKnowledgeSource | None = None,
    ) -> None:
        self.repo = repo
        self.source = source
        self.fetcher = fetcher
        # Сид ещё не удался (БД была недоступна при старте) — повторяем в фоне
        self.pending_seed = yaml_seed
        self.last_error: str | None = None

    # --- блокирующие шаги (в потоке) ---
    def bootstrap(self) -> None:
        """Сид (если нужен) + загрузка базы в память. Ошибки — в лог, не наружу."""
        if self.pending_seed is not None:
            try:
                seed_yaml_if_empty(self.repo, self.pending_seed)
                self.pending_seed = None
            except Exception as exc:
                logger.error("knowledge_seed_failed", extra={"error": _short(exc)})
        self.source.try_refresh()

    # --- Google-таблица → БД ---
    async def sync_sheet(self, client: httpx.AsyncClient) -> bool:
        """True — таблица загружена и записана в БД (изменения или skipped)."""
        assert self.fetcher is not None
        started = datetime.now(UTC)
        try:
            kb = await self.fetcher.fetch(client)
        except SheetError as exc:
            self._fail(exc)
            await asyncio.to_thread(self.repo.record_error, "sheet", str(exc), started)
            return False
        try:
            await asyncio.to_thread(self.repo.apply, kb, "sheet", started_at=started)
        except Exception as exc:  # дубли map_name, БД недоступна — apply уже записал error
            self._fail(exc)
            return False
        self.last_error = None
        await asyncio.to_thread(self.source.try_refresh)
        return True

    def _fail(self, exc: Exception) -> None:
        self.last_error = _short(exc)
        logger.error("knowledge_sheet_sync_failed", extra={"error": self.last_error})

    async def run_sheet_periodic(self, interval_s: float, client: httpx.AsyncClient) -> None:
        """Синк сразу после старта, затем раз в interval_s (interval_s <= 0 — только один раз)."""
        while True:
            await self.sync_sheet(client)
            if interval_s <= 0:
                return
            await asyncio.sleep(interval_s)

    async def run_refresh(self, interval_s: float) -> None:
        while True:
            await asyncio.sleep(max(interval_s, 0.2))
            await asyncio.to_thread(self.bootstrap)


def _short(exc: Exception) -> str:
    orig = getattr(exc, "orig", None)
    return f"{type(exc).__name__}: {str(orig if orig is not None else exc)[:300]}"
