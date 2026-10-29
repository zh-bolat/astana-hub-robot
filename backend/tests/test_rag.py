"""Поиск по базе (RAG, задача 11): документы, чанкинг, индексация, гибридный поиск, справка к ходу
(RETRIEVAL_MODE=prefetch, по умолчанию), инструмент search_kb (RETRIEVAL_MODE=tool), citations в /v1/chat
и live turn_end.

Эмбеддинги — FakeEmbeddings (без сети, мешок основ слов). Документы — фикстуры ниже: выдуманные тексты для
проверки механики, не факты о хабе.
"""

from __future__ import annotations

import asyncio
import json
import re
import shutil
import time
from contextlib import aclosing
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

from app.agent.prompts import REFERENCE_HEADER
from app.agent.service import LiveTurn, prefetch_query
from app.agent.tools import SEARCH_BUDGET_CHARS, prefer_documents, search_texts
from app.config import Settings
from app.knowledge.chunks import MAX_CHARS, DocumentSource, chunk_document, kb_chunks
from app.knowledge.documents import load_documents, parse_document
from app.knowledge.index import KB_INDEX_LOCK_KEY, KbIndexer
from app.knowledge.models import Fact, LocalizedText, Place
from app.knowledge.pg import KnowledgeRepo
from app.knowledge.search import (
    KbSearch,
    SearchHit,
    Term,
    bm25,
    excerpt,
    query_terms,
    rrf,
    tsquery,
)
from app.knowledge.source import YamlKnowledgeSource, build_kb
from app.knowledge.sync import seed_yaml
from app.llm.embeddings import (
    EmbeddingError,
    FakeEmbeddings,
    OpenRouterEmbeddings,
    build_embedder,
)
from app.llm.fake import FakeProvider
from app.main import create_app
from app.prompts import REFUSALS
from app.schemas import ChatRequest
from tests.conftest import AUTH, ask_body, logged, temp_database
from tests.test_chat import FakeModel, call, events, messages, say
from tests.test_latency import rows as latency_rows
from tests.test_latency import stages
from tests.test_live import live_server, run, session  # noqa: F401 — фикстура live_server

# ---------------------------------------------------------------- фикстуры


def _filler(topic: str, n: int = 12) -> str:
    """Наполнитель раздела (~700 символов), чтобы разделы не склеивались в один фрагмент."""
    return " ".join(
        f"Пункт {i} раздела про {topic} описывает порядок работы." for i in range(1, n + 1)
    )


ORBITA = f"""---
title: Программа «Орбита»
lang: ru
source_url: https://example.test/orbita
active: true
updated: 2026-10-26
---
# Программа «Орбита»

## Кто может участвовать
В программу «Орбита» берут команды из двух или пяти человек с прототипом. {_filler("участников")}

## Сколько длится программа
Программа «Орбита» длится девятнадцать недель, встречи с менторами по четвергам. {_filler("сроков")}

## Стоимость участия
Участие бесплатное, регистрация на сайте orbita-demo.kz до конца месяца. {_filler("оплаты")}
"""

CLUB = """---
title: Лунный клуб
lang: ru
---
# Лунный клуб

Лунный клуб собирается по субботам, вход по зефиролёт-пропуску.
"""

WIFI = Fact(
    id="wifi",
    keywords=["wifi"],
    question=LocalizedText(
        ru="Какой пароль от Wi-Fi?", kk="Wi-Fi құпиясөзі қандай?", en="What is the Wi-Fi password?"
    ),
    answer=LocalizedText(
        ru="Пароль от гостевой сети — на табличке у ресепшена.",
        kk="Қонақ желісінің құпиясөзі ресепшендегі тақтайшада.",
        en="The guest network password is on the sign at the reception.",
    ),
)
CAFE_HOURS = Fact(
    id="cafe_hours",
    question=LocalizedText(ru="Когда работает кафе?"),
    answer=LocalizedText(ru="Кафе работает с восьми утра."),
)
ROOFTOP = Fact(
    id="rooftop",
    question=LocalizedText(ru="Есть ли терраса на крыше?", kk="Шатырда терраса бар ма?"),
    answer=LocalizedText(ru="Терраса на крыше открыта летом.", kk="Шатырдағы терраса жазда ашық."),
)
CAFE = Place(
    id="cafe",
    map_name="cafe",
    names=LocalizedText(ru="Кафе", kk="Кафе", en="Cafe"),
    synonyms=["кофейня"],
    description=LocalizedText(ru="Кафе на первом этаже."),
)


@pytest.fixture
def docs_dir(knowledge_dir: Path) -> Path:
    """KNOWLEDGE_DIR/documents тестов — только фикстуры (настоящие документы не мешают проверкам)."""
    d = knowledge_dir / "documents"
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir()
    (d / "orbita.md").write_text(ORBITA, encoding="utf-8")
    (d / "club.md").write_text(CLUB, encoding="utf-8")
    return d


def put(engine, facts=(), places=(), docs=()) -> None:
    """База в Postgres: факты, места, документы [(slug, текст)] — как сид из YAML."""
    KnowledgeRepo(engine).apply(
        build_kb(list(facts), list(places), []),
        "yaml",
        documents=[parse_document(slug, body) for slug, body in docs],
    )


def find(engine, embedder, query: str, lang: str = "ru", k: int = 3, **kw) -> list[SearchHit]:
    return asyncio.run(KbSearch(engine, embedder, **kw).search(query, lang, k=k))  # type: ignore[arg-type]


def chunk_rows(engine) -> dict[tuple, tuple]:
    """(source_type, source_id, lang, chunk_no) → (content, is_active) всех строк kb_chunks."""
    with engine.connect() as conn:
        rows = conn.execute(
            text("SELECT source_type, source_id, lang, chunk_no, content, is_active FROM kb_chunks")
        )
        return {(r[0], r[1], r[2].strip(), r[3]): (r[4], r[5]) for r in rows}


class SynonymEmbeddings(FakeEmbeddings):
    """«По смыслу» для фейка: автомобиль ≈ машина (у полнотекста таких знаний нет)."""

    def vector(self, value: str) -> list[float]:
        return super().vector(re.sub(r"автомобил\w*", "машины", value, flags=re.IGNORECASE))


class ConstantEmbeddings(FakeEmbeddings):
    """Все векторы одинаковые: по смыслу не различает ничего, порядок решает полнотекст."""

    def vector(self, value: str) -> list[float]:
        return [1.0] + [0.0] * (self.dim - 1)


class SlowEmbeddings(FakeEmbeddings):
    async def embed_query(self, value: str) -> list[float]:
        await asyncio.sleep(2)
        return self.vector(value)


