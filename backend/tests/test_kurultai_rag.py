"""Поиск робота Курултая (app/kurultai/rag): фрагменты, индекс, гибридный поиск, сводка для промпта."""

from __future__ import annotations

import asyncio
import contextlib
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


def test_request_reindex_wakes_loop_and_material_chunks(db, monkeypatch):
    """Публикация в админке → on_change → индекс обновляется сразу, не через KURULTAI_RAG_REINDEX_S."""
    from app.kurultai.rag import knowledge as kn_mod

    monkeypatch.setattr(kn_mod, "REINDEX_DEBOUNCE_S", 0.05)
    kn = KurultaiKnowledge(db, FakeEmbeddings(), Q(None))

    async def run():
        task = asyncio.create_task(kn.run_periodic(3600))
        await asyncio.sleep(0.3)  # стартовый проход: материалов нет
        assert kn.material_chunks() == {}
        mid = _material(db)
        kn.request_reindex()  # то, что делает MaterialsStore._changed()
        for _ in range(60):
            await asyncio.sleep(0.05)
            if kn.material_chunks():
                break
        assert kn.material_chunks() == {mid: 2}  # справка + 1 фрагмент текста
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    asyncio.run(run())


def test_summary_keeps_todays_briefs_when_there_are_many_materials(db):
    """83 материала (10.11.2026): заголовки не должны вытеснять справки сегодняшнего заседания; новая версия
    повестки — раньше старой; остальное — заголовками и числом."""
    today = date.today()
    _material(
        db,
        title="Повестка (старая версия)",
        brief="Докладчик по энергетике — Годунова.",
        event_date=today,
    )
    for i in range(90):
        _material(db, title=f"Документ номер {i} с довольно длинным официальным названием и уточнениями",
                  kind="reference", brief="Текст справки. " * 40, event_date=today if i % 2 else None)  # fmt: skip
    _material(db, title="Заседание сегодня — главное (актуальная версия)", kind="agenda",
              brief="Докладчик по энергетике — министр энергетики Аккенженов. " * 30, event_date=today)  # fmt: skip
    text = KurultaiKnowledge(db, None, Q(fixture_data())).summary()
    assert "Аккенженов" in text and "Годунова" in text
    assert text.index("Аккенженов") < text.index(
        "Докладчик по энергетике — Годунова"
    )  # новая версия выше старой
    assert "и ещё" in text and "через search_kurultai" in text
    assert len(text) <= 12000 + 200
