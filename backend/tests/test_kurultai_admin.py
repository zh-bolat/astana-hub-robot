"""Админка материалов Курултая (/app/admin): разбор файлов, справка моделью, публикация в промпт робота."""

from __future__ import annotations

import io
import json
import time
from datetime import UTC, date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.kurultai import admin, download
from app.kurultai.materials import extract as ex
from app.kurultai.materials import structure as st
from app.llm.fake import FakeProvider
from app.main import create_app
from tests.test_chat import FakeModel, say
from tests.test_kurultai import KURULTAI_TOKEN, ready_sync

PASSWORD = "apparat-2026"
H = {"X-Kurultai-Admin": "1"}
BRIEF = (
    "# Заседание Курултая 11.11.2026\n- Тип: заседание · Дата: 11.11.2026 · Время: 10:00 · Место: зал заседаний\n"
    "## Повестка и порядок\n1. 10:00 — открытие\n2. 10:30 — законопроект № 123-VIII"
)
SOURCE = "Заседание Курултая 11.11.2026 в 10:00, зал заседаний. 10:00 открытие. 10:30 законопроект № 123-VIII."


def _answer(brief: str = BRIEF, **over) -> str:
    data = {"title": "Заседание 11 ноября", "kind": "meeting", "event_date": "2099-11-11",
            "valid_until": "2099-11-11", "brief": brief} | over  # fmt: skip
    return json.dumps(data, ensure_ascii=False)


class FakeComplete:
    """Модель справок/OCR: отвечает по очереди; запоминает запросы."""

    def __init__(self, *answers: str | Exception) -> None:
        self.answers = list(answers)
        self.calls: list[tuple[list[dict], dict | None]] = []

    async def __call__(self, messages, response_format):
        self.calls.append((messages, response_format))
        ans = self.answers.pop(0) if self.answers else _answer()
        if isinstance(ans, Exception):
            raise ans
        return ans


@pytest.fixture(autouse=True)
def _fresh_failures(monkeypatch):
    monkeypatch.setattr(admin, "_failures", download._Failures())


@pytest.fixture
def make_admin(settings, tmp_path):
    clients = []

    def _make(complete=None, model: FakeModel | None = None, password: str = PASSWORD, **over):
        s = settings.model_copy(
            update={"kurultai_robot_token": SecretStr(KURULTAI_TOKEN), "kurultai_web_search_always": False,
                    "kurultai_admin_password": SecretStr(password), "kurultai_admin_cookie_secure": False, **over}
        )  # fmt: skip
        app = create_app(
            s,
            FakeProvider(),
            FakeProvider(),
            chat_client=FakeModel().client(),
            kurultai_chat_client=(model or FakeModel()).client(),
            quryltai=ready_sync(tmp_path),
            kurultai_materials_complete=complete if complete is not None else FakeComplete(),
        )
        c = TestClient(app)
        clients.append(c)
        return c

    yield _make
    for c in clients:
        c.close()
        c.app.state.log_writer.wait_idle(5)
        c.app.state.engine.dispose()


def _login(c: TestClient) -> TestClient:
    r = c.post("/app/admin/login", data={"password": PASSWORD}, follow_redirects=False)
    assert r.status_code == 303
    return c


def _wait(c: TestClient, mid: int, timeout: float = 10) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        item = c.get(f"/app/admin/api/materials/{mid}").json()
        if item["status"] != "processing":
            return item
        time.sleep(0.05)
    raise AssertionError("обработка не закончилась")


def _upload_text(c: TestClient, text: str = SOURCE, title: str = "") -> int:
    r = c.post("/app/admin/api/materials", data={"text": text, "title": title}, headers=H)
    assert r.status_code == 201, r.text
    return r.json()["created"][0]


# ---------- разбор файлов ----------
def _pdf_with_text(text: str) -> bytes:
    stream = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode()
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = io.BytesIO(), []
    out.write(b"%PDF-1.4\n")
    for i, body in enumerate(objs, 1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % i + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1))
    for off in offsets:
        out.write(b"%010d 00000 n \n" % off)
    out.write(
        b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    )
    return out.getvalue()