# ---------------------------------------------------------------- документы и чанкинг


def test_parse_document_front_matter_and_defaults():
    d = parse_document("orbita", ORBITA)
    assert (d.title, d.lang, d.source_url, d.active) == (
        "Программа «Орбита»",
        "ru",
        "https://example.test/orbita",
        True,
    )
    assert d.content.startswith("# Программа «Орбита»")
    assert "source_url" not in d.content and "---" not in d.content
    plain = parse_document("plain", "# Заголовок файла\n\nТекст.")
    assert (plain.title, plain.lang, plain.source_url, plain.active) == (
        "Заголовок файла",
        "ru",
        None,
        True,
    )
    assert parse_document("no_title", "Просто текст.").title == "no_title"
    assert parse_document("off", "---\nactive: нет\n---\nТекст").active is False
    assert parse_document("off2", "---\nactive: false\n---\nТекст").active is False
    for slug, body in [
        ("Bad-Slug", "Текст"),
        ("empty", "---\ntitle: T\n---\n"),
        ("de", "---\nlang: de\n---\nText"),
        ("open", "---\ntitle: T\nТекст"),
    ]:
        with pytest.raises(ValueError):
            parse_document(slug, body)


def test_load_documents_skips_bad_files(tmp_path):
    assert load_documents(tmp_path).documents == []  # папки нет — ноль документов
    d = tmp_path / "documents"
    d.mkdir()
    (d / "ok.md").write_text("# Ок\n\nТекст.", encoding="utf-8")
    (d / "Bad-Name.md").write_text("Текст", encoding="utf-8")
    (d / "broken.md").write_text("---\ntitle: [\n---\nТекст", encoding="utf-8")
    (d / "notes.txt").write_text("не документ", encoding="utf-8")
    load = load_documents(tmp_path)
    assert [x.slug for x in load.documents] == ["ok"]
    assert [e.split(":")[0] for e in load.errors] == ["Bad-Name.md", "broken.md"]


def test_small_sections_are_merged_with_headings_in_text():
    body = (
        "# Документ\n\nВводный абзац.\n\n## Раздел А\nТекст А.\n\n### Подраздел\nТекст подраздела."
        "\n\n## Раздел Б\nТекст Б."
    )
    assert chunk_document("Документ", body) == [
        (
            "Документ",
            "Вводный абзац.\n\nРаздел А\nТекст А.\n\nРаздел А — Подраздел\nТекст подраздела."
            "\n\nРаздел Б\nТекст Б.",
        )
    ]


def test_sections_become_chunks_titled_document_and_section():
    def big(word: str) -> str:
        return " ".join(f"{word} предложение номер {i}." for i in range(40))  # ~1100 символов

    body = f"# Документ\n\n## Раздел А\n{big('а')}\n\n### Подраздел\n{big('б')}\n\n## Раздел Б\n{big('в')}"
    chunks = chunk_document("Документ", body)
    assert [t for t, _ in chunks] == [
        "Документ — Раздел А",
        "Документ — Раздел А — Подраздел",
        "Документ — Раздел Б",
    ]
    assert chunks[1][1] == big("б")  # заголовок — в title, не в тексте


def test_large_section_is_split_with_paragraph_overlap():
    paras = [
        " ".join(f"Абзац {p}, мысль {i} продолжается здесь." for i in range(6)) for p in range(12)
    ]
    chunks = chunk_document("Док", "## Большой раздел\n\n" + "\n\n".join(paras))
    assert len(chunks) >= 2
    assert all(len(c) <= MAX_CHARS for _, c in chunks)
    assert {t for t, _ in chunks} == {"Док — Большой раздел"}
    first, second = chunks[0][1], chunks[1][1]
    assert second.startswith(first.split("\n\n")[-1])  # перекрытие — последний абзац (≤ 300)
    joined = "\n\n".join(c for _, c in chunks)
    assert all(p in joined for p in paras)


def test_long_paragraphs_overlap_by_sentences_and_never_exceed_max():
    paras = [
        " ".join(
            f"Абзац {p}, длинная мысль номер {i} продолжается и продолжается." for i in range(10)
        )
        for p in range(6)
    ]
    chunks = chunk_document("Док", "\n\n".join(paras))
    assert len(chunks) >= 2 and all(len(c) <= MAX_CHARS for _, c in chunks)
    head = chunks[1][1].split("\n\n")[0]
    assert len(head) <= 300 and chunks[0][1].endswith(head)
    huge = " ".join(f"Предложение {i} без переносов строк идёт дальше." for i in range(200))
    pieces = chunk_document("Док", huge)
    assert len(pieces) >= 4 and all(len(c) <= MAX_CHARS for _, c in pieces)


def test_kb_chunks_for_facts_places_and_documents():
    kb = build_kb([WIFI, CAFE_HOURS], [CAFE], [])
    doc = parse_document("orbita", ORBITA)
    specs = kb_chunks(kb, [DocumentSource("orbita", doc.title, "ru", doc.content, doc.source_url)])
    assert set(specs) == {
        ("fact", "wifi", "ru", 0),
        ("fact", "wifi", "kk", 0),
        ("fact", "wifi", "en", 0),
        ("fact", "cafe_hours", "ru", 0),
        ("place", "cafe", "ru", 0),
        ("place", "cafe", "kk", 0),
        ("place", "cafe", "en", 0),
        ("document", "orbita", "ru", 1),
        ("document", "orbita", "ru", 2),
        ("document", "orbita", "ru", 3),
    }
    kk = specs[("fact", "wifi", "kk", 0)]
    assert (kk.title, kk.content) == (
        "Wi-Fi құпиясөзі қандай?",
        "Wi-Fi құпиясөзі қандай?\nҚонақ желісінің құпиясөзі ресепшендегі тақтайшада.",
    )
    assert specs[("place", "cafe", "ru", 0)].content == "Кафе\nкофейня\nКафе на первом этаже."
    second = specs[("document", "orbita", "ru", 2)]
    assert second.title == "Программа «Орбита» — Сколько длится программа"
    assert second.content.startswith("Программа «Орбита» длится девятнадцать недель")
    assert all("source_url" not in s.content for s in specs.values())


