"""Админка материалов: форматы .doc, .xlsx, .pptx (app/kurultai/materials/extract.py)."""

from __future__ import annotations

import io
import shutil
from datetime import date

import pytest

from app.kurultai.materials import extract as ex


def _xlsx() -> bytes:
    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Статистика"
    ws.append(["Показатель", "Всего", "Дата"])
    ws.append(["Поступило законопроектов", 61.0, date(2026, 11, 9)])
    ws.append([None, None, None])
    ws.append(["Принято", 13, None])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _pptx() -> bytes:
    from pptx import Presentation
    from pptx.util import Inches

    prs = Presentation()
    slide = prs.slides.add_slide(prs.slide_layouts[1])
    slide.shapes.title.text = "Аудит науки"
    slide.placeholders[1].text = "Проверено 12 организаций"
    rows, cols = 2, 2
    table = slide.shapes.add_table(rows, cols, Inches(1), Inches(4), Inches(6), Inches(1)).table
    table.cell(0, 0).text, table.cell(0, 1).text = "Показатель", "Значение"
    table.cell(1, 0).text, table.cell(1, 1).text = "Нарушений", "7"
    slide.notes_slide.notes_text_frame.text = "Сказать про сроки"
    buf = io.BytesIO()
    prs.save(buf)
    return buf.getvalue()


def test_formats_listed():
    assert {".doc", ".xlsx", ".pptx"} <= ex.SUPPORTED


def test_extract_xlsx_rows_sheets_and_numbers():
    got = ex.extract(_xlsx(), "Статистика по законопроектам.xlsx")
    assert got.kind == "xlsx" and got.pages == 1
    assert "## Статистика" in got.text
    assert (
        "| Поступило законопроектов | 61 | 2026-11-09 |" in got.text
    )  # 61.0 → 61, дата без времени
    assert "| Принято | 13 |" in got.text  # пустые хвостовые ячейки отброшены
    assert got.text.count("|") < 30  # пустая строка не попала


def test_extract_pptx_slides_tables_and_notes():
    got = ex.extract(_pptx(), "През к докладу.pptx")
    assert got.kind == "pptx" and got.pages == 1
    assert (
        "[слайд 1]" in got.text
        and "Аудит науки" in got.text
        and "Проверено 12 организаций" in got.text
    )
    assert "| Нарушений | 7 |" in got.text
    assert "Заметки докладчика: Сказать про сроки" in got.text


def test_doc_extension_with_other_content_inside():
    rtf = b"{\\rtf1\\ansi\\ansicpg1251 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2 \\par}"
    assert ex.extract(rtf, "старый.doc").text == "Привет"
    html = (
        "<html><body><p>Повестка</p><table><tr><td>1</td><td>Отчёт</td></tr></table></body></html>"
    )
    got = ex.extract(html.encode("utf-8"), "выгрузка.doc")
    assert got.kind == "html" and "Повестка" in got.text and "Отчёт" in got.text
    with pytest.raises(ex.ExtractError):  # мусор под видом .doc
        ex.extract(b"\x01\x02\x03\x00\x00\xff" * 50, "битый.doc")


def test_doc_ole_without_antiword_gives_hint(monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda _name: None)
    with pytest.raises(ex.ExtractError, match="docx"):
        ex.extract(ex.OLE_MAGIC + b"\x00" * 600, "доклад.doc")


@pytest.mark.skipif(
    shutil.which("antiword") is None, reason="antiword не установлен (есть в образе сервера)"
)
def test_doc_ole_via_antiword_rejects_broken():
    with pytest.raises(ex.ExtractError):
        ex.extract(ex.OLE_MAGIC + b"\x00" * 600, "доклад.doc")
