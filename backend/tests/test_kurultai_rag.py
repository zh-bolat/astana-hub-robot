"""Поиск робота Курултая (app/kurultai/rag): фрагменты, индекс, гибридный поиск, сводка для промпта."""

from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.models import KurultaiChunkRow, KurultaiMaterialRow
from app.kurultai.rag.index import KurultaiIndexer, material_chunks, snapshot_chunks
from app.kurultai.rag.knowledge import KurultaiKnowledge
from app.kurultai.rag.search import format_hits
from app.kurultai.rag.summary import build_summary
from app.llm.embeddings import FakeEmbeddings
from tests.test_kurultai import fixture_data


class Q:
    def __init__(self, data):
        self.data = data


def _material(engine, **kw) -> int:
    defaults = dict(title="Повестка пленарного заседания 11 ноября", kind="agenda", status="published",
                    brief="Пункт 4: законопроект об альтернативной энергетике, докладывает Годунова Н. Н.",
                    source_text="Регламент. Статья 15. Депутат вправе внести поправку в течение трёх дней.",
                    event_date=date.today() + timedelta(days=1))  # fmt: skip
    defaults.update(kw)
    with Session(engine) as s, s.begin():
        row = KurultaiMaterialRow(**defaults)
        s.add(row)
        s.flush()
        return row.id


def _count(engine) -> int:
    with Session(engine) as s:
        return s.scalar(select(func.count()).select_from(KurultaiChunkRow)) or 0


def test_snapshot_chunks_cover_bills_requests_committees_plans():
    chunks = snapshot_chunks(fixture_data())
    sources = {c.source for c in chunks}
    assert sources >= {"bill", "committee", "request", "plan"}
    committee = next(c for c in chunks if c.source == "committee")
    assert "Состав:" in committee.content and "Членов:" in committee.content
    assert snapshot_chunks(None) == []


def test_material_chunks_brief_and_full_text():
    row = KurultaiMaterialRow(id=7, title="Регламент", kind="reference", brief="Кратко о Регламенте.",
                              source_text="Статья 1. Общие положения.\n\n" + "Текст статьи. " * 400)  # fmt: skip
    chunks = material_chunks([row])
    assert chunks[0].chunk_no == 0 and chunks[0].content == "Кратко о Регламенте."
    assert len(chunks) > 2 and all(c.source == "material" and c.source_id == "7" for c in chunks)


def test_index_search_and_reindex(db):
    _material(db)
    emb = FakeEmbeddings()
    indexer = KurultaiIndexer(db, emb, Q(fixture_data()))
    first = indexer.index()
    assert first.embedded == _count(db) > 0 and first.failed == 0
    again = indexer.index()  # ничего не поменялось — эмбеддинги не пересчитываются
    assert again.embedded == 0 and again.unchanged == _count(db)

    kn = KurultaiKnowledge(db, emb, Q(fixture_data()))
    out = asyncio.run(kn.search("кто докладывает по альтернативной энергетике"))
    assert "Годунова" in out
    out = asyncio.run(kn.search("поправка статья 15 регламента"))
    assert "Статья 15" in out


def test_unpublished_and_expired_materials_leave_index(db):
    mid = _material(db)
    old = _material(db, title="Старая повестка", valid_until=datetime.now(UTC) - timedelta(days=1))
    indexer = KurultaiIndexer(db, FakeEmbeddings(), Q(None))
    indexer.index()
    with Session(db) as s:
        ids = set(s.scalars(select(KurultaiChunkRow.source_id)))
    assert str(mid) in ids and str(old) not in ids  # истёкший не индексируется
    with Session(db) as s, s.begin():
        s.get(KurultaiMaterialRow, mid).status = "draft"
    assert indexer.index().removed > 0 and _count(db) == 0


def test_search_without_embeddings_falls_back_to_words(db):
    _material(db)
    KurultaiIndexer(db, None, Q(None)).index()  # эмбеддер выключен — фрагменты без векторов
    out = asyncio.run(KurultaiKnowledge(db, None, Q(None)).search("Годунова энергетика"))
    assert "Годунова" in out


def test_summary_is_short_and_has_upcoming_agenda(db):
    _material(db)
    _material(db, title="Правила мониторинга законов", kind="reference", brief="Длинные правила " * 50,
              event_date=None)  # fmt: skip
    kn = KurultaiKnowledge(db, None, Q(fixture_data()))
    text = kn.summary()
    assert "Законопроектов всего" in text and "Комитетов" in text
    assert "Годунова" in text  # справка ближайшей повестки — целиком
    assert (
        "Правила мониторинга законов" in text and "Длинные правила" not in text
    )  # прочее — заголовком
    assert len(text) < 9000
    assert len(build_summary(fixture_data(), [], datetime.now(UTC))) < 3000


def test_format_hits_empty():
    assert format_hits([], "x").startswith("Ничего не нашлось")