def test_seed_yaml_loads_documents_and_deactivates_removed(db, knowledge_dir, docs_dir):
    repo, source = KnowledgeRepo(db), YamlKnowledgeSource(knowledge_dir)

    def docs() -> dict[str, bool]:
        with db.connect() as conn:
            return dict(conn.execute(text("SELECT slug, is_active FROM documents")).all())

    first = seed_yaml(repo, source)
    assert first.counts["documents"] == {"inserted": 2, "updated": 0, "deactivated": 0}
    assert docs() == {"orbita": True, "club": True}
    again = seed_yaml(repo, source)
    assert again.status == "skipped"
    assert again.counts["documents"] == {"inserted": 0, "updated": 0, "deactivated": 0}

    (docs_dir / "club.md").unlink()
    (docs_dir / "orbita.md").write_text(ORBITA.replace("active: true", "active: false"), "utf-8")
    (docs_dir / "Bad Name.md").write_text("Текст", encoding="utf-8")
    third = seed_yaml(repo, source)
    assert third.status == "ok"
    assert third.counts["documents"] == {"inserted": 0, "updated": 1, "deactivated": 1}
    assert third.skipped and third.skipped[0].startswith("Bad Name.md:")
    assert docs() == {"orbita": False, "club": False}

    # синк Google-таблицы документы не трогает
    (docs_dir / "orbita.md").write_text(ORBITA, encoding="utf-8")
    seed_yaml(repo, source)
    sheet = repo.apply(source.load(), "sheet")
    assert "documents" not in sheet.counts
    assert docs() == {"orbita": True, "club": False}


# ---------------------------------------------------------------- индексация


def test_index_is_incremental_and_reembeds_only_changed_chunks(db):
    emb = FakeEmbeddings()
    indexer = KbIndexer(db, emb)
    put(db, [WIFI], docs=[("orbita", ORBITA)])

    first = indexer.index()
    assert (first.embedded, first.unchanged, first.removed, first.failed) == (6, 0, 0, 0)
    assert first.by_type == {"fact": 3, "document": 3} and emb.texts == 6

    calls = emb.calls
    again = indexer.index()
    assert (again.embedded, again.unchanged, again.calls) == (0, 6, 0)
    assert emb.calls == calls and emb.texts == 6  # повторный запуск — ноль вызовов эмбеддингов

    # правка одного раздела одного документа → пересчитан только его фрагмент
    put(db, [WIFI], docs=[("orbita", ORBITA.replace("девятнадцать", "двадцать"))])
    edited = indexer.index()
    assert (edited.embedded, edited.unchanged) == (1, 5) and emb.texts == 7
    content, active = chunk_rows(db)[("document", "orbita", "ru", 2)]
    assert "двадцать недель" in content and active

    forced = indexer.index(force=True)
    assert forced.embedded == 6 and emb.texts == 13


def test_disabled_fact_disappears_from_search_and_comes_back_without_embedding(db):
    emb = FakeEmbeddings()
    indexer = KbIndexer(db, emb)
    put(db, [WIFI, CAFE_HOURS])
    indexer.index()
    assert "fact:wifi" in [h.id for h in find(db, emb, "пароль от Wi-Fi")]

    put(db, [CAFE_HOURS])  # факт выключен в базе
    report = indexer.index()
    assert (report.removed, report.embedded) == (3, 0)
    assert "fact:wifi" not in [h.id for h in find(db, emb, "пароль от Wi-Fi")]
    assert not any(
        active for (t, sid, _, _), (_, active) in chunk_rows(db).items() if sid == "wifi"
    )

    put(db, [WIFI, CAFE_HOURS])  # вернули тем же текстом
    back = indexer.index()
    assert (back.restored, back.embedded) == (3, 0)
    assert "fact:wifi" in [h.id for h in find(db, emb, "пароль от Wi-Fi")]


def test_embedding_failure_keeps_old_chunks_but_applies_removals(db):
    emb = FakeEmbeddings()
    put(db, [WIFI], docs=[("orbita", ORBITA)])
    KbIndexer(db, emb).index()

    put(db, [], docs=[("orbita", ORBITA.replace("девятнадцать", "двадцать"))])
    failing = FakeEmbeddings(fail=True)  # та же модель, провайдер недоступен
    report = KbIndexer(db, failing).index()
    assert not report.ok and report.failed == 1 and "EmbeddingError" in report.error
    assert report.removed == 3  # выключенный факт пропал из поиска и без провайдера
    content, active = chunk_rows(db)[("document", "orbita", "ru", 2)]
    assert "девятнадцать" in content and active  # старый фрагмент остался

    ok = KbIndexer(db, emb).index()
    assert ok.ok and ok.embedded == 1
    assert "двадцать" in chunk_rows(db)[("document", "orbita", "ru", 2)][0]


def test_index_skips_while_another_process_indexes(db):
    put(db, [WIFI])
    with db.connect() as conn:
        conn.execute(text("SELECT pg_advisory_lock(:k)"), {"k": KB_INDEX_LOCK_KEY})
        try:
            assert KbIndexer(db, FakeEmbeddings()).index().skipped
        finally:
            conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": KB_INDEX_LOCK_KEY})
            conn.commit()
    assert KbIndexer(db, FakeEmbeddings()).index().embedded == 3


def test_check_reindexes_only_after_changes_and_backs_off_on_errors(db):
    put(db, [WIFI])
    indexer = KbIndexer(db, FakeEmbeddings())
    assert indexer.check().embedded == 3  # старт: фрагментов нет
    assert indexer.check() is None  # ничего не менялось — даже не сравнивает фрагменты
    put(db, [WIFI], docs=[("club", CLUB)])
    assert indexer.check().embedded == 1  # новый документ (отпечаток документов)
    put(db, [WIFI, CAFE_HOURS], docs=[("club", CLUB)])
    assert indexer.check().embedded == 1  # новый факт (версия синка)

    broken = KbIndexer(db, FakeEmbeddings(fail=True))
    put(db, [WIFI, CAFE_HOURS, ROOFTOP], docs=[("club", CLUB)])
    assert broken.check().ok is False
    assert broken.check() is None  # повтор — не раньше чем через RETRY_MIN_S


# ---------------------------------------------------------------- поиск


def test_query_terms_drop_stopwords_and_use_prefixes():
    terms = {
        t.stem: t.prefix
        for t in query_terms("Какие льготы у резидентов Astana Hub в 2026 году? LRT")
    }
    assert terms == {"льго": True, "резиден": True, "2026": False, "год": True, "lrt": False}
    assert tsquery([Term("льго", True), Term("2026", False)]) == "льго:* | 2026"
    assert query_terms("как где что") == []
    # названия — целиком, как их индексирует PG ('c4.6', 'alem.ai', 'wi-fi')
    assert {t.stem: t.prefix for t in query_terms("Переговорная в C4.6 и alem.ai, Wi-Fi?")} == {
        "перегов": True,
        "c4.6": False,
        "alem.ai": False,
        "wi-fi": False,
    }


