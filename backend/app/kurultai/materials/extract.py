"""Файл → текст: PDF, DOCX, TXT, MD, HTML, RTF, CSV, JSON и прочие текстовые форматы.

PDF без текстового слоя (скан) отмечается `needs_ocr=True` — текст распознаёт модель (structure.ocr_pdf).
Кодировка текстовых файлов: BOM → UTF-8 → UTF-16 по нулям → cp1251 (Windows, частая у русских документов).
"""

from __future__ import annotations

import contextlib
import io
import json
import re
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import PurePath
from typing import ClassVar

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
SUPPORTED = TEXT_EXT | HTML_EXT | {".pdf", ".docx", ".rtf"}


class ExtractError(Exception):
    """Файл не прочитать (повреждён, зашифрован, неизвестный формат) — текст для сотрудника."""


@dataclass
class Extracted:
    text: str
    kind: str  # pdf | docx | text | html | rtf
    pages: int | None = None
    needs_ocr: bool = False
    truncated: bool = False


def extract(data: bytes, filename: str) -> Extracted:
    ext = PurePath(filename or "").suffix.lower()
    if ext == ".pdf" or data[:5] == b"%PDF-":
        return _limit(_pdf(data))
    if ext == ".docx" or (data[:2] == b"PK" and ext not in TEXT_EXT):
        return _limit(_docx(data))
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
        f"Формат «{ext or 'без расширения'}» не поддерживается. Подходят: PDF, DOCX, TXT, MD, HTML, RTF, CSV, JSON."
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
