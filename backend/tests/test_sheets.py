from __future__ import annotations

import asyncio
from pathlib import Path

import httpx
import pytest
from sqlalchemy import text

from app.config import BACKEND_DIR
from app.knowledge.pg import DbKnowledgeSource, KnowledgeRepo
from app.knowledge.sheets import (
    SheetError,
    SheetFetcher,
    kb_from_csv,
    read_csv,
    sheet_csv_url,
)
from app.knowledge.source import KnowledgeStore, YamlKnowledgeSource, build_kb
from app.knowledge.sync import KnowledgeSyncer

TEMPLATE = BACKEND_DIR / "knowledge" / "sheet_template"
N_FACTS = len(YamlKnowledgeSource(BACKEND_DIR / "knowledge").load().facts)


def _templates() -> tuple[str, str, str]:
    return tuple(
        (TEMPLATE / n).read_text(encoding="utf-8")
        for n in ("facts.csv", "places.csv", "buttons.csv")
    )  # type: ignore[return-value]


def test_template_equals_yaml_examples():
    sheet_kb = kb_from_csv(*_templates())
    yaml_kb = YamlKnowledgeSource(BACKEND_DIR / "knowledge").load()
    assert sheet_kb.model_dump() == yaml_kb.model_dump()


def test_url():
    assert sheet_csv_url("abc", "Факты") == (
        "https://docs.google.com/spreadsheets/d/abc/gviz/tq?tqx=out:csv&sheet=%D0%A4%D0%B0%D0%BA%D1%82%D1%8B"
    )


def test_read_csv_skips_inactive_and_empty_and_normalizes_headers():
    rows = read_csv('"ID","Active","Answer_RU"\n"a","да","x"\n"b","нет","y"\n"","",""\n')
    assert rows == [{"id": "a", "active": "да", "answer_ru": "x"}]


def test_html_instead_of_csv_is_error():
    with pytest.raises(SheetError):
        read_csv("<!DOCTYPE html><html>Sign in</html>")


def test_empty_facts_tab_is_rejected():
    facts, places, buttons = _templates()
    with pytest.raises(SheetError):
        kb_from_csv(facts.splitlines()[0] + "\n", places, buttons)


def test_rows_without_answer_skipped_and_semicolon_lists():
    facts = "id,keywords,answer_ru\nok,кофе; кафе,Да.\nbad,x,\n"
    kb = kb_from_csv(facts, "id,map_name\n", "id,text_ru\n")
    assert [f.id for f in kb.facts] == ["ok"]
    assert kb.facts[0].keywords == ["кофе", "кафе"]


def test_duplicate_ids_rejected():
    with pytest.raises(SheetError):
        kb_from_csv("id,answer_ru\na,1\na,2\n", "id,map_name\n", "id,text_ru\n")


# ---------- таблица → Postgres (MockTransport) ----------


def _fetcher(timeout_s: float = 5.0) -> SheetFetcher:
    return SheetFetcher(sheet_id="SHEET", tabs=("Факты", "Места", "Кнопки"), timeout_s=timeout_s)


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _handler(facts: str, status: int = 200, places: str | None = None):
    _, default_places, buttons = _templates()

    def handler(req: httpx.Request) -> httpx.Response:
        tab = req.url.params["sheet"]
        body = {"Факты": facts, "Места": places or default_places, "Кнопки": buttons}[tab]
        return httpx.Response(status, text=body)

    return handler


GYM = "id,answer_ru,keywords\ngym,Спортзал на 3 этаже.,спортзал\n"


def _syncer(db, tmp_path: Path) -> KnowledgeSyncer:
    repo = KnowledgeRepo(db)
    source = DbKnowledgeSource(
        repo, YamlKnowledgeSource(BACKEND_DIR / "knowledge"), tmp_path / "snap.json"
    )
    return KnowledgeSyncer(repo, source, _fetcher())