def test_bm25_rare_word_outweighs_common_and_long_chunks_are_penalized():
    a, b, c = (("fact", x, 0) for x in "abc")
    # слово 0 — во всех трёх фрагментах (частое), слово 1 — только в c (редкое)
    rows = [(*a, 10, True, False), (*b, 10, True, False), (*c, 10, True, True)]
    assert bm25(rows, 2, total=100, avg_len=10)[0] == c
    rows = [(*a, 50, True, False), (*b, 5, True, False)]  # те же слова, b короче
    assert bm25(rows, 2, total=100, avg_len=20) == [b, a]


def test_tool_prefers_documents_because_facts_are_in_prompt():
    def hit(i: str, kind: str) -> SearchHit:
        return SearchHit(i, kind, i, "текст", None, "ru", 0.1)

    hits = [hit("fact:a", "fact"), hit("document:d#1", "document"), hit("fact:b", "fact")]
    hits += [hit("place:p", "place"), hit("document:d#2", "document")]
    assert [h.id for h in prefer_documents(hits)] == ["document:d#1", "document:d#2", "fact:a"]
    assert [h.id for h in prefer_documents(hits[:3])] == ["document:d#1", "fact:a", "fact:b"]


def test_rrf_merges_lists_and_collapses_language_duplicates():
    a, b, c, d = (("fact", x, 0) for x in "abcd")
    assert [key for key, _ in rrf([[a, b, c], [c, d]])] == [c, a, b, d]
    scores = dict(rrf([[a, a, b]]))
    assert scores[a] == pytest.approx(1 / 61) and scores[b] == pytest.approx(1 / 62)


def test_search_finds_by_meaning_only(db):
    parking = "---\ntitle: Парковка\n---\nМашины паркуют на подземной стоянке под павильоном."
    garden = "---\ntitle: Сад\n---\nСад открыт для гостей по выходным."
    put(db, docs=[("garden", garden), ("parking", parking)])
    query = "куда поставить автомобиль"
    with db.connect() as conn:  # по словам не находится ничего
        n = conn.execute(
            text("SELECT count(*) FROM kb_chunks WHERE tsv @@ to_tsquery('simple', :q)"),
            {"q": tsquery(query_terms(query))},
        ).scalar()
    assert n == 0

    plain = FakeEmbeddings()
    KbIndexer(db, plain).index()
    assert find(db, plain, query)[0].id == "document:garden#1"  # без «смысла» — просто первый

    smart = SynonymEmbeddings()
    KbIndexer(db, smart).index(force=True)
    hits = find(db, smart, query)
    assert hits[0].id == "document:parking#1" and hits[0].url is None


def test_search_finds_by_words_only_and_rrf_lifts_word_match(db):
    put(
        db,
        docs=[
            ("aaa", "Обычный текст про кофе."),
            ("bbb", "Ещё текст про чай."),
            ("zzz", "Пропуск зефиролёт выдают на входе."),
        ],
    )
    emb = ConstantEmbeddings()
    KbIndexer(db, emb).index()
    hits = find(db, emb, "зефиролёт пропуск")
    assert [h.id for h in hits] == ["document:zzz#1", "document:aaa#1", "document:bbb#1"]
    assert hits[0].score > hits[1].score


def test_search_falls_back_to_full_text_when_embedding_fails_or_is_slow(db):
    put(db, [WIFI], docs=[("club", CLUB)])
    KbIndexer(db, FakeEmbeddings()).index()
    hits = find(db, FakeEmbeddings(fail=True), "зефиролёт")
    assert [h.id for h in hits] == ["document:club#1"]  # только полнотекст
    started = time.perf_counter()
    hits = find(db, SlowEmbeddings(), "зефиролёт", query_timeout_s=0.2)
    assert [h.id for h in hits] == ["document:club#1"]
    assert time.perf_counter() - started < 1.5


def test_search_prefers_question_language_then_ru_and_dedups(db):
    emb = FakeEmbeddings()
    put(db, [WIFI, CAFE_HOURS, ROOFTOP])
    KbIndexer(db, emb).index()

    hits = find(db, emb, "пароль от Wi-Fi", lang="kk")
    wifi = [h for h in hits if h.id == "fact:wifi"]
    assert len(wifi) == 1 and wifi[0].lang == "kk"  # один результат, на языке вопроса
    assert wifi[0].content.startswith("Wi-Fi құпиясөзі")
    assert wifi[0].type == "fact" and wifi[0].url is None

    cafe = next(
        h for h in find(db, emb, "когда работает кафе", lang="kk") if h.id == "fact:cafe_hours"
    )
    assert cafe.lang == "ru"  # казахского нет — русский
    roof = next(h for h in find(db, emb, "терраса на крыше", lang="en") if h.id == "fact:rooftop")
    assert roof.lang == "ru"  # английского нет — ru раньше kk


def test_excerpt_and_tool_budget():
    lines = [f"Строка {i} про общие вещи и прочее." for i in range(60)]
    content = "\n".join([*lines[:30], "Взнос за демо-день — семь тысяч тенге.", *lines[30:]])
    part = excerpt(content, "взнос за демо-день", 300)
    assert "семь тысяч тенге" in part and len(part) <= 320
    assert excerpt(content, "несуществующее", 200).startswith("Строка 0")
    assert excerpt("Коротко.", "что угодно", 100) == "Коротко."

    def hit(i: int, body: str) -> SearchHit:
        return SearchHit(f"document:x#{i}", "document", "T", body, None, "ru", 0.1)

    long = "\n".join(f"Предложение {j} про разное и всякое." for j in range(120))
    texts = search_texts([hit(1, long), hit(2, long), hit(3, long)], "разное")
    assert all(texts) and sum(map(len, texts)) <= SEARCH_BUDGET_CHARS + 30
    short_first = search_texts([hit(1, "Коротко."), hit(2, long), hit(3, long)], "разное")
    assert len(short_first[1]) > SEARCH_BUDGET_CHARS // 3  # недобор первого — следующим


# ---------------------------------------------------------------- провайдер эмбеддингов


def _vectors(n: int, dim: int = 768) -> list[dict]:
    return [{"index": i, "embedding": [1.0] + [0.0] * (dim - 1)} for i in range(n)]


