"""Eval по набору вопросов (шаг 3.8): % правильных и % found по языкам.

    uv run python -m eval.run_eval                      # провайдер из .env (без ключа — fake)
    uv run python -m eval.run_eval --min-accuracy 0.9   # код выхода 1, если ниже порога (для CI)
    uv run python -m eval.run_eval --json report.json

Вопрос считается правильным, если совпал found с ожидаемым, нашёлся expect_source (если задан),
есть все expect_contains и язык ответа совпал с языком вопроса.
Лог вопросов пишется во временную БД, боевой лог не засоряется.
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
import tempfile
import time
from collections import defaultdict
from pathlib import Path

import yaml
from fastapi.testclient import TestClient

from app.config import Settings
from app.knowledge.source import YamlKnowledgeSource
from app.main import create_app
from eval.memory_log import MemoryQuestionLog

DEFAULT_QUESTIONS = Path(__file__).with_name("questions.yaml")


def run(questions_path: Path, pass_lang: bool) -> dict:
    data = yaml.safe_load(questions_path.read_text(encoding="utf-8"))
    questions = data["questions"]
    token = secrets.token_hex(16)
    with tempfile.TemporaryDirectory() as tmp:
        settings = Settings(
            robot_token=token,
            # Postgres не нужен: база знаний — YAML, лог вопросов — в памяти
            database_url="postgresql+psycopg://eval@127.0.0.1:1/none",
            db_auto_migrate=False,
            knowledge_snapshot_path=Path(tmp) / "snapshot.json",
            rate_limit_per_min=0,
            log_level="WARNING",
        )
        app = create_app(
            settings,
            knowledge_source=YamlKnowledgeSource(settings.knowledge_dir),
            log_repo=MemoryQuestionLog(),  # type: ignore[arg-type]
        )
        provider = app.state.service.text_llm
        results = []
        with TestClient(app) as client:
            for q in questions:
                body = {"question": q["question"], "session_id": f"eval-{q['id']}"}
                if pass_lang:
                    body["lang"] = q["lang"]
                t0 = time.perf_counter()
                r = client.post("/v1/ask", json=body, headers={"Authorization": f"Bearer {token}"})
                latency_ms = int((time.perf_counter() - t0) * 1000)
                res = r.json() if r.status_code == 200 else {}
                reasons = []
                if r.status_code != 200:
                    reasons.append(f"http {r.status_code}")
                else:
                    if res["found"] != q["expect_found"]:
                        reasons.append(f"found={res['found']}")
                    if q.get("expect_source") and q["expect_source"] not in res["sources"]:
                        reasons.append(f"sources={res['sources']}")
                    for sub in q.get("expect_contains", []):
                        if sub.lower() not in res["answer"].lower():
                            reasons.append(f"missing '{sub}'")
                    if res["lang"] != q["lang"]:
                        reasons.append(f"lang={res['lang']}")
                results.append(
                    {
                        "id": q["id"],
                        "lang": q["lang"],
                        "question": q["question"],
                        "ok": not reasons,
                        "reasons": reasons,
                        "found": res.get("found"),
                        "answer": res.get("answer"),
                        "latency_ms": latency_ms,
                    }
                )

    by_lang: dict[str, list[dict]] = defaultdict(list)
    for item in results:
        by_lang[item["lang"]].append(item)
    summary = {}
    for lang, items in sorted(by_lang.items()):
        summary[lang] = _stats(items)
    summary["all"] = _stats(results)
    return {
        "provider": f"{provider.name}:{provider.model}",
        "summary": summary,
        "results": results,
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
    args = parser.parse_args(argv)

    report = run(args.questions, pass_lang=not args.no_lang)
    print(f"provider: {report['provider']}")
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
