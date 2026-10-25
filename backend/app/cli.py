"""Служебные команды.

uv run python -m app.cli db upgrade            # миграции Alembic до head
uv run python -m app.cli db current            # текущая ревизия схемы
uv run python -m app.cli db import-sqlite PATH # перенести лог вопросов из старой SQLite-БД
uv run python -m app.cli kb seed-yaml          # YAML и documents/*.md (backend/knowledge) → Postgres
uv run python -m app.cli kb sync               # Google-таблица → Postgres
uv run python -m app.cli kb index [--force]    # фрагменты для поиска (kb_chunks) + эмбеддинги
uv run python -m app.cli kb status             # что в базе, фрагменты поиска, последние синхронизации
uv run python -m app.cli export-csv --out questions.csv [--unanswered]
uv run python -m app.cli openapi --out openapi.json
uv run python -m app.cli check-kb              # YAML читается без ошибок
"""

from __future__ import annotations

import argparse
import json
import sys

from app.config import Settings


def _export_csv(args: argparse.Namespace) -> int:
    from app.db import QuestionLogRepo

    repo = QuestionLogRepo(_engine())
    if args.out == "-":
        n = repo.export_csv(sys.stdout, only_unanswered=args.unanswered)
    else:
        # utf-8-sig — чтобы Excel корректно открыл кириллицу
        with open(args.out, "w", encoding="utf-8-sig", newline="") as f:
            n = repo.export_csv(f, only_unanswered=args.unanswered)
    print(f"exported {n} rows", file=sys.stderr)
    return 0


def _openapi(args: argparse.Namespace) -> int:
    from app.knowledge.source import YamlKnowledgeSource
    from app.llm.fake import FakeProvider
    from app.main import create_app

    settings = Settings(
        robot_token="openapi-dump",
        database_url="postgresql+psycopg://openapi@127.0.0.1:1/none",  # не подключается
        db_auto_migrate=False,
        log_level="WARNING",
    )
    app = create_app(
        settings,
        text_llm=FakeProvider(),
        vision_llm=FakeProvider(),
        knowledge_source=YamlKnowledgeSource(settings.knowledge_dir),
    )
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


def _engine():
    from app.db import make_engine

    settings = Settings()
    # CLI: запись в БД может длиться дольше 2 с (большая таблица, ожидание блокировки синка)
    return make_engine(settings.database_url, timeout_s=max(settings.db_timeout_s, 30))


def _print_sync(result) -> None:
    print(f"{result.status}: version={result.version}")
    for table, c in result.counts.items():
        print(
            f"  {table}: +{c['inserted']} ~{c['updated']} -{c['deactivated']} "
            "(новые / изменённые / выключенные)"
        )
    for reason in getattr(result, "skipped", []):
        print(f"  пропущен документ {reason}", file=sys.stderr)
    print(
        "Работающий сервер подхватит изменения за KNOWLEDGE_RELOAD_INTERVAL_S (≈5 с), "
        "фрагменты для поиска пересчитает сам."
    )


def _db_upgrade(_: argparse.Namespace) -> int:
    from app.db.migrate import current_revision, upgrade_head

    url = Settings().database_url
    upgrade_head(url)
    print(f"ok: schema at {current_revision(url)}")
    return 0


def _db_current(_: argparse.Namespace) -> int:
    from app.db.migrate import current_revision, head_revision

    current, head = current_revision(Settings().database_url), head_revision()
    print(f"current={current} head={head}")
    return 0 if current == head else 1


def _db_import_sqlite(args: argparse.Namespace) -> int:
    from app.db.sqlite_import import import_question_log

    n, skipped = import_question_log(args.path, _engine())
    print(f"imported {n} rows, skipped {skipped} (already present)")
    return 0


def _kb_seed_yaml(_: argparse.Namespace) -> int:
    from app.knowledge.pg import KnowledgeRepo
    from app.knowledge.source import YamlKnowledgeSource
    from app.knowledge.sync import seed_yaml

    settings = Settings()
    result = seed_yaml(KnowledgeRepo(_engine()), YamlKnowledgeSource(settings.knowledge_dir))
    _print_sync(result)
    return 0


