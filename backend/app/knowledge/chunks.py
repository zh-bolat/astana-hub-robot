"""Фрагменты для поиска (kb_chunks): что должно лежать в индексе для текущей базы.

- Факт — фрагмент на каждый язык, где есть ответ: вопрос + ответ (title — вопрос).
- Место — фрагмент на язык: название, синонимы, описание (title — название).
- Документ — фрагменты на его языке, по заголовкам markdown (#, ##, ###):
  - мелкие разделы склеиваются, пока фрагмент не больше MERGE_CHARS;
  - раздел длиннее MAX_CHARS режется по абзацам (абзац длиннее — по строкам и предложениям) на куски
    ≤ MAX_CHARS (~600 токенов); следующий кусок начинается с конца предыдущего (≤ OVERLAP_CHARS: последний
    абзац или последние предложения), чтобы мысль на стыке не потерялась;
  - title — «Заголовок документа — заголовок раздела» (подраздел ### — через « — » после раздела ##);
    заголовки следующих склеенных разделов остаются строкой в тексте фрагмента;
  - номер фрагмента — с 1 по порядку в документе (id в ответе — `document:<slug>#<n>`).
Ключ фрагмента — (source_type, source_id, lang, chunk_no); content_hash — sha256 заголовка и текста.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

from app.knowledge.models import KnowledgeBase

LANGS = ("ru", "kk", "en")
MAX_CHARS = 2400
MERGE_CHARS = 1200
OVERLAP_CHARS = 300

_HEADING = re.compile(r"^(#{1,3})\s+(.+?)\s*#*\s*$")
_FENCE = re.compile(r"^\s*(```|~~~)")
_SENTENCE = re.compile(r"(?<=[.!?…])\s+")

ChunkKey = tuple[str, str, str, int]  # source_type, source_id, lang, chunk_no


@dataclass(frozen=True)
class ChunkSpec:
    source_type: str  # fact | place | document
    source_id: str
    lang: str
    chunk_no: int
    title: str
    content: str

    @property
    def key(self) -> ChunkKey:
        return (self.source_type, self.source_id, self.lang, self.chunk_no)

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(f"{self.title}\n{self.content}".encode()).hexdigest()


def citation_id(source_type: str, source_id: str, chunk_no: int) -> str:
    """id фрагмента в ответе (docs/api-contract.md, v1.3): document:<slug>#<n> | fact:<id> | place:<id>."""
    if source_type == "document":
        return f"document:{source_id}#{chunk_no}"
    return f"{source_type}:{source_id}"


# ---------------------------------------------------------------- документы


@dataclass
class _Section:
    title: str | None  # заголовок раздела (с родительским ##); None — текст до первого раздела
    paragraphs: list[str]

    @property
    def text(self) -> str:
        return "\n\n".join(self.paragraphs)


def _sections(doc_title: str, body: str) -> list[_Section]:
    sections: list[_Section] = [_Section(None, [])]
    h2: str | None = None
    para: list[str] = []
    fenced = False

    def flush() -> None:
        text = "\n".join(para).strip()
        if text:
            sections[-1].paragraphs.append(text)
        para.clear()

    for line in body.splitlines():
        if _FENCE.match(line):
            fenced = not fenced
        m = None if fenced else _HEADING.match(line)
        if m is None:
            if line.strip():
                para.append(line.rstrip())
            else:
                flush()
            continue
        flush()
        level, heading = len(m.group(1)), m.group(2).strip()
        if level == 1:
            # заголовок документа (обычно = title) — не раздел; другой # — раздел верхнего уровня
            if heading.casefold() == doc_title.casefold():
                continue
            h2, title = None, heading
        elif level == 2:
            h2 = title = heading
        else:
            title = f"{h2} — {heading}" if h2 else heading
        sections.append(_Section(title, []))
    flush()
    return [s for s in sections if s.paragraphs]


def _tail(text: str, limit: int) -> str:
    """Перекрытие: последние предложения текста не длиннее limit (пусто, если и одно длиннее)."""
    out = ""
    for sentence in reversed(_SENTENCE.split(text.strip())):
        candidate = f"{sentence} {out}".strip()
        if len(candidate) > limit:
            break
        out = candidate
    return out


