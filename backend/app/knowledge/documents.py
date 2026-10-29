"""Длинные документы базы знаний (программы, правила, FAQ): KNOWLEDGE_DIR/documents/<slug>.md → таблица documents.

Формат файла — markdown с шапкой (front matter):

    ---
    title: Hub Space — коворкинг и офисы
    lang: ru
    source_url: https://astanahub.com/ru/l/hubspace
    active: true
    updated: 2026-10-26
    ---
    # Hub Space — коворкинг и офисы

    ## Кто может участвовать
    Текст…

- slug — имя файла без .md, только [a-z0-9_]; title — из шапки, иначе первый заголовок «#», иначе slug;
  lang — ru по умолчанию; source_url, active, updated — необязательны (active: false — документ выключен).
- Шапка в content не попадает. Файл с ошибкой пропускается (предупреждение в лог и в отчёт `kb seed-yaml`),
  остальные документы и факты загружаются. Папки нет — ноль документов.
- Записываются в БД при `kb seed-yaml` и автосиде (KnowledgeRepo.apply, та же транзакция, что и факты);
  синк Google-таблицы документы не трогает. На фрагменты для поиска их режет app/knowledge/chunks.py.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from app.logging_setup import logger

DOCUMENTS_DIR = "documents"
SLUG = re.compile(r"[a-z0-9_]+")
_LANGS = ("ru", "kk", "en")
_FALSE = {"false", "no", "0", "нет", "off"}
_H1 = re.compile(r"^#\s+(.+?)\s*#*\s*$")


@dataclass(frozen=True)
class Document:
    slug: str
    title: str
    lang: str
    content: str  # тело без шапки
    source_url: str | None = None
    active: bool = True

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(self.content.encode()).hexdigest()


@dataclass
class DocumentsLoad:
    documents: list[Document] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)  # «файл: причина» — пропущенные файлы


def _split_front_matter(text: str) -> tuple[dict, str]:
    text = text.lstrip("﻿")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            meta = yaml.safe_load("\n".join(lines[1:i])) or {}
            if not isinstance(meta, dict):
                raise ValueError("front matter must be a mapping")
            return meta, "\n".join(lines[i + 1 :])
    raise ValueError("front matter is not closed with ---")


def _active(value: object) -> bool:
    if value is None:
        return True
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() not in _FALSE


def parse_document(slug: str, text: str) -> Document:
    if not SLUG.fullmatch(slug):
        raise ValueError(f"bad slug {slug!r}: only a-z, 0-9 and _ are allowed")
    meta, body = _split_front_matter(text)
    body = body.strip()
    if not body:
        raise ValueError("empty document")
    title = str(meta.get("title") or "").strip()
    if not title:
        h1 = next((m.group(1) for m in map(_H1.match, body.splitlines()) if m), None)
        title = h1 or slug
    lang = str(meta.get("lang") or "ru").strip().lower()
    if lang not in _LANGS:
        raise ValueError(f"bad lang {lang!r}: ru, kk or en")
    url = str(meta.get("source_url") or "").strip() or None
    return Document(
        slug=slug,
        title=title,
        lang=lang,
        content=body,
        source_url=url,
        active=_active(meta.get("active")),
    )


def load_documents(knowledge_dir: Path) -> DocumentsLoad:
    """Все *.md из KNOWLEDGE_DIR/documents (по имени файла)."""
    out = DocumentsLoad()
    directory = Path(knowledge_dir) / DOCUMENTS_DIR
    if not directory.is_dir():
        return out
    for path in sorted(directory.glob("*.md")):
        try:
            out.documents.append(parse_document(path.stem, path.read_text(encoding="utf-8")))
        except (OSError, UnicodeDecodeError, ValueError, yaml.YAMLError) as exc:
            reason = f"{path.name}: {str(exc)[:200]}"
            out.errors.append(reason)
            logger.warning("knowledge_document_skipped", extra={"error": reason})
    return out


def document_values(d: Document) -> dict:
    return {
        "slug": d.slug,
        "title": d.title,
        "source_url": d.source_url,
        "lang": d.lang,
        "content": d.content,
        "content_hash": d.content_hash,
        "is_active": d.active,
    }