def _blank_pdf(pages: int = 2) -> bytes:
    from pypdf import PdfWriter

    w = PdfWriter()
    for _ in range(pages):
        w.add_blank_page(612, 792)
    buf = io.BytesIO()
    w.write(buf)
    return buf.getvalue()


def test_extract_pdf_text_and_scan():
    got = ex.extract(
        _pdf_with_text("Session 11.11.2026 at 10:00, hall A. Agenda: budget law, digital code."),
        "a.pdf",
    )
    assert got.kind == "pdf" and got.pages == 1 and not got.needs_ocr
    assert "11.11.2026" in got.text and "[стр. 1]" in got.text
    scan = ex.extract(_blank_pdf(), "scan.pdf")
    assert scan.needs_ocr and scan.pages == 2


def test_extract_docx_keeps_headings_paragraphs_and_tables():
    import docx

    d = docx.Document()
    d.add_heading("Повестка заседания", level=1)
    d.add_paragraph("Начало в 10:00.")
    t = d.add_table(rows=2, cols=2)
    t.cell(0, 0).text, t.cell(0, 1).text = "Время", "Вопрос"
    t.cell(1, 0).text, t.cell(1, 1).text = "10:30", "Бюджет"
    buf = io.BytesIO()
    d.save(buf)
    got = ex.extract(buf.getvalue(), "agenda.docx")
    assert got.kind == "docx"
    assert "## Повестка заседания" in got.text
    assert "Начало в 10:00." in got.text
    assert "| 10:30 | Бюджет |" in got.text
    assert got.text.index("Повестка") < got.text.index("10:30")


@pytest.mark.parametrize(
    ("data", "name", "expected"),
    [
        ("Заседание в среду".encode("cp1251"), "a.txt", "Заседание в среду"),
        ("Құрылтай отырысы".encode("utf-16"), "a.txt", "Құрылтай отырысы"),
        ("﻿Повестка".encode(), "a.md", "Повестка"),
        (
            b"<html><script>alert(1)</script><h1>\xd0\x9f\xd0\xbb\xd0\xb0\xd0\xbd</h1><p>A</p></html>",
            "a.html",
            "План",
        ),
        (b'{"a":"\xd0\xb1"}', "a.json", '"a": "б"'),
        (rb"{\rtf1\ansi{\fonttbl{\f0 Arial;}}\f0 \'cf\'eb\'e0\'ed\par 10:00}", "a.rtf", "План"),
    ],
)
def test_extract_text_formats(data, name, expected):
    got = ex.extract(data, name)
    assert expected in got.text
    assert "alert" not in got.text and "Arial" not in got.text


def test_extract_rejects_binary_and_broken():
    with pytest.raises(ex.ExtractError):
        ex.extract(b"\x00\x01\x02binary" * 50, "a.exe")
    with pytest.raises(ex.ExtractError):
        ex.extract(b"%PDF-1.4 broken", "a.pdf")


# ---------- справка ----------
def test_structure_parses_json_and_flags_missing_numbers():
    import asyncio

    complete = FakeComplete(_answer(brief=BRIEF + "\n3. 15:45 — закрытие"))
    got = asyncio.run(st.structure(SOURCE, "a.txt", complete, date(2026, 11, 6)))
    assert (got.kind, got.event_date, got.valid_until) == (
        "meeting",
        date(2099, 11, 11),
        date(2099, 11, 11),
    )
    assert got.warnings and "15:45" in got.warnings[0] and "10:30" not in got.warnings[0]
    messages, fmt = complete.calls[0]
    assert fmt == st.RESPONSE_FORMAT
    assert "Только то, что есть в документе" in messages[0]["content"]
    assert SOURCE in messages[1]["content"]


def test_structure_errors():
    import asyncio

    with pytest.raises(st.StructureError):
        asyncio.run(st.structure("текст", "a", FakeComplete("не json"), date(2026, 1, 1)))
    with pytest.raises(st.StructureError):
        asyncio.run(st.structure("текст", "a", FakeComplete(_answer(brief="")), date(2026, 1, 1)))
    with pytest.raises(st.StructureError):
        asyncio.run(st.structure("  ", "a", FakeComplete(), date(2026, 1, 1)))


