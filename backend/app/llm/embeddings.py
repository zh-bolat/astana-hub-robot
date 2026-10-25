"""Эмбеддинги для поиска по базе (app/knowledge/index.py, app/knowledge/search.py).

- openrouter — POST {base}/embeddings, модель EMBEDDING_MODEL (google/gemini-embedding-2), 768 измерений
  (= kb_chunks.embedding). У gemini-embedding-2 нет task_type — роль текста задаётся префиксом: вопрос
  `task: search result | query: …`, фрагмент `title: … | text: …`. Замеры 2026-10-26 с прод-сервера: один
  вопрос — 0,31–0,35 с, казахский вопрос находит русский текст.
  Индексация (синхронно: фоновый поток сервера или CLI) — батчи ≤ EMBEDDING_BATCH_SIZE, повторы на 429/5xx/обрыв.
  Вопрос (async, на пути ответа) — одна попытка с таймаутом EMBEDDING_QUERY_TIMEOUT_S: не успели — поиск
  только по словам.
- fake — без сети и детерминированно (тесты, CI): мешок основ слов (первые 5 букв, как в app/llm/fake.py) →
  хеш → 768 измерений, L2-норма. Тексты с общими основами близки, без общих — ортогональны.
"""

from __future__ import annotations

import hashlib
import math
import time
from collections.abc import Sequence
from typing import Protocol

import httpx

from app.config import Settings
from app.db.models import EMBEDDING_DIM
from app.llm.fake import _stem, _tokens
from app.logging_setup import logger

_RETRYABLE_HTTP = {408, 409, 425, 429, 500, 502, 503, 504}
RETRY_DELAYS_S = (1.0, 3.0)  # индексация: 3 попытки на батч


class EmbeddingError(Exception):
    pass


class Embedder(Protocol):
    name: str
    model: str  # → kb_chunks.embedding_model: векторы разных моделей не сравниваются

    def embed_documents(self, items: Sequence[tuple[str | None, str]]) -> list[list[float]]:
        """(заголовок, текст) → вектор на каждый; блокирующий вызов (индексация)."""
        ...

    async def embed_query(self, text: str) -> list[float]: ...

    async def aclose(self) -> None: ...


def _normalized(vec: Sequence[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    return [x / norm for x in vec] if norm else list(vec)


def query_text(text: str) -> str:
    return f"task: search result | query: {text}"


def document_text(title: str | None, content: str) -> str:
    return f"title: {title or 'none'} | text: {content}"


class OpenRouterEmbeddings:
    name = "openrouter"

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str = "https://openrouter.ai/api/v1",
        dim: int = EMBEDDING_DIM,
        batch_size: int = 64,
        timeout_s: float = 30.0,
        query_timeout_s: float = 1.0,
        transport: httpx.BaseTransport | None = None,
        async_transport: httpx.AsyncBaseTransport | None = None,
        retry_delays_s: Sequence[float] = RETRY_DELAYS_S,
    ) -> None:
        self.model = model
        self.dim = dim
        self.batch_size = batch_size
        self.url = base_url.rstrip("/") + "/embeddings"
        self.retry_delays_s = tuple(retry_delays_s)
        headers = {"Authorization": f"Bearer {api_key}"}
        self._client = httpx.Client(headers=headers, timeout=timeout_s, transport=transport)
        self._aclient = httpx.AsyncClient(
            headers=headers, timeout=query_timeout_s, transport=async_transport
        )

    def _body(self, texts: list[str]) -> dict:
        return {"model": self.model, "input": texts, "dimensions": self.dim}

    def _vectors(self, resp: httpx.Response, n: int) -> list[list[float]]:
        if resp.status_code != 200:
            raise EmbeddingError(f"http {resp.status_code}: {resp.text[:200]}")
        try:
            payload = resp.json()
        except ValueError as exc:
            raise EmbeddingError("invalid json") from exc
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, list) or len(data) != n:
            # OpenRouter иногда отвечает 200 с {"error": {...}}
            error = payload.get("error") if isinstance(payload, dict) else None
            raise EmbeddingError(f"bad response: {str(error or payload)[:200]}")
        out: list[list[float]] = [[] for _ in range(n)]
        for pos, item in enumerate(data):
            idx = item.get("index", pos)
            vec = item.get("embedding")
            if not isinstance(vec, list) or len(vec) != self.dim or not 0 <= idx < n:
                raise EmbeddingError(f"bad embedding #{idx}: dim {len(vec or [])}")
            out[idx] = _normalized(vec)
        return out

    def _batch(self, texts: list[str]) -> list[list[float]]:
        error: Exception | None = None
        for attempt in range(len(self.retry_delays_s) + 1):
            if attempt:
                time.sleep(self.retry_delays_s[attempt - 1])
            try:
                resp = self._client.post(self.url, json=self._body(texts))
            except httpx.HTTPError as exc:  # обрыв, таймаут
                error = exc
                continue
            if resp.status_code in _RETRYABLE_HTTP:
                error = EmbeddingError(f"http {resp.status_code}")
                continue
            return self._vectors(resp, len(texts))
        raise EmbeddingError(f"embeddings failed: {type(error).__name__}: {error}")

    def embed_documents(self, items: Sequence[tuple[str | None, str]]) -> list[list[float]]:
        texts = [document_text(title, content) for title, content in items]
        out: list[list[float]] = []
        for i in range(0, len(texts), self.batch_size):
            started = time.perf_counter()
            out += self._batch(texts[i : i + self.batch_size])
            logger.info(
                "embeddings_batch",
                extra={
                    "model": self.model,
                    "texts": len(texts[i : i + self.batch_size]),
                    "ms": int((time.perf_counter() - started) * 1000),
                },
            )
        return out

    async def embed_query(self, text: str) -> list[float]:
        try:
            resp = await self._aclient.post(self.url, json=self._body([query_text(text)]))
        except httpx.HTTPError as exc:
            raise EmbeddingError(f"{type(exc).__name__}: {exc}") from exc
        return self._vectors(resp, 1)[0]

    async def aclose(self) -> None:
        self._client.close()
        await self._aclient.aclose()