def test_openrouter_embeddings_batches_prefixes_and_retries():
    seen: list[tuple[httpx.Request, dict]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append((request, body))
        if len(seen) == 1:
            return httpx.Response(429, json={"error": {"message": "rate limited"}})
        return httpx.Response(200, json={"data": _vectors(len(body["input"]))})

    emb = OpenRouterEmbeddings(
        "secret",
        "google/gemini-embedding-2",
        base_url="http://fake-openrouter/api/v1",
        transport=httpx.MockTransport(handler),
        retry_delays_s=(0, 0),
    )
    vecs = emb.embed_documents([(f"T{i}", f"текст {i}") for i in range(70)])
    assert len(vecs) == 70 and len(vecs[0]) == 768
    assert [len(b["input"]) for _, b in seen] == [64, 64, 6]  # 429 → повтор того же батча
    request, body = seen[1]
    assert request.url.path == "/api/v1/embeddings"
    assert request.headers["authorization"] == "Bearer secret"
    assert body["input"][0] == "title: T0 | text: текст 0"
    assert body["model"] == "google/gemini-embedding-2" and body["dimensions"] == 768


def test_openrouter_embeddings_errors_and_query():
    calls: list[int] = []

    def respond(status: int, payload: object = None) -> OpenRouterEmbeddings:
        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(status)
            return httpx.Response(status, json=payload)

        return OpenRouterEmbeddings(
            "k",
            "m",
            base_url="http://x/api/v1",
            transport=httpx.MockTransport(handler),
            retry_delays_s=(0, 0),
        )

    with pytest.raises(EmbeddingError):
        respond(503).embed_documents([("t", "a")])
    assert calls == [503, 503, 503]  # 3 попытки, затем ошибка
    with pytest.raises(EmbeddingError):
        respond(400, {"error": "bad"}).embed_documents([("t", "a")])
    assert calls[3:] == [400]  # не повторяется
    with pytest.raises(EmbeddingError):  # не та размерность
        respond(200, {"data": _vectors(1, 512)}).embed_documents([("t", "a")])
    with pytest.raises(EmbeddingError):  # 200 с ошибкой внутри
        respond(200, {"error": {"code": 502}}).embed_documents([("t", "a")])

    asked: list[dict] = []

    async def ahandler(request: httpx.Request) -> httpx.Response:
        asked.append(json.loads(request.content))
        return httpx.Response(
            200, json={"data": [{"index": 0, "embedding": [3.0, 4.0] + [0.0] * 766}]}
        )

    emb = OpenRouterEmbeddings(
        "k", "m", base_url="http://x/api/v1", async_transport=httpx.MockTransport(ahandler)
    )
    vec = asyncio.run(emb.embed_query("где кафе"))
    assert asked[0]["input"] == ["task: search result | query: где кафе"]
    assert vec[:2] == pytest.approx([0.6, 0.8])  # L2-норма


def test_embedding_and_retrieval_settings():
    base = {"_env_file": None, "robot_token": "t"}
    off = Settings(**base, openrouter_api_key=None, embedding_provider="auto")
    assert (off.resolved_embedding_provider(), off.resolved_retrieval_mode()) == ("off", "off")
    assert build_embedder(off) is None
    on = Settings(**base, openrouter_api_key="k", embedding_provider="auto")
    assert (on.resolved_embedding_provider(), on.resolved_retrieval_mode()) == (
        "openrouter",
        "prefetch",  # по умолчанию — справка к ходу
    )
    for mode, kind in [("tool", "tool"), ("hybrid", "tool"), ("prefetch", "prefetch")]:
        s = Settings(**base, openrouter_api_key="k", retrieval_mode=mode)
        assert (s.retrieval_kind(), s.resolved_retrieval_mode()) == (kind, kind)
    assert Settings(**base, retrieval_mode="hybrid").resolved_retrieval_mode() == "off"  # без ключа
    emb = build_embedder(on)
    assert isinstance(emb, OpenRouterEmbeddings) and emb.model == "google/gemini-embedding-2"
    asyncio.run(emb.aclose())
    assert (
        Settings(**base, openrouter_api_key="k", retrieval_mode="off").resolved_retrieval_mode()
        == "off"
    )
    fake = Settings(**base, embedding_provider="fake")
    assert fake.resolved_retrieval_mode() == "prefetch" and isinstance(
        build_embedder(fake), FakeEmbeddings
    )
    assert (
        Settings(**base, openrouter_api_key="k", embedding_provider="off").resolved_retrieval_mode()
        == "off"
    )


# ---------------------------------------------------------------- агент /v1/chat, RETRIEVAL_MODE=tool

TOOL = {"retrieval_mode": "tool"}  # модель сама вызывает search_kb (до prefetch — режим hybrid)

QUESTION = "Сколько недель длится программа Орбита?"
ORBITA_2 = {
    "id": "document:orbita#2",
    "type": "document",
    "title": "Программа «Орбита» — Сколько длится программа",
    "url": "https://example.test/orbita",
}


def rag_client(make_client, model: FakeModel, **overrides) -> TestClient:
    """Сервер с поиском (фейковые эмбеддинги) и проиндексированной базой: YAML-факты + документы-фикстуры."""
    client = make_client(chat_client=model.client(), embedding_provider="fake", **overrides)
    report = client.app.state.indexer.index()
    assert report.ok and report.by_type["document"] == 4
    return client


def tool_output(model: FakeModel, request_no: int) -> dict:
    tool = [m for m in model.requests[request_no]["messages"] if m["role"] == "tool"][-1]
    return json.loads(tool["content"])


def test_chat_answers_document_detail_with_citation(make_client, docs_dir):
    model = FakeModel(
        call("search_kb", {"query": "сколько недель длится программа Орбита"}),
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
    )
    client = rag_client(make_client, model, **TOOL)

    evs = events(client, QUESTION, lang="ru")

    assert [e for e, _ in evs] == ["phrase", "done"]
    assert "девятнадцать недель" in evs[0][1]["text"]
    done = evs[-1][1]
    assert ORBITA_2 in done["citations"]
    assert "document:orbita#2" in done["sources"] and done["found"] is True
    assert len({c["id"] for c in done["citations"]}) == len(done["citations"]) <= 3
    # модель получила найденный текст (до 3 фрагментов, ≤ ~2500 символов текста)
    found = tool_output(model, 1)
    assert found["ok"] and any("девятнадцать недель" in r["text"] for r in found["results"])
    assert sum(len(r["text"]) for r in found["results"]) <= SEARCH_BUDGET_CHARS + 30
    row = logged(client)[0]
    assert row.found and "document:orbita#2" in row.sources
    assert any(name == "search_kb" for _, _, name in messages(client))


def test_chat_json_mode_has_citations(make_client, docs_dir):
    model = FakeModel(
        call("search_kb", {"query": "сколько недель длится программа Орбита"}),
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
    )
    client = rag_client(make_client, model, **TOOL)
    body = client.post("/v1/chat", json=ask_body(QUESTION, lang="ru"), headers=AUTH).json()
    assert ORBITA_2 in body["citations"] and "document:orbita#2" in body["sources"]
    assert "девятнадцать недель" in body["answer"]


def test_no_search_no_citations_and_tool_only_with_retrieval(make_client, docs_dir):
    model = FakeModel(say("[happy] Здравствуйте, чем помочь?"))
    client = make_client(chat_client=model.client(), embedding_provider="fake", **TOOL)
    evs = events(client, "Расскажи о хабе", lang="ru")
    assert evs[-1][1]["citations"] == []
    req = model.requests[0]
    assert "search_kb" in {t["function"]["name"] for t in req["tools"]}
    assert "search_kb(query)" in req["messages"][0]["content"][0]["text"]

    off = FakeModel(say("[happy] Здравствуйте."))
    client_off = make_client(
        chat_client=off.client(), embedding_provider="fake", retrieval_mode="off"
    )
    assert client_off.app.state.kb_search is None and client_off.app.state.indexer is None
    events(client_off, "Расскажи о хабе", lang="ru")
    assert "search_kb" not in {t["function"]["name"] for t in off.requests[0]["tools"]}
    assert "search_kb" not in off.requests[0]["messages"][0]["content"][0]["text"]


def test_link_from_found_document_passes_output_filter(make_client, docs_dir):
    """Название/домен из документа (в фактах его нет) — не выдумка модели: фильтр ссылок пропускает."""
    model = FakeModel(
        call("search_kb", {"query": "регистрация на программу Орбита"}),
        say("[happy] Регистрация на сайте orbita-demo.kz."),
    )
    client = rag_client(make_client, model, **TOOL)
    evs = events(client, "Где зарегистрироваться на Орбиту?", lang="ru")
    assert evs[0] == ("phrase", {"text": "Регистрация на сайте orbita-demo.kz."})
    assert "document:orbita#3" in [c["id"] for c in evs[-1][1]["citations"]]
    assert logged(client)[0].category == "agent"


def test_link_without_search_is_still_blocked(make_client, docs_dir):
    model = FakeModel(say("[happy] Регистрация на сайте orbita-demo.kz."))
    client = rag_client(make_client, model, **TOOL)
    events(client, "Где зарегистрироваться на Орбиту?", lang="ru")
    assert logged(client)[0].category == "blocked"


def test_nothing_suitable_after_search_is_logged_unanswered(make_client, docs_dir):
    model = FakeModel(call("search_kb", {"query": "камера хранения"}), call("no_answer"))
    client = rag_client(make_client, model, **TOOL)
    evs = events(client, "Есть ли камера хранения?", lang="ru")
    assert " ".join(d["text"] for e, d in evs if e == "phrase") == REFUSALS["ru"]
    done = evs[-1][1]
    assert (done["found"], done["sources"], done["citations"]) == (False, [], [])
    row = logged(client, only_unanswered=True)[0]
    assert row.category == "out_of_scope"


class UniqueCallsModel(FakeModel):
    """Повторные вызовы одного инструмента — с разными id (SDK не принимает повтор id)."""

    async def handle(self, request: httpx.Request) -> httpx.Response:
        resp = await super().handle(request)
        body = resp.content.replace(
            b'"call_search_kb"', f'"call_search_kb_{len(self.requests)}"'.encode()
        )
        return httpx.Response(
            resp.status_code, content=body, headers={"content-type": "text/event-stream"}
        )


def test_repeated_search_is_limited_and_citations_dedup(make_client, docs_dir):
    model = UniqueCallsModel(
        call("search_kb", {"query": "программа Орбита"}),
        call("search_kb", {"query": "программа Орбита"}),
        call("search_kb", {"query": "программа Орбита сроки"}),
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
    )
    client = rag_client(make_client, model, **TOOL)
    evs = events(client, QUESTION, lang="ru")
    assert tool_output(model, 2)["ok"] is True  # повтор — можно один раз
    assert "limit" in tool_output(model, 3)["error"]  # третий поиск — нет
    ids = [c["id"] for c in evs[-1][1]["citations"]]
    assert ids and len(ids) == len(set(ids))


def test_live_turn_end_has_sources_and_citations(live_server, docs_dir):  # noqa: F811
    model = FakeModel(
        call("search_kb", {"query": "сколько недель длится программа Орбита"}),
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
    )
    url, app, _ = live_server(
        model, script=[QUESTION], live_fast_places=False, embedding_provider="fake", **TOOL
    )
    assert app.state.indexer.index().ok

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.6)
            end = await c.wait("turn_end", timeout=12)
            assert "document:orbita#2" in end["sources"]
            assert ORBITA_2 in end["citations"]
            assert [p["text"] for p in c.events("phrase")] == [
                "Программа «Орбита» длится девятнадцать недель."
            ]

    run(scenario())


