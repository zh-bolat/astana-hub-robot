"""Гибридный поиск по kb_chunks (инструмент агента search_kb, app/agent/tools.py).

`KbSearch.search(query, lang, k=3)`:
1. Эмбеддинг вопроса (таймаут EMBEDDING_QUERY_TIMEOUT_S; ошибка/таймаут — дальше только по словам).
2. По смыслу: top-20 по косинусному расстоянию (`<=>`) среди активных фрагментов текущей модели. Поиск точный
   (полный перебор через MATERIALIZED CTE, мимо HNSW): фрагментов сотни, перебор — миллисекунды, а HNSW с
   фильтром is_active мог бы вернуть меньше 20 строк (docs/db-schema.md, раздел 2).
3. По словам: top-20 по полнотексту (`tsv`, конфигурация 'simple'), запрос — ИЛИ по словам вопроса без
   служебных слов; длинные слова — префиксом (`резиден:*` находит «резидент», «резидентам»), это заменяет
   стемминг, которого у 'simple' нет. Ранг — ts_rank_cd.
4. Слияние RRF (k=60): score = Σ 1 / (60 + место в списке).
5. Один и тот же фрагмент на разных языках (факт и место — по фрагменту на язык) — один результат: текст на
   языке вопроса, иначе ru, en, kk. Ссылка — documents.source_url (у фактов и мест — нет).
"""

from __future__ import annotations

import asyncio
import re
import time
from dataclasses import dataclass

from sqlalchemy import bindparam, text
from sqlalchemy.engine import Engine

from app.knowledge.chunks import citation_id
from app.lang import Lang
from app.llm.embeddings import Embedder
from app.logging_setup import logger

CANDIDATES = 20
RRF_K = 60

_TERM = re.compile(r"[^\W_]+", re.UNICODE)
_LATIN = re.compile(r"[a-z]")
# Служебные слова и то, что есть почти в каждом фрагменте (название хаба): по ним поиск только шумит
_STOPWORDS = """
    как где что кто когда какой какая какие какое каких какую каким это эти этот эта есть для при или они
    она оно его еще ещё все всё был была были быть будет мне меня мой моя вам вас ваш нас наш там тут здесь
    можно нужно надо чтобы если так уже про без над под через после перед между сколько почему зачем ли не
    на по из до от со во об то же бы вы мы ты он хочу хотел хотела подскажите скажите расскажите расскажи
    хаб хаба хабе хабу хабом астана астане астаны astana hub
    қалай қайда қандай қашан неше кім бар жоқ және үшін бұл сол мен сен сіз сізге керек болады ма ме ба бе
    па пе туралы
    the and for are is what where when how who can does do you your with about there this that have has
    from any tell please which
"""
STOPWORDS = frozenset(_STOPWORDS.split())


@dataclass(frozen=True)
class Term:
    stem: str
    prefix: bool  # искать как префикс (stem:*)

    def matches(self, word: str) -> bool:
        return word.startswith(self.stem) if self.prefix else word == self.stem


def query_terms(query: str) -> list[Term]:
    """Слова вопроса для полнотекста: без служебных, длинные — префиксом (обрезанное окончание)."""
    out: dict[str, Term] = {}
    for word in _TERM.findall(query.lower()):
        if word in STOPWORDS:
            continue
        if word.isdigit():
            term = Term(word, False)
        elif len(word) < 3:
            continue
        elif _LATIN.match(word):
            cut = 2 if len(word) >= 7 else 1 if len(word) >= 5 else 0
            term = Term(word[: min(len(word) - cut, 8)], cut > 0)
        else:
            cut = 3 if len(word) >= 7 else 2 if len(word) >= 5 else 1 if len(word) == 4 else 0
            term = Term(word[: min(len(word) - cut, 7)], cut > 0)
        out.setdefault(term.stem, term)
    return list(out.values())


def tsquery(terms: list[Term]) -> str:
    # слова — только буквы и цифры: спецсимволов tsquery в них нет
    return " | ".join(f"{t.stem}:*" if t.prefix else t.stem for t in terms)


@dataclass(frozen=True)
class SearchHit:
    id: str  # document:<slug>#<n> | fact:<id> | place:<id>
    type: str  # document | fact | place
    title: str
    content: str
    url: str | None
    lang: str
    score: float


def _lang_order(lang: str) -> list[str]:
    return list(dict.fromkeys([lang, "ru", "en", "kk"]))