class FakeEmbeddings:
    """Детерминированные векторы без сети. calls / texts — сколько раз и сколько текстов эмбеддилось
    при индексации (тесты: повторная индексация — ноль вызовов)."""

    name = "fake"

    def __init__(self, model: str = "fake-stems-768", fail: bool = False, dim: int = EMBEDDING_DIM):
        self.model = model
        self.dim = dim
        self.fail = fail  # True — индексация и вопрос падают (провайдер недоступен)
        self.calls = 0
        self.texts = 0
        self.queries = 0

    def vector(self, text: str) -> list[float]:
        vec = [0.0] * self.dim
        for word in _tokens(text):
            digest = hashlib.blake2b(_stem(word).encode(), digest_size=8).digest()
            vec[int.from_bytes(digest, "big") % self.dim] += 1.0
        if not any(vec):
            vec[0] = 1.0  # пустой текст: нулевой вектор pgvector не сравнивает
        return _normalized(vec)

    def embed_documents(self, items: Sequence[tuple[str | None, str]]) -> list[list[float]]:
        if self.fail:
            raise EmbeddingError("fake embeddings configured to fail")
        self.calls += 1
        self.texts += len(items)
        return [self.vector(f"{title or ''} {content}") for title, content in items]

    async def embed_query(self, text: str) -> list[float]:
        self.queries += 1
        if self.fail:
            raise EmbeddingError("fake embeddings configured to fail")
        return self.vector(text)

    async def aclose(self) -> None:
        return None


def build_embedder(settings: Settings) -> Embedder | None:
    """None — эмбеддингов нет (EMBEDDING_PROVIDER=off или auto без OPENROUTER_API_KEY)."""
    provider = settings.resolved_embedding_provider()
    if provider == "fake":
        return FakeEmbeddings()
    if provider != "openrouter":
        return None
    if settings.openrouter_api_key is None:
        raise RuntimeError("OPENROUTER_API_KEY is required for EMBEDDING_PROVIDER=openrouter")
    return OpenRouterEmbeddings(
        api_key=settings.openrouter_api_key.get_secret_value(),
        model=settings.embedding_model,
        base_url=settings.openrouter_base_url,
        batch_size=settings.embedding_batch_size,
        timeout_s=settings.embedding_timeout_s,
        query_timeout_s=settings.embedding_query_timeout_s,
    )