def _statuses(db) -> list[str]:
    with db.connect() as conn:
        return list(conn.execute(text("SELECT status FROM kb_sync ORDER BY id")).scalars())


def test_sync_writes_db_and_keeps_last_good_version_on_errors(db, tmp_path):
    syncer = _syncer(db, tmp_path)
    store = KnowledgeStore(syncer.source, reload_interval_s=0)
    assert len(store.get().facts) == N_FACTS  # пока не синхронизировано — YAML (БД пуста)

    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM)))) is True
    assert [f.id for f in store.get().facts] == ["gym"]
    assert syncer.source.from_db is True
    assert (tmp_path / "snap.json").exists()

    # http 500, HTML вместо CSV (таблица закрыта) — база в БД и в памяти не меняется
    assert asyncio.run(syncer.sync_sheet(_client(_handler("", status=500)))) is False
    assert asyncio.run(syncer.sync_sheet(_client(_handler("<html>login</html>")))) is False
    assert syncer.last_error
    assert [f.id for f in store.get().facts] == ["gym"]
    assert [f.id for f in KnowledgeRepo(db).load_active().facts] == ["gym"]

    # повтор той же таблицы — skipped, без записи
    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM)))) is True
    assert _statuses(db) == ["ok", "error", "error", "skipped"]


def test_sync_deactivates_rows_missing_from_sheet(db, tmp_path):
    syncer = _syncer(db, tmp_path)
    facts, _, _ = _templates()
    assert asyncio.run(syncer.sync_sheet(_client(_handler(facts)))) is True
    assert len(syncer.source.load().facts) == N_FACTS

    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM)))) is True
    with db.connect() as conn:
        active = dict(conn.execute(text("SELECT id, is_active FROM facts")).all())
    assert len(active) == N_FACTS + 1 and active["gym"] is True
    assert sum(active.values()) == 1  # остальные выключены, но не удалены
    last = KnowledgeRepo(db).sync_log(1)[0]
    assert last.status == "ok"
    assert last.counts["facts"] == {"inserted": 1, "updated": 0, "deactivated": N_FACTS}
    assert last.version == syncer.source.load().version

    # строка вернулась в таблицу — снова активна
    assert asyncio.run(syncer.sync_sheet(_client(_handler(facts)))) is True
    assert len(syncer.source.load().facts) == N_FACTS


def test_wrong_tab_name_gviz_returns_first_tab_is_rejected(db, tmp_path):
    """gviz при неверном имени вкладки отдаёт первую («Факты») — «Места» без map_name → ошибка."""
    facts, _, _ = _templates()
    syncer = _syncer(db, tmp_path)
    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM)))) is True
    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM, places=facts)))) is False
    assert "map_name" in (syncer.last_error or "")
    assert [f.id for f in syncer.source.load().facts] == ["gym"]  # остались на прошлой версии
    last = KnowledgeRepo(db).sync_log(1)[0]
    assert last.status == "error" and "map_name" in last.error


def test_duplicate_map_name_rejected_without_partial_write(db, tmp_path):
    syncer = _syncer(db, tmp_path)
    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM)))) is True
    places = "id,map_name\na,Кафе\nb,Кафе\n"
    assert asyncio.run(syncer.sync_sheet(_client(_handler(GYM, places=places)))) is False
    assert "map_name" in (syncer.last_error or "")
    assert {p.id for p in KnowledgeRepo(db).load_active().places} == {
        p.id for p in YamlKnowledgeSource(BACKEND_DIR / "knowledge").load().places
    }


@pytest.mark.parametrize(
    ("kind", "csv_text"),
    [
        ("facts", "id,keywords,question_ru\na,b,c\n"),
        ("places", "id,name_ru\na,b\n"),
        ("buttons", "id,text_en\na,b\n"),
    ],
)
def test_required_headers_per_tab(kind, csv_text):
    facts, places, buttons = _templates()
    tabs = {"facts": facts, "places": places, "buttons": buttons}
    tabs[kind] = csv_text
    with pytest.raises(SheetError):
        kb_from_csv(tabs["facts"], tabs["places"], tabs["buttons"])