def _pieces(paragraph: str, limit: int) -> list[str]:
    """Абзац длиннее limit → по строкам, затем по предложениям, в крайнем случае — по символам."""
    if len(paragraph) <= limit:
        return [paragraph]
    for parts, sep in ((paragraph.split("\n"), "\n"), (_SENTENCE.split(paragraph), " ")):
        if len(parts) > 1:
            out: list[str] = []
            cur = ""
            for part in parts:
                if cur and len(cur) + len(sep) + len(part) > limit:
                    out.append(cur)
                    cur = part
                else:
                    cur = f"{cur}{sep}{part}" if cur else part
            if cur:
                out.append(cur)
            return [p for piece in out for p in _pieces(piece, limit)]
    return [paragraph[i : i + limit] for i in range(0, len(paragraph), limit)]


def _split(paragraphs: list[str], max_chars: int, overlap: int) -> list[str]:
    units = [p for para in paragraphs for p in _pieces(para, max_chars - overlap - 2)]
    out: list[str] = []
    cur: list[str] = []
    for unit in units:
        size = sum(len(u) + 2 for u in cur) + len(unit)
        if cur and size > max_chars:
            out.append("\n\n".join(cur))
            last = cur[-1]
            carry = last if len(last) <= overlap else _tail(last, overlap)
            cur = [carry] if carry else []
        cur.append(unit)
    if cur:
        out.append("\n\n".join(cur))
    return out


def chunk_document(
    title: str,
    body: str,
    max_chars: int = MAX_CHARS,
    merge_chars: int = MERGE_CHARS,
    overlap: int = OVERLAP_CHARS,
) -> list[tuple[str, str]]:
    """Тело документа (без шапки) → [(title фрагмента, текст)] по порядку."""
    out: list[tuple[str, str]] = []
    cur_title: str | None = None
    cur: list[str] = []  # части текущего склеенного фрагмента

    def name(section_title: str | None) -> str:
        return f"{title} — {section_title}" if section_title else title

    def flush() -> None:
        nonlocal cur, cur_title
        if cur:
            out.append((name(cur_title), "\n\n".join(cur)))
        cur, cur_title = [], None

    for section in _sections(title, body):
        text = section.text
        if len(text) > max_chars:
            flush()
            out += [
                (name(section.title), piece)
                for piece in _split(section.paragraphs, max_chars, overlap)
            ]
            continue
        # заголовок склеенного раздела — строкой в тексте (у первого он в title фрагмента)
        part = f"{section.title}\n{text}" if cur and section.title else text
        if cur and sum(len(c) + 2 for c in cur) + len(part) > merge_chars:
            flush()
            part = text
        if not cur:
            cur_title = section.title
        cur.append(part)
    flush()
    return out


# ---------------------------------------------------------------- вся база


@dataclass(frozen=True)
class DocumentSource:
    """Активный документ из таблицы documents (что нужно для фрагментов и ссылки)."""

    slug: str
    title: str
    lang: str
    content: str
    source_url: str | None = None


def kb_chunks(kb: KnowledgeBase, documents: list[DocumentSource]) -> dict[ChunkKey, ChunkSpec]:
    """Желаемое содержимое индекса для активной базы и активных документов."""
    out: dict[ChunkKey, ChunkSpec] = {}

    def add(spec: ChunkSpec) -> None:
        out[spec.key] = spec

    for f in kb.facts:
        for lang in LANGS:
            question, answer = getattr(f.question, lang), getattr(f.answer, lang)
            if not answer:
                continue
            content = f"{question}\n{answer}" if question else answer
            add(ChunkSpec("fact", f.id, lang, 0, question or f.id, content))
    for p in kb.places:
        synonyms = ", ".join(p.synonyms)
        for lang in LANGS:
            name, description = getattr(p.names, lang), getattr(p.description, lang)
            if not name and not description:
                continue
            content = "\n".join(x for x in (name, synonyms, description) if x)
            add(ChunkSpec("place", p.id, lang, 0, name or p.map_name, content))
    for d in documents:
        for n, (title, content) in enumerate(chunk_document(d.title, d.content), start=1):
            add(ChunkSpec("document", d.slug, d.lang or "ru", n, title, content))
    return out
