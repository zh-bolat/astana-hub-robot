"""Файл → текст: PDF, DOCX, DOC, XLSX, PPTX, TXT, MD, HTML, RTF, CSV, JSON и прочие текстовые форматы.

PDF без текстового слоя (скан) отмечается `needs_ocr=True` — текст распознаёт модель (structure.ocr_pdf).
Кодировка текстовых файлов: BOM → UTF-8 → UTF-16 по нулям → cp1251 (Windows, частая у русских документов).
Старый Word (.doc, OLE) читает `antiword` (в образе сервера); `.doc`, внутри которого RTF/DOCX/HTML, — по содержимому.
"""

from __future__ import annotations

import contextlib
import io
import json
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import PurePath
from typing import ClassVar

OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"  # Word 97–2003 (.doc) и прочие OLE2
XLSX_ROWS_PER_SHEET = 5000  # дальше — обрезаем лист с пометкой (это не справка, а выгрузка)

# Сколько текста держим из одного файла (≈100 тыс. токенов): больше — обрезаем и предупреждаем
MAX_TEXT_CHARS = 400_000
# Меньше стольких букв на страницу PDF — считаем сканом
SCAN_CHARS_PER_PAGE = 40

TEXT_EXT = {
    ".txt",
    ".md",
    ".markdown",
    ".csv",
    ".tsv",
    ".json",
    ".xml",
    ".yaml",
    ".yml",
    ".log",
    ".ini",
}
HTML_EXT = {".html", ".htm"}
SUPPORTED = TEXT_EXT | HTML_EXT | {".pdf", ".docx", ".doc", ".rtf", ".xlsx", ".xlsm", ".pptx"}


class ExtractError(Exception):
    """Файл не прочитать (повреждён, зашифрован, неизвестный формат) — текст для сотрудника."""


@dataclass
class Extracted:
    text: str
    kind: str  # pdf | docx | doc | xlsx | pptx | text | html | rtf
    pages: int | None = None
    needs_ocr: bool = False
    truncated: bool = False


def extract(data: bytes, filename: str) -> Extracted:
    ext = PurePath(filename or "").suffix.lower()
    if ext == ".pdf" or data[:5] == b"%PDF-":
        return _limit(_pdf(data))
    if ext in (".xlsx", ".xlsm"):
        return _limit(_xlsx(data))
    if ext == ".pptx":
        return _limit(_pptx(data))
    if ext == ".doc":
        return _limit(_doc(data))
    if ext == ".docx" or (data[:2] == b"PK" and ext not in TEXT_EXT):
        return _limit(_docx(data))
    if data[:8] == OLE_MAGIC:
        if ext == "":
            return _limit(_doc(data))
        raise ExtractError(  # .xls / .ppt / переименованный файл старого Office
            f"«{ext}» внутри — старый формат Office (Word 97–2003, Excel .xls, PowerPoint .ppt): сохраните файл "
            "как .docx / .xlsx / .pptx и загрузите снова."
        )
    if ext == ".rtf" or data[:5] == b"{\\rtf":
        return _limit(Extracted(_rtf(decode(data)), "rtf"))
    if ext in HTML_EXT:
        return _limit(Extracted(_html(decode(data)), "html"))
    if ext == ".json":
        text = decode(data)
        with contextlib.suppress(ValueError):  # JSON — с отступами: модели и человеку читать легче
            text = json.dumps(json.loads(text), ensure_ascii=False, indent=1)
        return _limit(Extracted(text, "text"))
    if ext in TEXT_EXT or ext == "" or _looks_text(data):
        return _limit(Extracted(decode(data), "text"))
    raise ExtractError(
        f"Формат «{ext or 'без расширения'}» не поддерживается. Подходят: PDF, DOCX, DOC, XLSX, PPTX, TXT, MD, "
        "HTML, RTF, CSV, JSON."
    )


def decode(data: bytes) -> str:
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8", errors="replace")
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        pass
    head = data[:2000]
    if head and head.count(b"\x00") > len(head) // 4:  # UTF-16 без BOM
        return data.decode("utf-16-le", errors="replace")
    return data.decode("cp1251", errors="replace")


def _looks_text(data: bytes) -> bool:
    head = data[:4000]
    return bool(head) and b"\x00" not in head


def _limit(ex: Extracted) -> Extracted:
    text = normalize(ex.text)
    if len(text) > MAX_TEXT_CHARS:
        text, ex.truncated = text[:MAX_TEXT_CHARS], True
    ex.text = text
    return ex


