from __future__ import annotations

import asyncio
from pathlib import Path

import httpx
import pytest

from app.config import BACKEND_DIR
from app.knowledge.sheets import (
    SheetError,
    SheetKnowledgeSource,
    kb_from_csv,
    read_csv,
    sheet_csv_url,
)
from app.knowledge.source import KnowledgeStore, YamlKnowledgeSource

TEMPLATE = BACKEND_DIR / "knowledge" / "sheet_template"


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


# ---------- источник с синхронизацией (MockTransport) ----------


def _source(tmp_path: Path) -> SheetKnowledgeSource:
    return SheetKnowledgeSource(
        sheet_id="SHEET",
        tabs=("Факты", "Места", "Кнопки"),
        snapshot_path=tmp_path / "snap.json",
        fallback=YamlKnowledgeSource(BACKEND_DIR / "knowledge"),
    )


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _handler(facts: str, status: int = 200):
    _, places, buttons = _templates()

    def handler(req: httpx.Request) -> httpx.Response:
        tab = req.url.params["sheet"]
        body = {"Факты": facts, "Места": places, "Кнопки": buttons}[tab]
        return httpx.Response(status, text=body)

    return handler


def test_sync_updates_store_and_snapshot(tmp_path):
    src = _source(tmp_path)
    store = KnowledgeStore(src, reload_interval_s=0)
    assert len(store.get().facts) == 13  # пока не синхронизировано — YAML

    facts = "id,answer_ru,keywords\ngym,Спортзал на 3 этаже.,спортзал\n"
    assert asyncio.run(src.sync(_client(_handler(facts)))) is True
    kb = store.get()
    assert [f.id for f in kb.facts] == ["gym"]
    assert (tmp_path / "snap.json").exists()

    # ошибка синка — остаёмся на последней удачной версии
    assert asyncio.run(src.sync(_client(_handler("", status=500)))) is False
    assert [f.id for f in store.get().facts] == ["gym"]
    assert src.last_error

    # HTML (таблица закрыта) — тоже остаёмся
    assert asyncio.run(src.sync(_client(_handler("<html>login</html>")))) is False
    assert [f.id for f in store.get().facts] == ["gym"]

    # новый процесс без сети поднимается со снимка
    src2 = _source(tmp_path)
    assert src2.load_snapshot() is True
    assert [f.id for f in src2.load().facts] == ["gym"]


def test_build_source_without_network_falls_back_to_yaml(tmp_path, monkeypatch):
    from app.config import Settings
    from app.main import build_knowledge_source

    def boom(self):
        raise httpx.ConnectError("offline")

    monkeypatch.setattr(httpx.Client, "get", lambda self, url: boom(self))
    s = Settings(
        _env_file=None,
        robot_token="t",
        knowledge_sheet_id="SHEET",
        knowledge_snapshot_path=tmp_path / "none.json",
    )
    src = build_knowledge_source(s)
    assert isinstance(src, SheetKnowledgeSource)
    assert len(src.load().facts) == 13
