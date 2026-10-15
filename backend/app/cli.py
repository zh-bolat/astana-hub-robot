"""Служебные команды.

uv run python -m app.cli export-csv --out questions.csv [--unanswered]
uv run python -m app.cli openapi --out openapi.json
uv run python -m app.cli check-kb
"""

from __future__ import annotations

import argparse
import json
import sys

from app.config import Settings


def _export_csv(args: argparse.Namespace) -> int:
    from app.db import QuestionLogRepo, make_engine

    repo = QuestionLogRepo(make_engine(Settings().database_url))
    if args.out == "-":
        n = repo.export_csv(sys.stdout, only_unanswered=args.unanswered)
    else:
        # utf-8-sig — чтобы Excel корректно открыл кириллицу
        with open(args.out, "w", encoding="utf-8-sig", newline="") as f:
            n = repo.export_csv(f, only_unanswered=args.unanswered)
    print(f"exported {n} rows", file=sys.stderr)
    return 0


def _openapi(args: argparse.Namespace) -> int:
    from app.llm.fake import FakeProvider
    from app.main import create_app

    settings = Settings(
        robot_token="openapi-dump", database_url="sqlite:///:memory:", log_level="WARNING"
    )
    app = create_app(settings, text_llm=FakeProvider(), vision_llm=FakeProvider())
    text = json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n"
    if args.out == "-":
        sys.stdout.write(text)
    else:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


def _check_kb(_: argparse.Namespace) -> int:
    from app.knowledge.source import YamlKnowledgeSource

    kb = YamlKnowledgeSource(Settings().knowledge_dir).load()
    print(
        f"ok: version={kb.version} facts={len(kb.facts)} places={len(kb.places)} "
        f"quick_questions={len(kb.quick_questions)}"
    )
    return 0


def _sync_kb(_: argparse.Namespace) -> int:
    from app.knowledge.sheets import SheetKnowledgeSource
    from app.knowledge.source import YamlKnowledgeSource

    settings = Settings()
    if not settings.knowledge_sheet_id:
        print("KNOWLEDGE_SHEET_ID не задан — база берётся из YAML", file=sys.stderr)
        return 2
    sheet = SheetKnowledgeSource(
        sheet_id=settings.knowledge_sheet_id,
        tabs=(
            settings.knowledge_sheet_facts,
            settings.knowledge_sheet_places,
            settings.knowledge_sheet_buttons,
        ),
        snapshot_path=settings.knowledge_snapshot_path,
        fallback=YamlKnowledgeSource(settings.knowledge_dir),
    )
    if not sheet.sync_blocking():
        print(f"sync failed: {sheet.last_error}", file=sys.stderr)
        return 1
    kb = sheet.load()
    print(
        f"ok: version={kb.version} facts={len(kb.facts)} places={len(kb.places)} "
        f"buttons={len(kb.quick_questions)}; snapshot: {settings.knowledge_snapshot_path}"
    )
    print("Работающий сервер подхватит изменения при следующей фоновой синхронизации.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("export-csv", help="выгрузить лог вопросов в CSV")
    p.add_argument("--out", default="-", help="файл или '-' для stdout")
    p.add_argument("--unanswered", action="store_true", help="только вопросы без ответа")
    p.set_defaults(func=_export_csv)

    p = sub.add_parser("openapi", help="сохранить OpenAPI-схему")
    p.add_argument("--out", default="-")
    p.set_defaults(func=_openapi)

    p = sub.add_parser("check-kb", help="проверить, что база знаний читается")
    p.set_defaults(func=_check_kb)

    p = sub.add_parser("sync-kb", help="загрузить базу из Google-таблицы и сохранить снимок")
    p.set_defaults(func=_sync_kb)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
