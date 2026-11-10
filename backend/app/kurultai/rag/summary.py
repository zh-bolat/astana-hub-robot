"""Короткая сводка для промпта Realtime вместо полного снимка и всех справок (их находит search_kurultai).

В сводке то, о чём спрашивают чаще всего и что должно звучать без задержки поиска:
- законопроекты: всего и по статусам; комитеты и председатели; число депутатских запросов; последний план недели;
- материалы аппарата: заголовок и дата каждого (одной строкой) + полные справки ближайших событий (повестка
  заседания на неделю вперёд) — «что будет на пленарке» отвечается сразу.
Сводка ограничена SUMMARY_CHARS: префикс промпта маленький → быстрее первый ответ, дешевле, меньше упираемся
в лимит токенов OpenAI в минуту.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.db.models import KurultaiMaterialRow
from app.kurultai.materials import structure as st
from app.kurultai.quryltai import ASTANA_TZ, _int, _list, _one_line, _text, format_time, status_name

SUMMARY_CHARS = 12000
UPCOMING_DAYS = 7
BRIEFS_SHARE = (
    0.65  # доля бюджета материалов под полные справки ближайших событий; остальное — заголовки
)
TITLE_CHARS = 90


def _chair(committee: dict) -> str:
    for d in _list(committee.get("deputies")):
        pos = _text(d, "position").lower() if isinstance(d, dict) else ""
        if "председатель" in pos and "заместитель" not in pos:
            return _text(d, "name")
    return ""


def snapshot_summary(data: dict | None) -> str:
    if not isinstance(data, dict):
        return "Снимка quryltai.kz пока нет — о составе и законопроектах говори, что данных нет."
    out = [f"Данные quryltai.kz на {format_time(_int(data, 'updated_at'))}."]
    bills = [b for b in _list(data.get("bills")) if isinstance(b, dict)]
    if bills:
        by: dict[str, int] = {}
        for b in bills:
            name = status_name(_int(b, "status"))
            by[name] = by.get(name, 0) + 1
        out.append(f"Законопроектов всего {_int(data, 'bill_count') or len(bills)}; по статусам (используй эти "
                   "числа, не пересчитывай): " + "; ".join(f"{k} — {v}" for k, v in by.items()) + ".")  # fmt: skip
    committees = [c for c in _list(data.get("committees")) if isinstance(c, dict)]
    if committees:
        lines = []
        for c in committees:
            chair = _chair(c)
            members = len([d for d in _list(c.get("deputies")) if isinstance(d, dict)])
            lines.append(
                f"{_text(c, 'name')} — {members} членов"
                + (f", председатель {chair}" if chair else "")
            )
        out.append(f"Комитетов {len(committees)}: " + "; ".join(lines) + ".")
    total = _int(data, "deputy_request_count") or len(_list(data.get("deputy_requests")))
    if total:
        out.append(
            f"Депутатских запросов всего {total} (темы, номера, авторы — через search_kurultai)."
        )
    plans = [p for p in _list(data.get("weekly_plans")) if isinstance(p, dict)]
    if plans:
        out.append(f"Последний опубликованный план недели: {_one_line(_text(plans[0], 'title'))}.")
    return "\n".join(out)


def materials_summary(rows: list[KurultaiMaterialRow], now: datetime, budget: int) -> str:
    """Материалы для промпта. Сначала — ПОЛНЫЕ справки ближайших событий (сегодняшнее заседание отвечается сразу,
    без поиска): ближайшее событие первым, у одного события — повестки раньше прочего, новая версия раньше старой.
    Потом — заголовки остальных документов, сколько влезет (10.11.2026: 83 материала, список заголовков съедал
    весь бюджет, и ни одна справка в промпт не попадала — робот искал даже время начала заседания)."""
    if not rows:
        return ""
    today = now.astimezone(ASTANA_TZ).date()
    soon = [
        r
        for r in rows
        if r.event_date
        and today - timedelta(days=1) <= r.event_date <= today + timedelta(days=UPCOMING_DAYS)
        and (r.brief or "").strip()
    ]
    soon.sort(key=lambda r: (abs((r.event_date - today).days), r.kind != "agenda", -(r.id or 0)))
    intro = (
        "Материалы аппарата Курултая — главный источник о заседаниях. Ниже полные справки ближайших событий: по "
        "ним отвечай сразу, без поиска. Если версии расходятся — верь той, что выше (она новее)."
    )
    parts = [intro]
    used = len(intro)
    full: set[int] = set()
    cap = int(budget * BRIEFS_SHARE)
    for r in soon:
        part = f"=== {r.title} ===\n{(r.brief or '').strip()}"
        if used + len(part) > cap:
            continue
        parts.append(part)
        used += len(part) + 2
        full.add(r.id)
    rest = [r for r in rows if r.id not in full]
    rest.sort(
        key=lambda r: (
            r.event_date is None,
            abs((r.event_date - today).days) if r.event_date else 0,
            -(r.id or 0),
        )
    )
    lines = ["Остальные документы (их содержание — через search_kurultai):"]
    used += len(lines[0]) + 2
    shown = 0
    for r in rest:
        date = f" — {r.event_date.strftime('%d.%m.%Y')}" if r.event_date else ""
        line = f"- {_one_line(r.title)[:TITLE_CHARS]}{date} ({st.KIND_NAMES.get(r.kind, r.kind)})"
        if used + len(line) + 1 > budget - 80:
            break
        lines.append(line)
        used += len(line) + 1
        shown += 1
    if shown < len(rest):
        lines.append(f"… и ещё {len(rest) - shown} документов — тоже через search_kurultai.")
    if len(lines) > 1:
        parts.append("\n".join(lines))
    return "\n\n".join(parts)


def build_summary(data: dict | None, rows: list[KurultaiMaterialRow], now: datetime) -> str:
    snap = snapshot_summary(data)
    mats = materials_summary(rows, now, max(0, SUMMARY_CHARS - len(snap)))
    return f"{snap}\n\n{mats}".strip()
