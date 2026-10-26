"""Eval по набору вопросов (шаг 3.8): % правильных и % found по языкам.

    uv run python -m eval.run_eval                      # /v1/ask, провайдер из .env (без ключа — fake)
    uv run python -m eval.run_eval --min-accuracy 0.9   # код выхода 1, если ниже порога (для CI)
    uv run python -m eval.run_eval --json report.json
    # поиск по документам (задача 11): агент /v1/chat + поиск (RETRIEVAL_MODE: справка prefetch или search_kb);
    # нужен Postgres (временная БД на этом сервере: миграции, сид YAML и documents/*.md, индексация)
    # и OPENROUTER_API_KEY
    uv run python -m eval.run_eval --chat --questions eval/rag_questions.yaml \
        --database-url postgresql+psycopg://hub:…@127.0.0.1:5432/hub

Вопрос считается правильным, если совпал found с ожидаемым, нашёлся expect_source (если задан;
`document:<slug>` засчитывает любой фрагмент `document:<slug>#<n>`), есть все expect_contains и язык ответа
совпал с языком вопроса. Лог вопросов пишется в память (без --database-url) или во временную БД — боевой
лог не засоряется.
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
import tempfile
import time
import uuid
from collections import defaultdict
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from openai import AsyncOpenAI
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

from app.config import Settings
from app.knowledge.source import YamlKnowledgeSource
from app.llm.embeddings import Embedder
from app.main import create_app
from eval.memory_log import MemoryQuestionLog
from eval.run_injection import parse_sse

DEFAULT_QUESTIONS = Path(__file__).with_name("questions.yaml")


def source_matches(expected: str, sources: list[str]) -> bool:
    """`document:<slug>` (без номера) — любой фрагмент документа; остальное — точное совпадение."""
    if expected in sources:
        return True
    return (
        expected.startswith("document:")
        and "#" not in expected
        and any(s.startswith(expected + "#") for s in sources)
    )


@contextmanager
def temp_database(server_url: str) -> Iterator[str]:
    """Пустая БД на сервере server_url (нужно право CREATE DATABASE), удаляется после прогона."""
    name = f"hub_eval_{uuid.uuid4().hex[:10]}"
    admin = create_engine(
        make_url(server_url).set(database="postgres"), isolation_level="AUTOCOMMIT"
    )
    try:
        with admin.connect() as conn:
            conn.execute(text(f'CREATE DATABASE "{name}"'))
        yield make_url(server_url).set(database=name).render_as_string(hide_password=False)
    finally:
        with admin.connect() as conn:
            conn.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
        admin.dispose()


def _chat(client: TestClient, body: dict, token: str) -> tuple[int, dict]:
    """/v1/chat (SSE) → (status, ответ в форме /ask: answer, found, lang, sources, citations)."""
    headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}
    r = client.post("/v1/chat", json=body, headers=headers)
    if r.status_code != 200:
        return r.status_code, {}
    events = parse_sse(r.text)
    last = events[-1] if events else ("error", {})
    if last[0] != "done":
        code = (last[1].get("error") or {}).get("code")
        return {"llm_unavailable": 503, "timeout": 504}.get(code, 500), {}
    return 200, {
        "answer": " ".join(d["text"] for e, d in events if e == "phrase"),
        "found": last[1].get("found"),
        "lang": last[1].get("lang"),
        "sources": last[1].get("sources", []),
        "citations": last[1].get("citations", []),
    }


def _app(
    settings: Settings,
    db_url: str | None,
    chat_client: AsyncOpenAI | None,
    embedder: Embedder | None,
):
    if db_url is None:
        # Postgres не нужен: база знаний — YAML, лог вопросов — в памяти (поиска по документам нет)
        return create_app(
            settings,
            knowledge_source=YamlKnowledgeSource(settings.knowledge_dir),
            log_repo=MemoryQuestionLog(),  # type: ignore[arg-type]
            chat_client=chat_client,
        )
    app = create_app(settings, chat_client=chat_client, embedder=embedder)
    indexer = app.state.indexer
    if indexer is not None:  # фрагменты для поиска — до первого вопроса
        report = indexer.index()
        print(
            f"index: embedded={report.embedded} failed={report.failed} chunks={report.by_type}"
            + (f" error={report.error}" if report.error else ""),
            file=sys.stderr,
        )
    return app


def run(
    questions_path: Path,
    pass_lang: bool,
    endpoint: str = "ask",
    database_url: str | None = None,
    chat_client: AsyncOpenAI | None = None,
    embedder: Embedder | None = None,
) -> dict:
    """endpoint="chat" — через агента /v1/chat; database_url — сервер Postgres для временной БД (поиск по
    базе); chat_client / embedder — подменить модель агента и эмбеддинги (тесты)."""
    data = yaml.safe_load(questions_path.read_text(encoding="utf-8"))
    questions = data["questions"]
    token = secrets.token_hex(16)
    with tempfile.TemporaryDirectory() as tmp, _maybe_db(database_url) as db_url:
        settings = Settings(
            robot_token=token,
            database_url=db_url or "postgresql+psycopg://eval@127.0.0.1:1/none",
            db_auto_migrate=db_url is not None,
            knowledge_sheet_id=None,  # база — из KNOWLEDGE_DIR (YAML + documents)
            kb_seed_yaml_if_empty=True,
            knowledge_snapshot_path=Path(tmp) / "snapshot.json",
            rate_limit_per_min=0,
            log_level="WARNING",
        )
        app = _app(settings, db_url, chat_client, embedder)
        provider = app.state.service.text_llm
        chat = app.state.chat
        search = app.state.kb_search
        results = []
        try:
            with TestClient(app) as client:
                for q in questions:
                    body = {"question": q["question"], "session_id": f"eval-{q['id']}"}
                    if pass_lang:
                        body["lang"] = q["lang"]
                    t0 = time.perf_counter()
                    if endpoint == "chat":
                        status, res = _chat(client, body, token)
                    else:
                        r = client.post(
                            "/v1/ask", json=body, headers={"Authorization": f"Bearer {token}"}
                        )
                        status, res = r.status_code, (r.json() if r.status_code == 200 else {})
                    latency_ms = int((time.perf_counter() - t0) * 1000)
                    results.append(_check(q, status, res, latency_ms))
        finally:
            app.state.engine.dispose()

    by_lang: dict[str, list[dict]] = defaultdict(list)
    for item in results:
        by_lang[item["lang"]].append(item)
    summary = {}
    for lang, items in sorted(by_lang.items()):
        summary[lang] = _stats(items)
    summary["all"] = _stats(results)
    label = (
        chat.name
        if endpoint == "chat" and chat.model is not None
        else f"{provider.name}:{provider.model}"
    )
    return {
        "endpoint": endpoint,
        "provider": label,
        "retrieval": f"{chat.retrieval}:{search.embedder.model}" if search is not None else "off",
        "summary": summary,
        "results": results,
    }


@contextmanager
def _maybe_db(server_url: str | None) -> Iterator[str | None]:
    if server_url is None:
        yield None
        return
    with temp_database(server_url) as url:
        yield url


def _check(q: dict, status: int, res: dict, latency_ms: int) -> dict:
    reasons = []
    if status != 200:
        reasons.append(f"http {status}")
    else:
        if res["found"] != q["expect_found"]:
            reasons.append(f"found={res['found']}")
        if q.get("expect_source") and not source_matches(q["expect_source"], res["sources"]):
            reasons.append(f"sources={res['sources']}")
        for sub in q.get("expect_contains", []):
            if sub.lower() not in res["answer"].lower():
                reasons.append(f"missing '{sub}'")
        if res["lang"] != q["lang"]:
            reasons.append(f"lang={res['lang']}")
    return {
        "id": q["id"],
        "lang": q["lang"],
        "question": q["question"],
        "ok": not reasons,
        "reasons": reasons,
        "found": res.get("found"),
        "answer": res.get("answer"),
        "sources": res.get("sources"),
        "latency_ms": latency_ms,
    }


def _stats(items: list[dict]) -> dict:
    n = len(items)
    lat = sorted(i["latency_ms"] for i in items)
    return {
        "total": n,
        "accuracy": round(sum(i["ok"] for i in items) / n, 3) if n else 0.0,
        "found_rate": round(sum(bool(i["found"]) for i in items) / n, 3) if n else 0.0,
        "p50_ms": lat[n // 2] if n else 0,
        "p95_ms": lat[min(n - 1, int(n * 0.95))] if n else 0,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m eval.run_eval")
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    parser.add_argument("--min-accuracy", type=float, default=None)
    parser.add_argument("--json", type=Path, default=None, help="сохранить полный отчёт")
    parser.add_argument(
        "--no-lang", action="store_true", help="не передавать lang (проверка автоопределения)"
    )
    parser.add_argument("--chat", action="store_true", help="через агента /v1/chat вместо /v1/ask")
    parser.add_argument(
        "--database-url",
        default=None,
        help="Postgres для временной БД (поиск по документам); без него база — YAML, поиска нет",
    )
    args = parser.parse_args(argv)

    report = run(
        args.questions,
        pass_lang=not args.no_lang,
        endpoint="chat" if args.chat else "ask",
        database_url=args.database_url,
    )
    print(
        f"endpoint: {report['endpoint']}  provider: {report['provider']}  "
        f"retrieval: {report['retrieval']}"
    )
    print(f"{'lang':<5} {'total':>5} {'accuracy':>9} {'found':>7} {'p50ms':>6} {'p95ms':>6}")
    for lang, s in report["summary"].items():
        print(
            f"{lang:<5} {s['total']:>5} {s['accuracy']:>9.0%} {s['found_rate']:>7.0%}"
            f" {s['p50_ms']:>6} {s['p95_ms']:>6}"
        )
    failed = [r for r in report["results"] if not r["ok"]]
    for r in failed:
        print(f"FAIL {r['id']}: {r['question']} -> {', '.join(r['reasons'])}")
    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.min_accuracy is not None and report["summary"]["all"]["accuracy"] < args.min_accuracy:
        print(f"accuracy below {args.min_accuracy:.0%}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