def test_tabs_load_in_parallel_with_total_timeout():
    fetcher = _fetcher(timeout_s=0.3)
    calls: list[str] = []

    async def slow(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.params["sheet"])
        await asyncio.sleep(0.2 if req.url.params["sheet"] != "Кнопки" else 5)
        return httpx.Response(200, text="id,map_name\n")

    async def run() -> tuple[str, float]:
        async with httpx.AsyncClient(transport=httpx.MockTransport(slow)) as client:
            t0 = asyncio.get_running_loop().time()
            try:
                await fetcher.fetch(client)
            except SheetError as exc:
                return str(exc), asyncio.get_running_loop().time() - t0
            return "", 0.0

    error, elapsed = asyncio.run(run())
    assert "timeout" in error
    assert elapsed < 1.0
    assert sorted(calls) == sorted(["Факты", "Места", "Кнопки"])  # все запрошены сразу


def test_startup_does_not_wait_for_google_and_syncs_in_background(settings, monkeypatch):
    import time

    from fastapi.testclient import TestClient

    from app.llm.fake import FakeProvider
    from app.main import create_app

    _, places, buttons = _templates()

    async def handler(req: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.5)  # Google медленный
        tab = req.url.params["sheet"]
        return httpx.Response(200, text={"Факты": GYM, "Места": places, "Кнопки": buttons}[tab])

    monkeypatch.setattr(
        SheetFetcher,
        "new_client",
        lambda self: httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    s = settings.model_copy(update={"knowledge_sheet_id": "SHEET"})
    t0 = time.perf_counter()
    app = create_app(s, FakeProvider(), FakeProvider())
    with TestClient(app) as c:
        assert time.perf_counter() - t0 < 0.4  # старт без ожидания таблицы
        # таблица задана → автосида из YAML нет; пустая БД → запасной YAML до первого синка
        assert KnowledgeRepo(c.app.state.engine).is_empty()
        assert len(c.app.state.store.get().facts) == N_FACTS
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and len(c.app.state.store.get().facts) != 1:
            time.sleep(0.05)
        assert [f.id for f in c.app.state.store.get().facts] == ["gym"]
    assert s.knowledge_snapshot_path.exists()


def test_startup_uses_snapshot_when_db_is_down(settings, monkeypatch, tmp_path):
    from app.main import create_app

    # снимок от прошлого запуска
    snap = tmp_path / "snap.json"
    snap.write_text(
        build_kb(kb_from_csv(GYM, "id,map_name\n", "id,text_ru\n").facts, [], []).model_dump_json(),
        encoding="utf-8",
    )

    def no_network(*a, **kw):
        raise AssertionError("startup must not touch the network")

    monkeypatch.setattr(httpx.Client, "get", no_network)
    monkeypatch.setattr(httpx.AsyncClient, "get", no_network)
    s = settings.model_copy(
        update={
            "database_url": "postgresql+psycopg://u:p@127.0.0.1:1/none",
            "db_timeout_s": 1,
            "knowledge_sheet_id": "SHEET",
            "knowledge_snapshot_path": snap,
        }
    )
    from app.llm.fake import FakeProvider

    app = create_app(s, FakeProvider(), FakeProvider())
    assert [f.id for f in app.state.store.get().facts] == ["gym"]
    assert app.state.knowledge.source.from_db is False


def test_startup_falls_back_to_yaml_when_db_is_down_and_no_snapshot(settings):
    from app.llm.fake import FakeProvider
    from app.main import create_app

    s = settings.model_copy(
        update={"database_url": "postgresql+psycopg://u:p@127.0.0.1:1/none", "db_timeout_s": 1}
    )
    app = create_app(s, FakeProvider(), FakeProvider())
    assert len(app.state.store.get().facts) == N_FACTS
