"""Eval «не мне» (v1.4): отличает ли агент реплики к роботу от чужих разговоров рядом (eval/addressed.yaml).

    uv run python -m eval.run_addressed                    # агент /v1/chat на модели из .env (AGENT_MODEL)
    uv run python -m eval.run_addressed --sleep 1          # пауза между запросами, с
    uv run python -m eval.run_addressed --json report.json
    uv run python -m eval.run_addressed --min-recall 0.8 --max-false-ignores 0   # пороги (по умолчанию)

Каждая реплика — отдельный разговор (своя session_id). Итог:
- false_ignores — реплик к роботу, на которые он промолчал (главная метрика: должно быть 0);
- ignore_recall — доля чужих реплик, на которые он промолчал.
Провал (код 1): false_ignores > --max-false-ignores, ignore_recall < --min-recall или тег [not_for_me] в ответе.
Ответы 503/504 (провайдер недоступен / лимит) считаются отдельно как errors.

Без ключа агента (CHAT_PROVIDER=off) /v1/chat отвечает как /ask и никогда не молчит — eval это покажет как
ignore_recall 0. Postgres не нужен: база знаний — YAML из knowledge/, лог вопросов — в памяти.
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
import tempfile
import time
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from openai import AsyncOpenAI

from app.agent.prompts import NOT_ADDRESSED_TAG
from app.config import Settings
from app.knowledge.source import YamlKnowledgeSource
from app.main import create_app
from eval.memory_log import MemoryQuestionLog
from eval.run_injection import parse_sse

DEFAULT_CASES = Path(__file__).with_name("addressed.yaml")


def _chat(client: TestClient, case: dict, token: str) -> tuple[int, dict]:
    """Реплика /v1/chat (SSE) → (status, {phrases, ignored, lang, actions})."""
    body = {"question": case["question"], "session_id": f"addr-{case['id']}", "lang": case["lang"]}
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
        "phrases": [d["text"] for e, d in events if e == "phrase"],
        "ignored": bool(last[1].get("ignored")),
        "lang": last[1].get("lang"),
        "actions": [d["type"] for e, d in events if e == "action"],
    }


def check(case: dict, status: int, res: dict) -> list[str]:
    if status != 200:
        return [f"http {status}"]
    answer = " ".join(res["phrases"])
    reasons = []
    if case["expect"] == "ignore":
        if not res["ignored"]:
            reasons.append("answered")
    elif res["ignored"]:
        reasons.append("false ignore")
    elif not res["phrases"] and not res["actions"]:
        reasons.append("empty answer")
    if "not_for_me" in answer.lower() or NOT_ADDRESSED_TAG in answer:
        reasons.append("tag spoken")
    return reasons


def run(
    cases_path: Path = DEFAULT_CASES,
    sleep_s: float = 0.0,
    chat_client: AsyncOpenAI | None = None,
    overrides: dict | None = None,
) -> dict:
    """chat_client — подменить клиент модели агента (тесты: фейковый OpenAI-совместимый транспорт)."""
    cases = yaml.safe_load(cases_path.read_text(encoding="utf-8"))["cases"]
    token = secrets.token_hex(16)
    with tempfile.TemporaryDirectory() as tmp:
        settings = Settings(
            robot_token=token,
            database_url="postgresql+psycopg://eval@127.0.0.1:1/none",
            db_auto_migrate=False,
            knowledge_snapshot_path=Path(tmp) / "snapshot.json",
            rate_limit_per_min=0,
            log_level="WARNING",
            **(overrides or {}),
        )
        app = create_app(
            settings,
            knowledge_source=YamlKnowledgeSource(settings.knowledge_dir),
            log_repo=MemoryQuestionLog(),  # type: ignore[arg-type]
            chat_client=chat_client,
        )
        results = []
        with TestClient(app) as client:
            for i, case in enumerate(cases):
                if i and sleep_s:
                    time.sleep(sleep_s)
                t0 = time.perf_counter()
                status, res = _chat(client, case, token)
                latency_ms = int((time.perf_counter() - t0) * 1000)
                assert app.state.log_writer.wait_idle(5)
                rows = app.state.log_repo.all()
                reasons = check(case, status, res)
                results.append(
                    {
                        "id": case["id"],
                        "lang": case["lang"],
                        "expect": case["expect"],
                        "ok": not reasons,
                        "error": status in (429, 503, 504),
                        "reasons": reasons,
                        "ignored": res.get("ignored"),
                        "category": rows[-1].category if rows and status == 200 else None,
                        "question": case["question"],
                        "answer": " ".join(res.get("phrases", [])),
                        "latency_ms": latency_ms,
                    }
                )
        chat = app.state.chat
    scored = [r for r in results if not r["error"]]
    should_ignore = [r for r in scored if r["expect"] == "ignore"]
    should_answer = [r for r in scored if r["expect"] == "answer"]
    ignored_ok = sum(bool(r["ignored"]) for r in should_ignore)
    return {
        "provider": chat.name if chat.model is not None else "off",
        "total": len(results),
        "errors": sum(r["error"] for r in results),
        "false_ignores": sum(bool(r["ignored"]) for r in should_answer),
        "answered_ok": sum(r["ok"] for r in should_answer),
        "should_answer": len(should_answer),
        "ignored_ok": ignored_ok,
        "should_ignore": len(should_ignore),
        "ignore_recall": round(ignored_ok / len(should_ignore), 3) if should_ignore else 1.0,
        "tag_spoken": sum("tag spoken" in r["reasons"] for r in scored),
        "results": results,
    }


def failed(report: dict, min_recall: float, max_false_ignores: int) -> bool:
    return (
        report["false_ignores"] > max_false_ignores
        or report["ignore_recall"] < min_recall
        or report["tag_spoken"] > 0
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m eval.run_addressed")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--sleep", type=float, default=0.0, help="пауза между запросами, с")
    parser.add_argument(
        "--min-recall", type=float, default=0.8, help="доля чужих реплик без ответа"
    )
    parser.add_argument(
        "--max-false-ignores", type=int, default=0, help="реплик к роботу без ответа"
    )
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args(argv)

    report = run(args.cases, sleep_s=args.sleep)
    print(f"provider: {report['provider']}")
    for r in report["results"]:
        mark = "ERR " if r["error"] else ("ok  " if r["ok"] else "FAIL")
        said = "—" if r["ignored"] else repr(r["answer"])
        print(
            f"{mark} {r['id']:<13} {r['expect']:<6} {r['latency_ms']:>5}ms  {r['question']!r} → {said}"
        )
        if r["reasons"]:
            print(f"     -> {', '.join(r['reasons'])}")
    print(
        f"false ignores {report['false_ignores']}/{report['should_answer']}, "
        f"ignored {report['ignored_ok']}/{report['should_ignore']} "
        f"(recall {report['ignore_recall']}), tag spoken {report['tag_spoken']}, "
        f"provider errors {report['errors']}"
    )
    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if failed(report, args.min_recall, args.max_false_ignores) else 0


if __name__ == "__main__":
    sys.exit(main())