# ---------------------------------------------------------------- справка к ходу, RETRIEVAL_MODE=prefetch


def spy_search(app) -> list[str]:
    """Запросы поиска по базе за время теста (поиск при этом работает как обычно)."""
    search = app.state.kb_search
    queries: list[str] = []
    real = search.search

    async def spy(query: str, lang, k: int = 3):
        queries.append(query)
        return await real(query, lang, k)

    search.search = spy
    return queries


def turn_text(model: FakeModel, request_no: int = 0) -> str:
    """Сообщение хода (последнее сообщение посетителя) в запросе к модели."""
    return [m for m in model.requests[request_no]["messages"] if m["role"] == "user"][-1]["content"]


def system_text(model: FakeModel, request_no: int = 0) -> str:
    return model.requests[request_no]["messages"][0]["content"][0]["text"]


def test_prefetch_answers_document_detail_with_citation(make_client, docs_dir):
    """DoD задачи 11 в режиме по умолчанию: сервер сам нашёл фрагмент документа и положил его в сообщение
    хода, модель ответила по справке; citations — то, что было в справке."""
    model = FakeModel(
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
        say("[happy] Участие бесплатное."),
    )
    client = rag_client(make_client, model)
    assert client.app.state.chat.retrieval == "prefetch"

    evs = events(client, QUESTION, lang="ru")

    assert [e for e, _ in evs] == ["phrase", "done"]
    assert "девятнадцать недель" in evs[0][1]["text"]
    done = evs[-1][1]
    assert ORBITA_2 in done["citations"]
    assert "document:orbita#2" in done["sources"] and done["found"] is True
    assert len({c["id"] for c in done["citations"]}) == len(done["citations"]) <= 3
    req = model.requests[0]
    assert {t["function"]["name"] for t in req["tools"]} == {
        "where_is",
        "guide_to",
        "look",
        "no_answer",
    }
    system, turn = system_text(model), turn_text(model)
    assert "search_kb" not in system and "«Справка»" in system
    assert REFERENCE_HEADER not in system and "девятнадцать недель" not in system
    assert REFERENCE_HEADER in turn and "девятнадцать недель" in turn
    assert "база" not in turn.split(REFERENCE_HEADER)[0].lower()
    assert turn.index(REFERENCE_HEADER) < turn.index("<visitor_message>")
    # в справке ровно отданное модели: по фрагменту на каждую citation, текст ≤ ~2500 символов
    assert re.findall(r"^\[(\d)\] ", turn, re.M) == [
        str(i + 1) for i in range(len(done["citations"]))
    ]
    body = turn.split(REFERENCE_HEADER)[1].split("Язык ответа:")[0]
    assert len(body) <= SEARCH_BUDGET_CHARS + 300
    row = logged(client)[0]
    assert row.found and "document:orbita#2" in row.sources
    assert any(name == "kb_prefetch" for _, _, name in messages(client))
    by = stages(latency_rows(client, "chat"))
    assert by["kb_search_ms"].ms is not None
    assert by["llm_total_ms"].extra["tools"] == ["kb_prefetch"]

    events(client, "Сколько стоит участие в программе Орбита?", lang="ru")
    assert system_text(model, 1) == system  # системный промпт одинаков между ходами — кеш
    assert "orbita-demo.kz" in turn_text(model, 1)  # своя справка у каждого хода
    first_user = next(m for m in model.requests[1]["messages"] if m["role"] == "user")
    assert REFERENCE_HEADER not in first_user["content"]  # в истории — только реплика посетителя