# ---------- вход ----------
def test_admin_off_without_password(make_admin):
    c = make_admin(password="")
    assert c.get("/app/admin").status_code == 404
    assert c.get("/app/admin/api/materials").status_code == 404


def test_login_lockout_and_api_guards(make_admin):
    c = make_admin()
    page = c.get("/app/admin")
    assert page.status_code == 200 and 'type="password"' in page.text and "Материалы" in page.text
    assert c.get("/app/admin/api/materials").status_code == 401
    for _ in range(download.APP_MAX_FAILURES):
        assert c.post("/app/admin/login", data={"password": "нет"}).status_code == 401
    assert c.post("/app/admin/login", data={"password": PASSWORD}).status_code == 429


def test_login_cookie_and_csrf_header(make_admin):
    c = _login(make_admin())
    assert "X-Kurultai-Admin" in c.get("/app/admin").text  # страница админки, не форма входа
    assert c.get("/app/admin/api/materials").status_code == 200
    r = c.post("/app/admin/api/materials", data={"text": SOURCE})  # без заголовка — отказ
    assert r.status_code == 403
    c.cookies.set("k_admin", "9999999999.deadbeef", path="/app/admin")
    assert c.get("/app/admin/api/materials").status_code == 401


# ---------- полный цикл ----------
def test_upload_review_publish_and_robot_prompt(make_admin):
    model = FakeModel(say("Заседание начнётся в 10:00."))
    complete = FakeComplete(_answer())
    c = _login(make_admin(complete=complete, model=model))
    mid = _upload_text(c, title="")
    item = _wait(c, mid)
    assert item["status"] == "draft" and item["brief"] == BRIEF
    assert item["title"] == "Заседание 11 ноября" and item["kind"] == "meeting"
    assert item["event_date"] == "2099-11-11" and item["valid_until"] == "2099-11-11"
    assert item["warnings"] == []
    assert c.get("/app/admin/api/preview").json()["block"] == ""  # черновик робот не знает

    # сотрудник правит справку: новое число без опоры в исходнике — предупреждение
    edited = BRIEF + "\n3. 16:00 — закрытие"
    r = c.patch(f"/app/admin/api/materials/{mid}", json={"brief": edited}, headers=H)
    assert r.status_code == 200 and "16:00" in r.json()["warnings"][0]

    assert (
        c.post(f"/app/admin/api/materials/{mid}/publish", headers=H).json()["status"] == "published"
    )
    pv = c.get("/app/admin/api/preview").json()
    assert "материалы аппарата Курултая" in pv["block"] and "16:00 — закрытие" in pv["block"]
    assert pv["included"] == [mid]

    # пробный вопрос — тот же агент: материалы в системном промпте
    r = c.post(
        "/app/admin/api/ask", json={"question": "Во сколько заседание?", "lang": "ru"}, headers=H
    )
    assert r.status_code == 200 and "10:00" in r.json()["answer"]
    system = json.dumps(model.requests[0]["messages"][0], ensure_ascii=False)
    assert "16:00 — закрытие" in system and "приоритетный источник" in system

    # снять с публикации — робот снова не знает
    assert (
        c.post(f"/app/admin/api/materials/{mid}/unpublish", headers=H).json()["status"] == "draft"
    )
    assert c.get("/app/admin/api/preview").json()["block"] == ""
    actions = [e["action"] for e in c.get("/app/admin/api/events").json()["events"]]
    assert actions[:5] == ["unpublish", "publish", "edit", "processed", "upload"]