def normalize(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ").replace("\x00", "")
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{4,}", "\n\n\n", text)
    return text.strip()


# ---------- форматы ----------
def _pdf(data: bytes) -> Extracted:
    from pypdf import PdfReader
    from pypdf.errors import PdfReadError

    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            try:
                reader.decrypt("")  # «защита от копирования» без пароля
            except Exception as exc:
                raise ExtractError(
                    "PDF защищён паролем — снимите пароль и загрузите снова."
                ) from exc
        pages = []
        for i, page in enumerate(reader.pages, 1):
            try:
                pages.append(f"[стр. {i}]\n{page.extract_text() or ''}")
            except Exception:
                pages.append(f"[стр. {i}]\n")
    except ExtractError:
        raise
    except (PdfReadError, ValueError, KeyError, TypeError) as exc:
        raise ExtractError("PDF повреждён или не читается.") from exc
    text = "\n\n".join(pages)
    letters = sum(ch.isalpha() for ch in text) - 3 * len(pages)  # без «[стр. N]»
    needs_ocr = letters < SCAN_CHARS_PER_PAGE * max(len(pages), 1)
    return Extracted(text, "pdf", pages=len(pages), needs_ocr=needs_ocr)


def _docx(data: bytes) -> Extracted:
    try:
        import docx
        from docx.oxml.ns import qn

        doc = docx.Document(io.BytesIO(data))
    except Exception as exc:
        raise ExtractError(
            "DOCX повреждён или это не документ Word (.doc старого формата сохраните как .docx)."
        ) from exc
    out: list[str] = []
    body = doc.element.body
    for child in body.iterchildren():  # абзацы и таблицы — в порядке документа
        if child.tag == qn("w:p"):
            text = "".join(t.text or "" for t in child.iter(qn("w:t")))
            style = child.find(qn("w:pPr"))
            heading = style is not None and any(
                (s.get(qn("w:val")) or "").lower().startswith(("heading", "заголовок"))
                for s in style.iter(qn("w:pStyle"))
            )
            out.append(("## " + text) if heading and text.strip() else text)
        elif child.tag == qn("w:tbl"):
            for row in child.iter(qn("w:tr")):
                cells = [
                    " ".join(
                        "".join(t.text or "" for t in p.iter(qn("w:t")))
                        for p in cell.iter(qn("w:p"))
                    ).strip()
                    for cell in row.iter(qn("w:tc"))
                ]
                out.append("| " + " | ".join(cells) + " |")
            out.append("")
    return Extracted("\n".join(out), "docx")


def _doc(data: bytes) -> Extracted:
    """Файл .doc: часто это RTF, DOCX или HTML с чужим расширением; настоящий Word 97–2003 — через antiword."""
    if data[:5] == b"{\\rtf":
        return Extracted(_rtf(decode(data)), "rtf")
    if data[:2] == b"PK":
        return _docx(data)
    if data[:8] != OLE_MAGIC:
        if _looks_text(data):
            text = decode(data)
            if "<html" in text[:3000].lower() or "<body" in text[:3000].lower():
                return Extracted(_html(text), "html")
            return Extracted(text, "text")
        raise ExtractError(
            "DOC повреждён или не читается — сохраните в Word как .docx и загрузите снова."
        )
    exe = shutil.which("antiword")
    if not exe:
        raise ExtractError(
            "Старый формат Word (.doc) на этом сервере не читается — сохраните как .docx и загрузите снова."
        )
    with tempfile.NamedTemporaryFile(suffix=".doc") as tmp:
        tmp.write(data)
        tmp.flush()
        try:  # -m UTF-8.txt — кодировка вывода, -w 0 — без переносов строк по ширине
            res = subprocess.run(
                [exe, "-m", "UTF-8.txt", "-w", "0", tmp.name], capture_output=True, timeout=60
            )
        except subprocess.TimeoutExpired as exc:
            raise ExtractError("DOC читается слишком долго — сохраните как .docx.") from exc
    text = res.stdout.decode("utf-8", errors="replace")
    if res.returncode != 0 or not text.strip():
        raise ExtractError(
            "DOC не читается (повреждён, защищён паролем или это не Word) — сохраните как .docx и загрузите снова."
        )
    text = re.sub(r"^[ \t]+", "", text, flags=re.M)  # antiword центрирует и выравнивает пробелами
    text = re.sub(r"(?<=\S) {2,}(?=\S)", " ", text)
    return Extracted(text, "doc")


def _cell(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    if hasattr(value, "isoformat"):  # date / datetime
        text = value.isoformat()
        return text[:10] if text.endswith("T00:00:00") else text.replace("T", " ")
    return " ".join(str(value).split())


def _xlsx(data: bytes) -> Extracted:
    try:
        import warnings

        import openpyxl

        with (
            warnings.catch_warnings()
        ):  # «Workbook contains no default style» у выгрузок из 1С/eKuriltay
            warnings.simplefilter("ignore")
            wb = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    except Exception as exc:
        raise ExtractError("XLSX повреждён или это не книга Excel.") from exc
    out: list[str] = []
    for ws in wb.worksheets:
        out.append(f"## {ws.title}")
        for n, row in enumerate(ws.iter_rows(values_only=True)):
            if n >= XLSX_ROWS_PER_SHEET:
                out.append(f"[лист обрезан: показаны первые {XLSX_ROWS_PER_SHEET} строк]")
                break
            cells = [_cell(v) for v in row]
            while cells and not cells[-1]:
                cells.pop()
            if any(cells):
                out.append("| " + " | ".join(cells) + " |")
        out.append("")
    pages = len(wb.worksheets)
    wb.close()
    return Extracted("\n".join(out), "xlsx", pages=pages)


def _shape_text(shape) -> list[str]:
    out: list[str] = []
    inner = getattr(shape, "shapes", None)  # группа фигур
    if inner is not None:
        for s in sorted(inner, key=lambda s: (s.top or 0, s.left or 0)):
            out.extend(_shape_text(s))
        return out
    if getattr(shape, "has_table", False):
        for row in shape.table.rows:
            out.append("| " + " | ".join(" ".join(c.text.split()) for c in row.cells) + " |")
        out.append("")
    elif getattr(shape, "has_text_frame", False):
        for p in shape.text_frame.paragraphs:
            text = "".join(r.text for r in p.runs).strip()
            if text:
                out.append(text)
    return out


def _pptx(data: bytes) -> Extracted:
    try:
        from pptx import Presentation

        prs = Presentation(io.BytesIO(data))
    except Exception as exc:
        raise ExtractError("PPTX повреждён или это не презентация PowerPoint.") from exc
    out: list[str] = []
    for i, slide in enumerate(prs.slides, 1):
        out.append(f"[слайд {i}]")
        for shape in sorted(slide.shapes, key=lambda s: (s.top or 0, s.left or 0)):
            out.extend(_shape_text(shape))
        if slide.has_notes_slide and slide.notes_slide.notes_text_frame is not None:
            notes = " ".join(slide.notes_slide.notes_text_frame.text.split())
            if notes:
                out.append(f"Заметки докладчика: {notes}")
        out.append("")
    return Extracted("\n".join(out), "pptx", pages=len(prs.slides))


class _HtmlText(HTMLParser):
    BLOCK: ClassVar[frozenset[str]] = frozenset(
        (
            "p",
            "div",
            "br",
            "li",
            "tr",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "section",
            "article",
            "table",
        )
    )

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "noscript"):
            self.skip += 1
        elif tag in self.BLOCK:
            self.parts.append("\n")
        elif tag in ("td", "th"):
            self.parts.append(" | ")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "noscript") and self.skip:
            self.skip -= 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def _html(text: str) -> str:
    p = _HtmlText()
    p.feed(text)
    return re.sub(r"[ \t]{2,}", " ", "".join(p.parts))


def _rtf(text: str) -> str:
    """Упрощённо: убрать служебные группы и команды, \\'hh → cp1251, \\uN → символ."""
    for group in ("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer"):
        text = re.sub(r"\{(?:\\\*)?\\" + group + r"(?:[^{}]|\{[^{}]*\})*\}", "", text)
    text = re.sub(
        r"\\'([0-9a-fA-F]{2})",
        lambda m: bytes([int(m.group(1), 16)]).decode("cp1251", "replace"),
        text,
    )
    text = re.sub(r"\\u(-?\d+)\??", lambda m: chr(int(m.group(1)) % 65536), text)
    text = re.sub(r"\\(par|line)\b ?", "\n", text)
    text = re.sub(r"\\tab\b ?", "\t", text)
    text = re.sub(r"\\[a-zA-Z]+-?\d* ?", "", text)
    return text.replace("{", "").replace("}", "").replace("\\\\", "\\")