def test_prefetch_json_mode_has_citations(make_client, docs_dir):
    model = FakeModel(say("[happy] Программа «Орбита» длится девятнадцать недель."))
    client = rag_client(make_client, model)
    body = client.post("/v1/chat", json=ask_body(QUESTION, lang="ru"), headers=AUTH).json()
    assert ORBITA_2 in body["citations"] and "document:orbita#2" in body["sources"]
    assert "девятнадцать недель" in body["answer"]


def test_prefetch_skips_noise_small_talk_greeting_one_word_places_and_consent(
    make_client, docs_dir
):
    model = FakeModel(
        say("[happy] Здравствуйте! Что ищете?"),  # visitor_arrived
        say("[thinking] Что про Орбиту подсказать?"),  # «Орбита?»
        call("where_is", {"place_id": "place:cafe"}),
        say("[happy] Показал на экране, куда идти. Проводить вас?"),
        call("guide_to", {"place_id": "place:cafe"}),
        say("[excited] Идите за мной, провожаю в кафе!"),
        say("[happy] Программа «Орбита» длится девятнадцать недель."),
    )
    client = rag_client(make_client, model)
    queries = spy_search(client.app)

    assert events(client, "Ну…", lang="ru")[-1][1]["ignored"] is True  # шум
    events(client, "Спасибо большое", lang="ru")  # локальный small talk
    events(client, "", lang="ru", event="visitor_arrived")
    events(client, "Орбита?", lang="ru")  # одно слово
    events(client, "Где кафе?", lang="ru")  # место — в промпте
    evs = events(client, "Да, проводите", lang="ru")  # «да» на «Проводить вас?»
    assert ("action", {"type": "guide_to", "place_id": "cafe"}) in evs
    assert queries == []
    assert len(model.requests) == 6
    assert all(REFERENCE_HEADER not in turn_text(model, i) for i in range(6))

    # live: быстрый путь мест и «да» на «Проводить вас?» — без модели и без поиска
    chat = client.app.state.chat

    async def live_turns() -> list[list[str]]:
        out = []
        for question in ["Где кафе?", "Да, проводите"]:
            req = ChatRequest(question=question, lang="ru", session_id="live-session")
            async with aclosing(chat.chat_stream(req, "req", live=LiveTurn())) as stream:
                out.append([kind async for kind, _ in stream])
        return out

    assert asyncio.run(live_turns()) == [
        ["action", "emotion", "phrase", "phrase", "done"],
        ["action", "emotion", "phrase", "done"],
    ]
    assert queries == [] and len(model.requests) == 6

    # обычный вопрос — ищем; короткий (5 слов) — вместе с прошлой репликой о чём-то: «да, проводите» и
    # «спасибо» темы не несут, берётся «Где кафе?»
    events(client, QUESTION, lang="ru")
    assert queries == [f"Где кафе? {QUESTION}"]
    assert REFERENCE_HEADER in turn_text(model, 6)


def test_prefetch_query_adds_previous_utterance_for_short_or_reference():
    prev = "Расскажите про программу Орбита"
    assert prefetch_query("А сколько это стоит?", prev) == f"{prev} А сколько это стоит?"
    assert prefetch_query("Есть ли скидка студентам", prev) == f"{prev} Есть ли скидка студентам"
    long_ref = "Скажите пожалуйста сколько всего недель длится это обучение"  # 8 слов, «это»
    assert prefetch_query(long_ref, prev) == f"{prev} {long_ref}"
    en_ref = "And how much does the whole program cost for a team"
    assert prefetch_query(en_ref, prev) == f"{prev} {en_ref}"
    standalone = "Сколько недель длится программа Орбита для команд?"  # 7 слов, без отсылки
    assert prefetch_query(standalone, prev) == standalone
    assert prefetch_query("А сколько это стоит?", None) == "А сколько это стоит?"
    assert prefetch_query("  А сколько это стоит?  ", "  ") == "А сколько это стоит?"


def test_prefetch_short_follow_up_searches_with_previous_utterance(make_client, docs_dir):
    answer = say("[happy] Участие бесплатное.")
    model = FakeModel(answer, answer, answer)
    client = rag_client(make_client, model)
    queries = spy_search(client.app)
    first, follow = "Расскажите про программу Орбита", "А сколько это стоит?"

    events(client, first, lang="ru")
    events(client, follow, lang="ru")
    assert queries == [first, f"{first} {follow}"]
    assert "orbita-demo.kz" in turn_text(model, 1)  # «стоит» + «Орбита» → раздел про оплату

    # перезапуск сервера: кеша памяти нет — прошлая реплика из БД (ждём загрузку истории)
    messages(client)
    restarted = make_client(chat_client=model.client(), embedding_provider="fake")
    again = spy_search(restarted.app)
    events(restarted, "А там кормят?", lang="ru")
    assert again == [f"{follow} А там кормят?"]


def test_prefetch_embedding_timeout_falls_back_to_full_text(make_client, docs_dir):
    model = FakeModel(say("[happy] Программа «Орбита» длится девятнадцать недель."))
    client = rag_client(
        make_client, model, embedder=SlowEmbeddings(), embedding_query_timeout_s=0.2
    )
    started = time.perf_counter()
    evs = events(client, QUESTION, lang="ru")
    assert time.perf_counter() - started < 1.5
    assert ORBITA_2 in evs[-1][1]["citations"]
    assert "девятнадцать недель" in turn_text(model)  # нашлось по словам


