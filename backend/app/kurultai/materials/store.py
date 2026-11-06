"""Материалы аппарата Курултая: хранение (Postgres), обработка файла в фоне и блок для промпта робота.

Жизнь материала: processing (извлечение текста, OCR скана, справка моделью) → draft (сотрудник проверяет и правит)
→ published (идёт в промпт, пока не истёк valid_until) ⇄ draft (снят с публикации); error — обработка не удалась.
Промпт-блок кешируется и пересобирается после любой правки (один процесс uvicorn) и раз в минуту — из-за сроков.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, timezone
from datetime import time as dtime
from typing import Any

import httpx
from openai import AsyncOpenAI
from sqlalchemy import delete, select, update
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import KurultaiMaterialEventRow, KurultaiMaterialRow
from app.kurultai.materials import extract as ex
from app.kurultai.materials import structure as st

logger = logging.getLogger("app.kurultai.materials")
ASTANA_TZ = timezone(timedelta(hours=5))
PROCESSING_CONCURRENCY = 2
BLOCK_TTL_S = 60.0

PROMPT_NOTE = (
    "Ниже материалы аппарата Курултая, загруженные его сотрудниками для тебя. Это самый свежий и приоритетный "
    "источник о заседаниях, мероприятиях, повестке, порядке работы и организационных вопросах: если веб-поиск или "
    "снимок сайта им противоречат — верь материалам. Отвечай по ним точно: время, даты, номера, имена и должности — "
    "как в материалах. Если нужного факта в материалах нет — не придумывай, скажи, что такой информации у тебя нет."
)


def end_of_day(d: date) -> datetime:
    return datetime.combine(d, dtime(23, 59, 59), ASTANA_TZ)


def openrouter_complete(
    settings: Settings, transport: httpx.AsyncBaseTransport | None = None
) -> st.Complete | None:
    """Вызов модели справок/OCR через OpenRouter (ключ сервера); нет ключа — None (обработка выдаст ошибку)."""
    if settings.openrouter_api_key is None or not settings.openrouter_api_key.get_secret_value():
        return None
    client = AsyncOpenAI(
        base_url=settings.openrouter_base_url,
        api_key=settings.openrouter_api_key.get_secret_value(),
        timeout=settings.kurultai_materials_timeout_s,
        max_retries=1,
        http_client=httpx.AsyncClient(
            transport=transport, timeout=settings.kurultai_materials_timeout_s
        )
        if transport is not None
        else None,
    )

    async def complete(messages: list[dict], response_format: dict | None) -> str:
        kw: dict[str, Any] = {
            "model": settings.kurultai_materials_model,
            "messages": messages,
            "temperature": 0,
            "max_tokens": 32_000,
        }
        if response_format is not None:
            kw["response_format"] = response_format
        has_file = any(isinstance(m.get("content"), list) for m in messages)
        if has_file:  # PDF модели — целиком (Gemini читает PDF сам, без внешнего OCR)
            kw["extra_body"] = {"plugins": [{"id": "file-parser", "pdf": {"engine": "native"}}]}
        resp = await client.chat.completions.create(**kw)
        return (resp.choices[0].message.content or "") if resp.choices else ""

    return complete


@dataclass
class BlockStats:
    included: list[int]
    skipped: list[int]  # не поместились в бюджет
    expired: list[int]
    chars: int
    budget: int


class MaterialsStore:
    def __init__(self, engine: Engine, settings: Settings, complete: st.Complete | None,
                 clock: Callable[[], datetime] | None = None) -> None:  # fmt: skip
        self.engine = engine
        self.settings = settings
        self.complete = complete
        self.clock = clock or (lambda: datetime.now(UTC))
        self._version = 0
        self._block: tuple[int, float, str, BlockStats] | None = None
        self._tasks: set[asyncio.Task] = set()
        self._sem = asyncio.Semaphore(PROCESSING_CONCURRENCY)

    # ---------- журнал и служебное ----------
    def _changed(self) -> None:
        self._version += 1

    def _event(self, s: Session, material_id: int | None, action: str, title: str | None, ip: str | None,
               **detail: Any) -> None:  # fmt: skip
        s.add(
            KurultaiMaterialEventRow(
                material_id=material_id, action=action, title=title, client_ip=ip, detail=detail
            )
        )

    def recover_interrupted(self) -> int:
        """После перезапуска: «обрабатывается» навсегда — в ошибку с подсказкой переобработать."""
        with Session(self.engine) as s, s.begin():
            res = s.execute(
                update(KurultaiMaterialRow)
                .where(KurultaiMaterialRow.status == "processing")
                .values(
                    status="error",
                    error="Обработка прервана перезапуском сервера — нажмите «Переобработать».",
                )
            )
        self._changed()
        return res.rowcount or 0

    # ---------- чтение ----------
    def items(self) -> list[dict]:
        now = self.clock()
        stats = self.block_stats()
        with Session(self.engine) as s:
            rows = s.execute(
                select(
                    KurultaiMaterialRow.id, KurultaiMaterialRow.title, KurultaiMaterialRow.kind,
                    KurultaiMaterialRow.status, KurultaiMaterialRow.filename, KurultaiMaterialRow.error,
                    KurultaiMaterialRow.warnings, KurultaiMaterialRow.event_date, KurultaiMaterialRow.valid_until,
                    KurultaiMaterialRow.published_at, KurultaiMaterialRow.created_at, KurultaiMaterialRow.updated_at,
                    KurultaiMaterialRow.brief,
                ).order_by(KurultaiMaterialRow.updated_at.desc(), KurultaiMaterialRow.id.desc())
            ).all()  # fmt: skip
        out = []
        for r in rows:
            item = self._public(r._mapping, now)
            item["brief_chars"] = len(r.brief or "")
            item["in_prompt"] = r.id in stats.included
            item["over_budget"] = r.id in stats.skipped
            out.append(item)
        return out

    def get(self, material_id: int) -> dict | None:
        with Session(self.engine) as s:
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return None
            item = self._public(
                {
                    c.name: getattr(row, c.name)
                    for c in KurultaiMaterialRow.__table__.columns
                    if c.name != "file_bytes"
                },
                self.clock(),
            )
            item["source_text"] = row.source_text
            item["brief"] = row.brief
            item["has_file"] = row.file_bytes is not None
            item["file_size"] = len(row.file_bytes) if row.file_bytes is not None else 0
            return item

    def file(self, material_id: int) -> tuple[str, str, bytes] | None:
        with Session(self.engine) as s:
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None or row.file_bytes is None:
                return None
            return (
                row.filename or f"material-{material_id}",
                row.mime or "application/octet-stream",
                row.file_bytes,
            )

    def events(self, limit: int = 100) -> list[dict]:
        with Session(self.engine) as s:
            rows = s.scalars(
                select(KurultaiMaterialEventRow)
                .order_by(KurultaiMaterialEventRow.id.desc())
                .limit(limit)
            ).all()
            return [
                {"ts": r.ts.isoformat(), "material_id": r.material_id, "action": r.action, "title": r.title,
                 "client_ip": r.client_ip, "detail": r.detail}
                for r in rows
            ]  # fmt: skip

    @staticmethod
    def _public(m: Any, now: datetime) -> dict:
        valid_until = m["valid_until"]
        return {
            "id": m["id"],
            "title": m["title"],
            "kind": m["kind"],
            "kind_name": st.KIND_NAMES.get(m["kind"], m["kind"]),
            "status": m["status"],
            "filename": m["filename"],
            "error": m["error"],
            "warnings": list(m["warnings"] or []),
            "event_date": m["event_date"].isoformat() if m["event_date"] else None,
            "valid_until": valid_until.astimezone(ASTANA_TZ).date().isoformat()
            if valid_until
            else None,
            "expired": bool(valid_until and valid_until < now),
            "published_at": m["published_at"].isoformat() if m["published_at"] else None,
            "created_at": m["created_at"].isoformat() if m["created_at"] else None,
            "updated_at": m["updated_at"].isoformat() if m["updated_at"] else None,
        }

    # ---------- запись ----------
    def create(self, *, title: str, filename: str | None, mime: str | None, data: bytes | None,
               text: str | None, ip: str | None) -> int:  # fmt: skip
        row = KurultaiMaterialRow(
            title=(title or filename or "Материал").strip()[:200],
            status="processing",
            filename=filename,
            mime=mime,
            file_sha256=hashlib.sha256(data).hexdigest() if data is not None else None,
            file_bytes=data,
            source_text=ex.normalize(text) if text is not None else "",
        )
        with Session(self.engine) as s, s.begin():
            s.add(row)
            s.flush()
            self._event(
                s,
                row.id,
                "upload",
                row.title,
                ip,
                filename=filename,
                size=len(data or b"") or len(text or ""),
            )
            material_id = row.id
        self._changed()
        return material_id

    def update(self, material_id: int, ip: str | None, **fields: Any) -> dict | None:
        allowed = {"title", "kind", "brief", "event_date", "valid_until"}
        values = {k: v for k, v in fields.items() if k in allowed}
        if "kind" in values and values["kind"] not in st.KINDS:
            raise ValueError("неизвестный тип материала")
        if "title" in values:
            values["title"] = " ".join(str(values["title"]).split())[:200] or "Материал"
        if "brief" in values:
            values["brief"] = ex.normalize(str(values["brief"]))
        if "valid_until" in values and isinstance(values["valid_until"], date):
            values["valid_until"] = end_of_day(values["valid_until"])
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return None
            if "brief" in values and row.source_text:
                values["warnings"] = st.check_numbers(values["brief"], row.source_text)
            for k, v in values.items():
                setattr(row, k, v)
            row.updated_at = self.clock()
            self._event(s, material_id, "edit", row.title, ip, fields=sorted(values))
        self._changed()
        return self.get(material_id)

    def publish(self, material_id: int, published: bool, ip: str | None) -> dict | None:
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return None
            if published:
                if row.status not in ("draft", "published") or not row.brief.strip():
                    raise ValueError("публиковать можно только готовую справку")
                row.status, row.published_at = "published", self.clock()
            elif row.status == "published":
                row.status = "draft"
            row.updated_at = self.clock()
            self._event(s, material_id, "publish" if published else "unpublish", row.title, ip)
        self._changed()
        return self.get(material_id)

    def delete(self, material_id: int, ip: str | None) -> bool:
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return False
            self._event(s, None, "delete", row.title, ip, deleted_id=material_id)
            s.execute(delete(KurultaiMaterialRow).where(KurultaiMaterialRow.id == material_id))
        self._changed()
        return True

    # ---------- обработка ----------
    def start_processing(self, material_id: int) -> None:
        task = asyncio.get_running_loop().create_task(self.process(material_id))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def wait_idle(self) -> None:
        while self._tasks:
            await asyncio.gather(*list(self._tasks), return_exceptions=True)

    def mark_processing(self, material_id: int, ip: str | None) -> bool:
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None or row.status == "processing":
                return False
            row.status, row.error = "processing", None
            row.updated_at = self.clock()
            self._event(s, material_id, "reprocess", row.title, ip)
        self._changed()
        return True

    async def process(self, material_id: int) -> None:
        async with self._sem:
            try:
                await self._process(material_id)
            except (ex.ExtractError, st.StructureError) as exc:
                self._fail(material_id, str(exc))
            except Exception as exc:
                logger.exception("kurultai_material_process_failed")
                self._fail(
                    material_id,
                    f"Сбой обработки ({type(exc).__name__}). Попробуйте «Переобработать».",
                )

    async def _process(self, material_id: int) -> None:
        with Session(self.engine) as s:
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return
            data, filename, text = row.file_bytes, row.filename or "", row.source_text
        notes: list[str] = []
        if data is not None:
            got = await asyncio.to_thread(ex.extract, data, filename)
            text = got.text
            if got.needs_ocr:
                if self.complete is None:
                    raise st.StructureError(
                        "PDF — скан без текста, а модель для распознавания не настроена"
                    )
                text = ex.normalize(await st.ocr_pdf(data, filename, self.complete))
                notes.append(
                    "Текст распознан со скана моделью — сверьте справку с оригиналом особенно внимательно."
                )
            if got.truncated:
                notes.append(
                    f"Документ очень большой: обработаны первые {ex.MAX_TEXT_CHARS:,} знаков.".replace(
                        ",", " "
                    )
                )
        if self.complete is None:
            raise st.StructureError("модель для справок не настроена (нет ключа OpenRouter)")
        today = self.clock().astimezone(ASTANA_TZ).date()
        result = await st.structure(text, filename, self.complete, today)
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return
            row.source_text = text
            row.brief = result.brief
            row.kind = result.kind
            if not row.title or row.title == (row.filename or "") or row.title == "Материал":
                row.title = result.title
            row.event_date = row.event_date or result.event_date
            if row.valid_until is None and (result.valid_until or result.event_date):
                row.valid_until = end_of_day(result.valid_until or result.event_date)
            row.warnings = notes + result.warnings
            row.status, row.error = "draft", None
            row.updated_at = self.clock()
            self._event(
                s,
                material_id,
                "processed",
                row.title,
                None,
                chars=len(text),
                warnings=len(row.warnings),
            )
        self._changed()

    def _fail(self, material_id: int, message: str) -> None:
        with Session(self.engine) as s, s.begin():
            row = s.get(KurultaiMaterialRow, material_id)
            if row is None:
                return
            row.status, row.error = "error", message[:500]
            row.updated_at = self.clock()
            self._event(s, material_id, "error", row.title, None, error=message[:500])
        self._changed()

    # ---------- промпт ----------
    def _ordered_published(self, now: datetime) -> tuple[list[KurultaiMaterialRow], list[int]]:
        today = now.astimezone(ASTANA_TZ).date()
        with Session(self.engine) as s:
            rows = list(
                s.scalars(
                    select(KurultaiMaterialRow).where(KurultaiMaterialRow.status == "published")
                ).all()
            )
            for r in rows:
                s.expunge(r)
        expired = [r.id for r in rows if r.valid_until is not None and r.valid_until < now]
        live = [r for r in rows if r.id not in expired]

        def rank(r: KurultaiMaterialRow) -> tuple:
            if r.event_date is not None and r.event_date >= today:  # ближайшие события — первыми
                return (0, r.event_date.toordinal(), -r.updated_at.timestamp())
            if r.event_date is None:
                return (1, 0, -r.updated_at.timestamp())
            return (2, -r.event_date.toordinal(), -r.updated_at.timestamp())

        return sorted(live, key=rank), expired

    def _build(self) -> tuple[str, BlockStats]:
        now = self.clock()
        budget = self.settings.kurultai_materials_max_chars
        rows, expired = self._ordered_published(now)
        parts: list[str] = []
        included: list[int] = []
        skipped: list[int] = []
        used = 0
        for r in rows:
            head = [st.KIND_NAMES.get(r.kind, r.kind)]
            if r.event_date:
                head.append(f"дата события {r.event_date.strftime('%d.%m.%Y')}")
            head.append(
                "обновлено " + r.updated_at.astimezone(ASTANA_TZ).strftime("%d.%m.%Y %H:%M")
            )
            part = f"=== {r.title} ({'; '.join(head)}) ===\n{r.brief.strip()}"
            if used + len(part) > budget:
                skipped.append(r.id)
                continue
            parts.append(part)
            included.append(r.id)
            used += len(part) + 2
        block = (PROMPT_NOTE + "\n\n" + "\n\n".join(parts)) if parts else ""
        return block, BlockStats(included, skipped, expired, len(block), budget)

    def _cached(self) -> tuple[str, BlockStats]:
        mono = time.monotonic()
        cached = self._block
        if cached is not None and cached[0] == self._version and mono - cached[1] < BLOCK_TTL_S:
            return cached[2], cached[3]
        try:
            block, stats = self._build()
        except Exception:
            logger.exception("kurultai_materials_block_failed")
            if cached is not None:  # база недоступна — последний собранный блок
                return cached[2], cached[3]
            return "", BlockStats([], [], [], 0, self.settings.kurultai_materials_max_chars)
        self._block = (self._version, mono, block, stats)
        return block, stats

    def prompt_block(self) -> str:
        return self._cached()[0]

    def block_stats(self) -> BlockStats:
        return self._cached()[1]