def _kb_sync(_: argparse.Namespace) -> int:
    from datetime import UTC, datetime

    from app.knowledge.pg import KnowledgeRepo
    from app.knowledge.sheets import SheetError
    from app.main import build_sheet_fetcher

    settings = Settings()
    fetcher = build_sheet_fetcher(settings)
    if fetcher is None:
        print("KNOWLEDGE_SHEET_ID не задан (для разработки: kb seed-yaml)", file=sys.stderr)
        return 2
    repo = KnowledgeRepo(_engine())
    started = datetime.now(UTC)
    try:
        kb = fetcher.fetch_blocking()
    except SheetError as exc:
        repo.record_error("sheet", str(exc), started)
        print(f"sync failed: {exc}", file=sys.stderr)
        return 1
    _print_sync(repo.apply(kb, "sheet", started_at=started))
    return 0


def _kb_index(args: argparse.Namespace) -> int:
    from app.knowledge.index import KbIndexer
    from app.llm.embeddings import build_embedder

    settings = Settings()
    embedder = build_embedder(settings)
    if embedder is None or settings.retrieval_mode == "off":
        print(
            "поиск выключен: нужен OPENROUTER_API_KEY (или EMBEDDING_PROVIDER) и RETRIEVAL_MODE=hybrid",
            file=sys.stderr,
        )
        return 2
    report = KbIndexer(_engine(), embedder, settings.embedding_batch_size).index(force=args.force)
    if report.skipped:
        print("индексация уже идёт в другом процессе, повторите позже", file=sys.stderr)
        return 1
    print(
        f"{'ok' if report.ok else 'error'}: model={embedder.model} embedded={report.embedded} "
        f"unchanged={report.unchanged} removed={report.removed} restored={report.restored} "
        f"failed={report.failed}"
    )
    print("  фрагменты: " + _chunks_line(report.by_type))
    if report.error:
        print(f"  {report.error}", file=sys.stderr)
    return 0 if report.ok else 1


def _chunks_line(by_type: dict[str, int]) -> str:
    return " ".join(f"{t}={by_type.get(t, 0)}" for t in ("fact", "place", "document"))


def _kb_status(_: argparse.Namespace) -> int:
    from sqlalchemy import func, select

    from app.db.models import DocumentRow, KbChunkRow
    from app.knowledge.index import chunk_counts
    from app.knowledge.pg import KnowledgeRepo

    engine = _engine()
    repo = KnowledgeRepo(engine)
    kb = repo.load_active()
    with engine.connect() as conn:
        docs = conn.execute(
            select(func.count()).select_from(DocumentRow).where(DocumentRow.is_active)
        ).scalar_one()
        chunks = chunk_counts(conn)
        models = conn.execute(
            select(KbChunkRow.embedding_model, func.count())
            .where(KbChunkRow.is_active)
            .group_by(KbChunkRow.embedding_model)
        ).all()
    print(
        f"active: version={kb.version} facts={len(kb.facts)} places={len(kb.places)} "
        f"quick_questions={len(kb.quick_questions)} events={len(kb.events)} documents={docs}"
    )
    model_list = ", ".join(f"{m or 'без эмбеддинга'}: {n}" for m, n in models) or "нет"
    print(f"chunks (поиск): {_chunks_line(chunks)}; модель — {model_list}")
    for row in repo.sync_log(10):
        print(
            f"  #{row.id} {row.started_at:%Y-%m-%d %H:%M:%S} {row.source:5} {row.status:7} "
            f"{row.version or ''} {row.error or ''}".rstrip()
        )
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

    db = sub.add_parser("db", help="схема БД (Alembic)").add_subparsers(
        dest="db_cmd", required=True
    )
    db.add_parser("upgrade", help="применить миграции до head").set_defaults(func=_db_upgrade)
    db.add_parser("current", help="текущая ревизия (код 1 — не head)").set_defaults(
        func=_db_current
    )
    p = db.add_parser("import-sqlite", help="перенести question_log из SQLite-файла")
    p.add_argument("path")
    p.set_defaults(func=_db_import_sqlite)

    kb = sub.add_parser("kb", help="база знаний в Postgres").add_subparsers(
        dest="kb_cmd", required=True
    )
    kb.add_parser(
        "seed-yaml", help="YAML и documents/*.md (KNOWLEDGE_DIR) → Postgres"
    ).set_defaults(func=_kb_seed_yaml)
    kb.add_parser("sync", help="Google-таблица → Postgres").set_defaults(func=_kb_sync)
    p = kb.add_parser("index", help="фрагменты для поиска и эмбеддинги (только изменённые)")
    p.add_argument("--force", action="store_true", help="пересчитать эмбеддинги всех фрагментов")
    p.set_defaults(func=_kb_index)
    kb.add_parser("status", help="активная база и журнал синхронизаций").set_defaults(
        func=_kb_status
    )

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