def test_expired_and_budget(make_admin):
    long_brief = "# Документ\n" + "Пункт повестки в 10:00. " * 25  # ≈600 знаков
    complete = FakeComplete(_answer(brief=long_brief), _answer(brief=long_brief))
    c = _login(make_admin(complete=complete, kurultai_materials_max_chars=1_000))
    first = _upload_text(c, "Документ один 10:00")
    second = _upload_text(c, "Документ два 10:00")
    for mid in (first, second):
        _wait(c, mid)
        c.post(f"/app/admin/api/materials/{mid}/publish", headers=H)
    # бюджет 1000 знаков: две справки по ≈650 не помещаются — одна пропущена
    stats = c.get("/app/admin/api/preview").json()
    assert len(stats["included"]) == 1 and len(stats["skipped"]) == 1
    listed = {i["id"]: i for i in c.get("/app/admin/api/materials").json()["items"]}
    assert sum(i["over_budget"] for i in listed.values()) == 1
    # истёкший срок — не в промпте
    past = (datetime.now(UTC) - timedelta(days=2)).date().isoformat()
    c.patch(
        f"/app/admin/api/materials/{stats['included'][0]}", json={"valid_until": past}, headers=H
    )
    stats = c.get("/app/admin/api/preview").json()
    assert len(stats["expired"]) == 1 and len(stats["included"]) == 1


def test_file_upload_scan_ocr_and_download(make_admin):
    complete = FakeComplete(
        "[стр. 1]\nЗаседание 11.11.2026 в 10:00. 10:30 законопроект № 123-VIII. Зал заседаний.",
        _answer(),
    )
    c = _login(make_admin(complete=complete))
    pdf = _blank_pdf()
    r = c.post(
        "/app/admin/api/materials",
        files=[("files", ("скан.pdf", pdf, "application/pdf"))],
        headers=H,
    )
    mid = r.json()["created"][0]
    item = _wait(c, mid)
    assert item["status"] == "draft"
    assert "распознан со скана" in item["warnings"][0]
    assert "Заседание 11.11.2026" in item["source_text"]
    ocr_messages, fmt = complete.calls[0]
    assert fmt is None and ocr_messages[0]["content"][1]["type"] == "file"
    f = c.get(f"/app/admin/api/materials/{mid}/file")
    assert f.status_code == 200 and f.content == pdf
    assert "filename*=UTF-8''%D1%81%D0%BA%D0%B0%D0%BD.pdf" in f.headers["content-disposition"]


def test_upload_rejects_and_errors_and_reprocess(make_admin):
    complete = FakeComplete(st.StructureError("модель ответила не в формате JSON"), _answer())
    c = _login(make_admin(complete=complete, kurultai_materials_upload_mb=1))
    r = c.post(
        "/app/admin/api/materials",
        files=[
            ("files", ("virus.exe", b"MZ\x00\x00", "application/octet-stream")),
            ("files", ("big.txt", b"a" * (1024 * 1024 + 1), "text/plain")),
            ("files", ("ok.txt", SOURCE.encode(), "text/plain")),
        ],
        headers=H,
    )
    body = r.json()
    assert r.status_code == 201 and len(body["created"]) == 1
    assert {x["file"] for x in body["rejected"]} == {"virus.exe", "big.txt"}
    mid = body["created"][0]
    item = _wait(c, mid)
    assert item["status"] == "error" and "JSON" in item["error"]
    assert c.post(f"/app/admin/api/materials/{mid}/publish", headers=H).status_code == 409
    assert c.post(f"/app/admin/api/materials/{mid}/reprocess", headers=H).status_code == 202
    assert _wait(c, mid)["status"] == "draft"
    assert c.delete(f"/app/admin/api/materials/{mid}", headers=H).status_code == 200
    assert c.get(f"/app/admin/api/materials/{mid}").status_code == 404


def test_interrupted_processing_recovers_on_restart(make_admin, settings):
    from sqlalchemy.orm import Session

    from app.db.models import KurultaiMaterialRow

    c = _login(make_admin())
    with Session(c.app.state.engine) as s, s.begin():
        s.add(KurultaiMaterialRow(title="Повис", status="processing", source_text="x"))
    c2 = _login(make_admin())
    items = c2.get("/app/admin/api/materials").json()["items"]
    stuck = next(i for i in items if i["title"] == "Повис")
    assert stuck["status"] == "error" and "Переобработать" in stuck["error"]


def test_materials_only_for_kurultai_agent(make_admin):
    """Материалы — только у агента Курултая; агент Astana Hub их не получает."""
    from app.profiles import ASTANA_HUB, KURULTAI

    c = make_admin()
    chats = c.app.state.chats
    assert chats[KURULTAI].materials is c.app.state.kurultai_materials
    assert getattr(chats[ASTANA_HUB], "materials", None) is None