def test_prefetch_failure_or_ceiling_answers_without_reference(make_client, docs_dir):
    answer = say("[happy] В «Орбиту» берут команды с прототипом.")
    model = FakeModel(answer, answer)
    client = rag_client(make_client, model, kb_prefetch_timeout_s=0.3)
    search = client.app.state.kb_search

    async def broken(query: str, lang, k: int = 3):
        raise RuntimeError("db down")

    search.search = broken
    evs = events(client, QUESTION, lang="ru")
    assert [e for e, _ in evs] == ["phrase", "done"] and evs[-1][1]["citations"] == []
    assert REFERENCE_HEADER not in turn_text(model, 0)

    async def slow(query: str, lang, k: int = 3):
        await asyncio.sleep(3)
        return []

    search.search = slow
    started = time.perf_counter()
    evs = events(client, QUESTION, lang="ru")
    assert time.perf_counter() - started < 2.0  # потолок справки, дальше — к модели без неё
    assert [e for e, _ in evs] == ["phrase", "done"] and evs[-1][1]["citations"] == []
    assert REFERENCE_HEADER not in turn_text(model, 1)
    assert [r.category for r in logged(client)] == ["agent", "agent"]
    searched = [r.ms for r in latency_rows(client, "chat") if r.stage == "kb_search_ms"]
    assert len(searched) == 2 and searched[1] >= 300


def test_prefetch_reference_feeds_output_filter(make_client, docs_dir):
    """Ссылка из справки — не выдумка модели; без справки та же ссылка блокируется."""
    answer = say("[happy] Регистрация на сайте orbita-demo.kz.")
    model = FakeModel(answer, answer)
    client = rag_client(make_client, model)
    evs = events(client, "Где зарегистрироваться на Орбиту?", lang="ru")
    assert evs[0] == ("phrase", {"text": "Регистрация на сайте orbita-demo.kz."})
    assert "document:orbita#3" in [c["id"] for c in evs[-1][1]["citations"]]

    async def nothing(query: str, lang, k: int = 3):
        return []

    client.app.state.kb_search.search = nothing
    events(client, "Где зарегистрироваться на Орбиту?", lang="ru")
    assert [r.category for r in logged(client)] == ["agent", "blocked"]


def test_prefetch_live_turn_end_has_sources_and_citations(live_server, docs_dir):  # noqa: F811
    model = FakeModel(say("[happy] Программа «Орбита» длится девятнадцать недель."))
    url, app, _ = live_server(
        model, script=[QUESTION], live_fast_places=False, embedding_provider="fake"
    )
    assert app.state.indexer.index().ok
    queries = spy_search(app)

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.6)
            end = await c.wait("turn_end", timeout=12)
            assert "document:orbita#2" in end["sources"]
            assert ORBITA_2 in end["citations"]
            assert [p["text"] for p in c.events("phrase")] == [
                "Программа «Орбита» длится девятнадцать недель."
            ]

    run(scenario())
    assert queries == [QUESTION]
    assert REFERENCE_HEADER in turn_text(model)


def test_server_indexes_in_background_on_start(settings, docs_dir):
    s = settings.model_copy(update={"embedding_provider": "fake"})
    app = create_app(s, FakeProvider(), FakeProvider())
    n = 0
    try:
        with TestClient(app):  # lifespan: сид из YAML уже был, индексация — фоном
            deadline = time.time() + 10
            while time.time() < deadline and n < 4:
                with app.state.engine.connect() as conn:
                    n = conn.execute(
                        text("SELECT count(*) FROM kb_chunks WHERE source_type = 'document'")
                    ).scalar_one()
                time.sleep(0.1)
        assert n == 4
    finally:
        app.state.log_writer.wait_idle(5)
        app.state.engine.dispose()


# ---------------------------------------------------------------- CLI и eval


def test_cli_kb_index_and_status(monkeypatch, capsys, knowledge_dir, docs_dir):
    from app.cli import main

    with temp_database() as url:
        monkeypatch.setenv("DATABASE_URL", url)
        monkeypatch.setenv("KNOWLEDGE_DIR", str(knowledge_dir))
        monkeypatch.setenv("KNOWLEDGE_SHEET_ID", "")
        assert main(["db", "upgrade"]) == 0
        assert main(["kb", "seed-yaml"]) == 0
        monkeypatch.setenv("EMBEDDING_PROVIDER", "off")
        assert main(["kb", "index"]) == 2  # без эмбеддингов поиска нет
        monkeypatch.setenv("EMBEDDING_PROVIDER", "fake")
        capsys.readouterr()
        assert main(["kb", "index"]) == 0
        out = capsys.readouterr().out
        assert re.search(r"embedded=[1-9]\d*", out) and "document=4" in out
        assert main(["kb", "index"]) == 0
        assert "embedded=0" in capsys.readouterr().out
        assert main(["kb", "status"]) == 0
        out = capsys.readouterr().out
        assert "documents=2" in out and "document=4" in out and "fake-stems-768" in out


@pytest.mark.parametrize("mode", ["prefetch", "tool"])
def test_eval_matches_document_prefix_and_runs_chat_with_search(
    tmp_path, monkeypatch, docs_dir, mode
):
    from eval.run_eval import run as run_eval
    from eval.run_eval import source_matches
    from tests.conftest import PG_URL

    assert source_matches("document:orbita", ["fact:wifi", "document:orbita#2"])
    assert source_matches("document:orbita#2", ["document:orbita#2"])
    assert not source_matches("document:orbita#1", ["document:orbita#2"])
    assert not source_matches("document:orb", ["document:orbita#2"])

    questions = tmp_path / "rag.yaml"
    questions.write_text(
        "questions:\n"
        f"  - {{id: q1, lang: ru, question: '{QUESTION}', expect_found: true,"
        " expect_source: 'document:orbita', expect_contains: ['девятнадцать']}\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("KNOWLEDGE_DIR", str(docs_dir.parent))
    monkeypatch.setenv("RETRIEVAL_MODE", mode)
    answer = say("[happy] Программа «Орбита» длится девятнадцать недель.")
    model = (
        FakeModel(call("search_kb", {"query": "сколько недель длится программа Орбита"}), answer)
        if mode == "tool"
        else FakeModel(answer)  # справку сервер нашёл сам
    )
    report = run_eval(
        questions,
        pass_lang=True,
        endpoint="chat",
        database_url=PG_URL,
        chat_client=model.client(),
        embedder=FakeEmbeddings(),
    )
    assert report["retrieval"] == f"{mode}:fake-stems-768"
    assert report["summary"]["all"]["accuracy"] == 1.0, report["results"]
