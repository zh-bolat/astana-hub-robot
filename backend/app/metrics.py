"""Тайминги этапов → таблица latency_events (docs/api-contract.md, «Мониторинг задержек»).

Единый способ замерять: `LatencyRecorder.record(kind, {stage: ms, ...}, ...)`.
- Запись фоном через очередь `BackgroundLogWriter` — ответ посетителю БД не ждёт; ошибки не всплывают.
- Пишутся только числа и метки (этап, язык, провайдер/модель, request_id, session_id, флаги).
  Тексты реплик, аудио и кадры сюда не попадают — `extra` принимает только числа, bool и короткие строки.

Этапы сервера (kind → stage):
- stt:   stt_ms (POST /v1/stt, вызов провайдера), stt_live_final_ms (live: от конца речи до финала);
- chat:  llm_first_token_ms, first_phrase_ms, llm_total_ms (от начала запроса); extra: action, fallback, …;
- ask:   llm_first_token_ms, first_phrase_ms (SSE), llm_total_ms;
- look:  llm_total_ms;
- tts:   tts_first_byte_ms, tts_total_ms; extra: cache, chars, stream;
- robot: поля POST /v1/metrics/turn (*_ms) + строка stage=turn без ms (флаги хода).
"""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping

from app.logging_setup import get_request_id, logger

_MAX_MS = 24 * 3600 * 1000
_MAX_EXTRA_STR = 64


def elapsed_ms(started: float) -> int:
    """Миллисекунды от отметки time.perf_counter()."""
    return int((time.perf_counter() - started) * 1000)


def _clean_extra(extra: Mapping[str, object] | None) -> dict:
    """Только скаляры: числа, bool, None и короткие строки (метки), списки коротких строк."""
    out: dict = {}
    for key, value in (extra or {}).items():
        if value is None or isinstance(value, bool | int | float):
            out[key] = value
        elif isinstance(value, str):
            out[key] = value[:_MAX_EXTRA_STR]
        elif isinstance(value, list | tuple):
            out[key] = [str(v)[:_MAX_EXTRA_STR] for v in value[:10]]
    return out


def _cut(value: str | None, n: int) -> str | None:
    return value[:n] if value else None


class LatencyRecorder:
    """submit — функция постановки задачи в фоновую очередь (BackgroundLogWriter.submit_job);
    sink — функция записи строк (LatencyRepo.add_many). Любая None → запись выключена."""

    def __init__(
        self,
        submit: Callable[..., bool] | None = None,
        sink: Callable[[list[dict]], None] | None = None,
    ) -> None:
        self._submit = submit
        self._sink = sink

    @property
    def enabled(self) -> bool:
        return self._submit is not None and self._sink is not None

    def record(
        self,
        kind: str,
        stages: Mapping[str, int | None],
        *,
        lang: str | None = None,
        provider: str | None = None,
        request_id: str | None = None,
        session_id: str | None = None,
        ok: bool = True,
        extra: Mapping[str, object] | None = None,
        keep_null: tuple[str, ...] = (),
    ) -> None:
        """Строка на каждый этап с ms (None пропускается, кроме этапов из keep_null)."""
        if not self.enabled:
            return
        try:
            # None — request_id текущего запроса; "" — не известен (ход робота без request_id)
            rid = _cut(get_request_id() if request_id is None else request_id, 64)
            clean = _clean_extra(extra)
            rows = [
                {
                    "kind": kind,
                    "stage": stage,
                    "ms": None if ms is None else max(0, min(int(ms), _MAX_MS)),
                    "lang": _cut(lang, 8),
                    "provider": _cut(provider, 128),
                    "request_id": rid,
                    "session_id": _cut(session_id, 64),
                    "ok": ok,
                    "extra": clean,
                }
                for stage, ms in stages.items()
                if ms is not None or stage in keep_null
            ]
            if rows:
                sink = self._sink
                assert self._submit is not None and sink is not None
                self._submit(lambda: sink(rows), name="latency")
        except Exception as exc:  # метрики никогда не ломают ответ
            logger.warning("latency_record_failed", extra={"error": type(exc).__name__})


NULL_RECORDER = LatencyRecorder()