class KbSearch:
    def __init__(
        self,
        engine: Engine,
        embedder: Embedder,
        query_timeout_s: float = 1.0,
        candidates: int = CANDIDATES,
        rrf_k: int = RRF_K,
    ) -> None:
        self.engine = engine
        self.embedder = embedder
        self.query_timeout_s = query_timeout_s
        self.candidates = candidates
        self.rrf_k = rrf_k

    async def search(self, query: str, lang: Lang, k: int = 3) -> list[SearchHit]:
        """Исключения БД пробрасываются (инструмент отвечает модели «поиск недоступен»)."""
        started = time.perf_counter()
        vec: list[float] | None = None
        embed_error: str | None = None
        try:
            vec = await asyncio.wait_for(
                self.embedder.embed_query(query), timeout=self.query_timeout_s
            )
        except Exception as exc:  # таймаут, сеть, провайдер — ищем только по словам
            embed_error = type(exc).__name__
        embed_ms = int((time.perf_counter() - started) * 1000)
        hits = await asyncio.to_thread(self._search_db, query, vec, lang, k)
        logger.info(
            "kb_search",
            extra={
                "lang": lang,
                "hits": [h.id for h in hits],
                "vector": vec is not None,
                "embed_error": embed_error,
                "embed_ms": embed_ms,
                "ms": int((time.perf_counter() - started) * 1000),
            },
        )
        return hits

    def _search_db(self, query: str, vec: list[float] | None, lang: str, k: int) -> list[SearchHit]:
        terms = query_terms(query)
        lists: list[list[tuple[str, str, int]]] = []
        with self.engine.connect() as conn:
            if vec is not None:
                rows = conn.execute(
                    text(
                        """
                        WITH c AS MATERIALIZED (
                            SELECT id, source_type, source_id, chunk_no,
                                   embedding <=> CAST(:vec AS vector) AS dist
                            FROM kb_chunks
                            WHERE is_active AND embedding IS NOT NULL AND embedding_model = :model
                        )
                        SELECT source_type, source_id, chunk_no FROM c ORDER BY dist, id LIMIT :n
                        """
                    ),
                    {
                        "vec": _vector_literal(vec),
                        "model": self.embedder.model,
                        "n": self.candidates,
                    },
                )
                lists.append([tuple(r) for r in rows])
            if terms:
                rows = conn.execute(
                    text(
                        """
                        SELECT source_type, source_id, chunk_no
                        FROM kb_chunks, to_tsquery('simple', :q) AS q
                        WHERE is_active AND tsv @@ q
                        ORDER BY ts_rank_cd(tsv, q) DESC, id LIMIT :n
                        """
                    ),
                    {"q": tsquery(terms), "n": self.candidates},
                )
                lists.append([tuple(r) for r in rows])
            # RRF; строки одного фрагмента на разных языках — один кандидат (лучший ранг в каждом списке)
            scores: dict[tuple[str, str, int], float] = {}
            for ranked in lists:
                seen: set[tuple[str, str, int]] = set()
                rank = 0
                for key in ranked:
                    if key in seen:
                        continue
                    seen.add(key)
                    rank += 1
                    scores[key] = scores.get(key, 0.0) + 1.0 / (self.rrf_k + rank)
            top = sorted(scores.items(), key=lambda kv: -kv[1])[:k]
            if not top:
                return []
            variants = conn.execute(
                text(
                    """
                    SELECT c.source_type, c.source_id, c.chunk_no, c.lang, c.title, c.content,
                           d.source_url
                    FROM kb_chunks c
                    LEFT JOIN documents d ON c.source_type = 'document' AND d.slug = c.source_id
                    WHERE c.is_active
                      AND (c.source_type, c.source_id, c.chunk_no) IN :keys
                    """
                ).bindparams(bindparam("keys", expanding=True)),
                {"keys": [key for key, _ in top]},
            ).all()
        order = _lang_order(lang)
        best: dict[tuple[str, str, int], object] = {}
        for row in variants:
            key = (row.source_type, row.source_id, row.chunk_no)
            rlang = row.lang.strip()
            cur = best.get(key)
            if cur is None or order.index(rlang) < order.index(cur.lang.strip()):  # type: ignore[attr-defined]
                best[key] = row
        hits: list[SearchHit] = []
        for key, score in top:
            row = best.get(key)
            if row is None:  # фрагмент выключили между запросами
                continue
            hits.append(
                SearchHit(
                    id=citation_id(*key),
                    type=key[0],
                    title=row.title or key[1],  # type: ignore[attr-defined]
                    content=row.content,  # type: ignore[attr-defined]
                    url=row.source_url if key[0] == "document" else None,  # type: ignore[attr-defined]
                    lang=row.lang.strip(),  # type: ignore[attr-defined]
                    score=round(score, 5),
                )
            )
        return hits


def _vector_literal(vec: list[float]) -> str:
    return "[" + ",".join(f"{x:.7g}" for x in vec) + "]"


# ---------------------------------------------------------------- выдержка для модели

_UNIT_SPLIT = re.compile(r"\n+")
_SENTENCES = re.compile(r"(?<=[.!?…])\s+")
LONG_LINE = 400


def _units(content: str) -> list[str]:
    out: list[str] = []
    for line in _UNIT_SPLIT.split(content):
        line = line.strip()
        if not line:
            continue
        out += _SENTENCES.split(line) if len(line) > LONG_LINE else [line]
    return out


def _cut(textv: str, limit: int) -> str:
    if len(textv) <= limit:
        return textv
    head = textv[: max(1, limit - 1)]
    for sep in (". ", "! ", "? ", "; ", ", ", " "):
        pos = head.rfind(sep)
        if pos >= limit // 2:
            return head[: pos + 1].rstrip() + "…"
    return head.rstrip() + "…"


def excerpt(content: str, query: str, limit: int) -> str:
    """Фрагмент длиннее limit → строки и предложения, где больше слов вопроса (в порядке текста, пропуски —
    «…»); совпадений нет — начало фрагмента."""
    if len(content) <= limit:
        return content
    units = _units(content)
    terms = query_terms(query)

    def score(unit: str) -> int:
        words = _TERM.findall(unit.lower())
        return sum(1 for t in terms if any(t.matches(w) for w in words))

    scores = [score(u) for u in units]
    if not any(scores):
        ranked = list(range(len(units)))
    else:
        ranked = sorted(range(len(units)), key=lambda i: (-scores[i], i))
    picked: list[int] = []
    used = 0
    for i in ranked:
        size = len(units[i]) + 2
        if used + size > limit:
            if not picked:  # даже лучшая строка не влезает — обрезаем её
                return _cut(units[i], limit)
            if not any(scores):
                break
            continue
        picked.append(i)
        used += size
    picked.sort()
    out: list[str] = []
    prev = -1
    for i in picked:
        if out and i != prev + 1:
            out.append("…")
        out.append(units[i])
        prev = i
    return "\n".join(out)
