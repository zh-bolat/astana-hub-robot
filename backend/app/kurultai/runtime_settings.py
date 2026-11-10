"""Настройки робота Курултая, которые меняются во вкладке «Робот и настройки» без переустановки и перезапуска
(docs/kurultai/robot-settings.md): `live_engine` (realtime / pipeline) и `noise_mode` (normal / noisy).

Хранятся в таблице `kurultai_runtime_settings` (миграция 0007), в памяти — кэш: один процесс api, читается при
старте и после каждой записи. Нет строки или значение не из списка — умолчание из .env (`KURULTAI_LIVE_ENGINE`,
`KURULTAI_NOISE_MODE`). БД недоступна при старте — работаем на умолчаниях, запись вернёт ошибку.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import KurultaiRuntimeSettingRow
from app.logging_setup import logger

ALLOWED: dict[str, tuple[str, ...]] = {
    "live_engine": ("realtime", "pipeline"),
    "noise_mode": ("normal", "noisy"),
}


def validate(key: str, value: object) -> str:
    """Ключ и значение из контракта; иначе ValueError с текстом для ответа 400."""
    if key not in ALLOWED:
        raise ValueError(f"unknown setting: {key}")
    if not isinstance(value, str) or value not in ALLOWED[key]:
        raise ValueError(f"{key} must be one of: {', '.join(ALLOWED[key])}")
    return value


class RuntimeSettings:
    def __init__(self, engine: Engine | None, settings: Settings) -> None:
        self._engine = engine
        self._defaults = {
            "live_engine": settings.kurultai_live_engine,
            "noise_mode": settings.kurultai_noise_mode,
        }
        self._values: dict[str, str] = {}
        self.updated_at: dict[str, datetime] = {}
        self.load()

    def load(self) -> None:
        """Перечитать из БД (при старте и после записи); ошибка — прежние значения."""
        if self._engine is None:
            return
        try:
            with Session(self._engine) as s:
                rows = s.execute(select(KurultaiRuntimeSettingRow)).scalars().all()
        except Exception as exc:
            logger.warning("runtime_settings_load_failed", extra={"error": type(exc).__name__})
            return
        values: dict[str, str] = {}
        updated: dict[str, datetime] = {}
        for row in rows:
            try:
                values[row.key] = validate(row.key, row.value)
            except ValueError:
                logger.warning("runtime_settings_invalid_row", extra={"key": row.key})
                continue
            updated[row.key] = row.updated_at
        self._values, self.updated_at = values, updated

    def get(self, key: str) -> str:
        if key not in ALLOWED:
            raise KeyError(key)
        return self._values.get(key) or self._defaults[key]

    @property
    def live_engine(self) -> str:
        return self.get("live_engine")

    @property
    def noise_mode(self) -> str:
        return self.get("noise_mode")

    def as_dict(self) -> dict[str, str]:
        return {key: self.get(key) for key in ALLOWED}

    def set(self, key: str, value: object, updated_by: str | None = None) -> tuple[str, str]:
        """Записать (upsert) и обновить кэш; возвращает (старое, новое). Невалидно — ValueError."""
        new = validate(key, value)
        old = self.get(key)
        if self._engine is not None:
            stmt = insert(KurultaiRuntimeSettingRow).values(
                key=key, value=new, updated_by=updated_by
            )
            stmt = stmt.on_conflict_do_update(
                index_elements=[KurultaiRuntimeSettingRow.key],
                set_={
                    "value": new,
                    "updated_by": updated_by,
                    "updated_at": datetime.now().astimezone(),
                },
            )
            with Session(self._engine) as s, s.begin():
                s.execute(stmt)
        self._values[key] = new
        self.updated_at[key] = datetime.now().astimezone()
        self.load()  # updated_at из БД; не вышло — кэш уже обновлён
        return old, new
