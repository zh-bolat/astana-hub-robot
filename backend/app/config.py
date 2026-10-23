"""Настройки сервиса. Всё — из переменных окружения (или backend/.env), секретов в коде нет."""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env", env_file_encoding="utf-8", extra="ignore"
    )

    # Авторизация робота
    robot_token: SecretStr = Field(default=SecretStr(""))
    rate_limit_per_min: int = 30  # /ask и /look
    # Отдельный бакет для /places и /quick-questions: их опрос не съедает лимит вопросов
    kb_rate_limit_per_min: int = 60
    # /docs, /redoc, /openapi.json: в проде выключены (схема — backend/openapi.json)
    expose_docs: bool = False

    # LLM: auto = gemini (GEMINI_API_KEY) → openrouter (OPENROUTER_API_KEY) → fake
    llm_provider: Literal["auto", "gemini", "openrouter", "deepseek", "fake"] = "auto"
    # Vision (/look): auto — так же; у DeepSeek vision нет
    vision_provider: Literal["auto", "gemini", "openrouter", "fake"] = "auto"
    gemini_api_key: SecretStr | None = None
    gemini_model: str = "gemini-3.1-flash-lite"
    # Резервные модели через запятую: при 503/429/таймауте/обрыве — повтор основной (если есть время), затем они
    gemini_fallback_models: str = "gemini-3.5-flash"
    # Если основная модель молчит дольше — параллельно запускается резервная (хедж), побеждает первый ответ
    gemini_hedge_after_s: float = 3.0
    gemini_vision_model: str | None = None  # по умолчанию = gemini_model
    # Gemini 3.x: minimal | low | medium | high; пусто — дефолт модели (или thinking_budget для 2.5)
    gemini_thinking_level: str | None = "minimal"
    gemini_thinking_budget: int | None = None  # только для Gemini 2.5: 0 — выключить
    # Потолок длины ответа модели (JSON целиком). Ответ ≤ 4 фраз / 600 символов + обёртка JSON.
    # Если включаете «размышления» (THINKING_LEVEL выше minimal / THINKING_BUDGET) — увеличьте.
    llm_max_output_tokens: int = 320
    deepseek_api_key: SecretStr | None = None
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"

    # open — база, а если в ней нет ответа — модель отвечает из общих знаний (category=general);
    # strict — только база + small talk, иначе отказ «обратитесь на ресепшен»
    answer_mode: Literal["open", "strict"] = "open"

    # Агентский чат /v1/chat (ADR 0001): OpenAI Agents SDK + LiteLLM. Модели — строки LiteLLM
    # (anthropic/…, gemini/…, openrouter/…). Модель без ключа своего провайдера пропускается.
    # false — /v1/chat отвечает запасным путём (логика /v1/ask) в том же формате SSE.
    agent_enabled: bool = True
    agent_model: str = "anthropic/claude-haiku-5-5"
    # Резерв через запятую: пробуются по порядку, если модель упала до первого события роботу
    agent_fallback_models: str = "gemini/gemini-3.1-flash-lite"
    agent_max_tool_calls: int = 3
    # Весь ход (без look); с look — плюс agent_look_extra_s (контракт: ≤ 20 с / ≤ 30 с)
    agent_turn_timeout_s: float = 20.0
    agent_look_extra_s: float = 10.0
    # Нет ни фразы, ни действия за это время → ход отдаётся запасному пути (робот ждёт phrase 8 с)
    agent_first_phrase_timeout_s: float = 5.0
    # Сколько ждать кадр от робота после action look (контракт: 8 с)
    agent_look_frame_timeout_s: float = 8.0
    # Память: сколько последних сообщений (посетитель + ассистент) сессии видит агент
    agent_history_messages: int = 10
    agent_max_output_tokens: int = 400
    # auto — minimal для gemini/*, иначе дефолт модели; или none|minimal|low|medium|high
    agent_reasoning_effort: str = "auto"
    anthropic_api_key: SecretStr | None = None
    # Трассировка Agents SDK выключена всегда (тексты посетителей не уходят в OpenAI); false игнорируется
    openai_agents_disable_tracing: bool = True

    # Таймауты вызова LLM на стороне сервера (робот ждёт 8 / 12 с)
    ask_timeout_s: float = 6.5
    look_timeout_s: float = 11.0
    # SSE /ask: до первого куска — ask_timeout_s (с повтором/fallback), весь стрим — не дольше этого
    ask_stream_total_s: float = 12.0

    # База знаний живёт в Postgres (таблицы facts/places/quick_questions/events). Источники:
    # Google-таблица (KNOWLEDGE_SHEET_ID) → синк в БД; YAML в knowledge_dir — сид для разработки.
    knowledge_dir: Path = BACKEND_DIR / "knowledge"
    # Как часто сервер сверяет kb_sync.version и перечитывает базу из БД в память, сек
    knowledge_reload_interval_s: float = 5.0
    # Google-таблица (шаг 3.3): если задан ID — фоновый синк таблица → БД (вкладки доступны по ссылке)
    knowledge_sheet_id: str | None = None
    knowledge_sheet_facts: str = "Факты"
    knowledge_sheet_places: str = "Места"
    knowledge_sheet_buttons: str = "Кнопки"
    knowledge_sync_interval_s: float = 300.0
    # Общий таймаут загрузки всех вкладок (параллельно); не успели — база в БД не меняется
    knowledge_sync_timeout_s: float = 5.0
    # Снимок последней загруженной из БД базы: старт, если Postgres недоступен
    knowledge_snapshot_path: Path = BACKEND_DIR / "data" / "knowledge_snapshot.json"
    # БД без единого факта и таблица не задана → при старте засеять из YAML (разработка)
    kb_seed_yaml_if_empty: bool = True

    # PostgreSQL 16 + pgvector — единственная рабочая БД (docs/db-schema.md).
    # Локально: infra/docker-compose.dev.yml (порт 127.0.0.1:5432), пароль — в backend/.env.
    database_url: str = "postgresql+psycopg://hub@127.0.0.1:5432/hub"
    # Миграции Alembic при старте (до приёма запросов); ошибка миграции — сервер не стартует
    db_auto_migrate: bool = True
    # Таймауты БД, сек: подключение и запрос (statement_timeout/lock_timeout)
    db_timeout_s: float = 2.0
    # Хранение лога вопросов, сессий и сообщений, дней (0 — хранить вечно). Очистка — раз в сутки фоном.
    question_log_retention_days: int = 180
    # Очередь фоновой записи: при зависшей БД сверх лимита записи отбрасываются (с предупреждением)
    question_log_queue_max: int = 1000

    # /look
    max_image_bytes: int = 1024 * 1024

    # Голос через сервер (/v1/stt, /v1/tts) — провайдеры раздельно.
    # STT auto: elevenlabs (ELEVENLABS_API_KEY) → gemini (GEMINI_API_KEY) → openrouter (OPENROUTER_API_KEY) → fake
    # TTS auto: elevenlabs (ELEVENLABS_API_KEY) → yandex (YANDEX_API_KEY) → openrouter (OPENROUTER_API_KEY) → fake
    stt_provider: Literal["auto", "gemini", "openrouter", "elevenlabs", "yandex", "fake"] = "auto"
    tts_provider: Literal["auto", "elevenlabs", "openrouter", "yandex", "fake"] = "auto"
    # Запасной TTS при ошибке основного (кончились кредиты ElevenLabs и т. п.); пусто — без запасного
    tts_fallback_provider: Literal["elevenlabs", "openrouter", "yandex"] | None = None
    # Gemini STT: модель + резервные (через запятую)
    gemini_stt_model: str = "gemini-3.1-flash-lite"
    gemini_stt_fallback_models: str = "gemini-3.5-flash"
    # OpenRouter: текст, vision и STT — Gemini через OpenAI-совместимый API, TTS — модели ElevenLabs
    openrouter_api_key: SecretStr | None = None
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Текст (/ask) и vision (/look); резервные модели через запятую — как у Gemini
    openrouter_model: str = "google/gemini-3.1-flash-lite"
    openrouter_fallback_models: str = "google/gemini-3.5-flash"
    openrouter_vision_model: str | None = None  # по умолчанию = openrouter_model
    openrouter_stt_model: str = "google/gemini-3.1-flash-lite"
    openrouter_stt_fallback_models: str = "google/gemini-3.5-flash"
    openrouter_tts_model_kk: str = "elevenlabs/eleven-v3"  # казахский умеет только v3
    openrouter_tts_model_default: str = "elevenlabs/eleven-flash-v2.5"  # ru/en
    # voice_id из библиотеки ElevenLabs; по умолчанию «George», как у прямого ElevenLabs
    openrouter_voice_ru: str = "JBFqnCBsd6RMkjVDRZzb"
    openrouter_voice_kk: str = "JBFqnCBsd6RMkjVDRZzb"
    openrouter_voice_en: str = "JBFqnCBsd6RMkjVDRZzb"
    # ElevenLabs
    elevenlabs_api_key: SecretStr | None = None
    elevenlabs_base_url: str = "https://api.elevenlabs.io"
    elevenlabs_tts_model_kk: str = "eleven_v3"  # казахский умеет только v3
    elevenlabs_tts_model_default: str = "eleven_flash_v2_5"  # ru/en, минимальная задержка
    # Голос по умолчанию — премиальный «George» из стандартной библиотеки (мультиязычные модели)
    elevenlabs_voice_ru: str = "JBFqnCBsd6RMkjVDRZzb"
    elevenlabs_voice_kk: str = "JBFqnCBsd6RMkjVDRZzb"
    elevenlabs_voice_en: str = "JBFqnCBsd6RMkjVDRZzb"
    elevenlabs_output_format: str = "mp3_44100_128"
    elevenlabs_stt_model: str = "scribe_v2"
    # Live STT (WebSocket /v1/stt/stream): auto — ElevenLabs Scribe Realtime при ELEVENLABS_API_KEY,
    # иначе buffered (звук копится, по концу фразы — обычный STT_PROVIDER)
    stt_stream_provider: Literal["auto", "elevenlabs", "buffered"] = "auto"
    elevenlabs_realtime_url: str = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"
    elevenlabs_realtime_model: str = "scribe_v2_realtime"
    # Пауза, после которой VAD ElevenLabs считает фразу законченной
    elevenlabs_realtime_silence_s: float = 0.8
    # Посетитель так и не заговорил — сессия закрывается с пустым текстом
    stt_stream_no_speech_s: float = 8.0
    # Тишина: пиковая амплитуда ниже порога → пустой текст без вызова провайдера
    stt_silence_peak: int = 500
    yandex_api_key: SecretStr | None = None
    yandex_folder_id: str | None = (
        None  # нужен только для ключа пользователя, не сервисного аккаунта
    )
    yandex_stt_url: str = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize"
    yandex_tts_url: str = "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize"
    yandex_voice_ru: str = "jane"
    yandex_voice_kk: str = "amira"
    yandex_voice_en: str = "john"
    stt_timeout_s: float = 5.0  # робот ждёт /stt 6 с
    tts_timeout_s: float = 4.0  # робот ждёт /tts 5 с
    max_audio_bytes: int = 600 * 1024
    max_audio_seconds: float = 15.0
    # Отдельный бакет для /stt и /tts: на один вопрос робот делает 1 stt + N tts
    speech_rate_limit_per_min: int = 120
    tts_cache_memory_mb: int = 32
    tts_cache_dir: Path | None = BACKEND_DIR / "data" / "tts_cache"  # пусто/None — без диска
    tts_cache_disk_mb: int = 100

    log_level: str = "INFO"
    # Сети/адреса доверенных прокси (через запятую): X-Forwarded-For принимается только от них.
    # По умолчанию — сети docker (caddy) и localhost. В образе тот же список идёт в
    # uvicorn --forwarded-allow-ips.
    trusted_proxies: str = "172.16.0.0/12,127.0.0.1"
    # Sentry: подключается только если задан DSN; ПДн (тексты вопросов/ответов, IP) не отправляются
    sentry_dsn: SecretStr | None = None
    sentry_environment: str = "production"

    @field_validator(
        "tts_cache_dir",
        "yandex_folder_id",
        "gemini_thinking_level",
        "gemini_thinking_budget",
        "gemini_vision_model",
        "openrouter_vision_model",
        "knowledge_sheet_id",
        "elevenlabs_api_key",
        "yandex_api_key",
        "gemini_api_key",
        "sentry_dsn",
        "openrouter_api_key",
        "anthropic_api_key",
        "tts_fallback_provider",
        mode="before",
    )
    @classmethod
    def _empty_to_none(cls, v):
        return None if isinstance(v, str) and not v.strip() else v

    def gemini_fallback_list(self) -> list[str]:
        return _split(self.gemini_fallback_models)

    def gemini_stt_fallback_list(self) -> list[str]:
        return _split(self.gemini_stt_fallback_models)

    def agent_model_list(self) -> list[str]:
        """Основная модель агента + резервные, без повторов."""
        return list(dict.fromkeys([self.agent_model.strip(), *_split(self.agent_fallback_models)]))

    def openrouter_fallback_list(self) -> list[str]:
        return _split(self.openrouter_fallback_models)

    def openrouter_stt_fallback_list(self) -> list[str]:
        return _split(self.openrouter_stt_fallback_models)

    def resolved_text_provider(self) -> str:
        if self.llm_provider != "auto":
            return self.llm_provider
        return self._auto_llm()

    def _auto_llm(self) -> str:
        if self.gemini_api_key:
            return "gemini"
        return "openrouter" if self.openrouter_api_key else "fake"

    def resolved_stt_provider(self) -> str:
        if self.stt_provider != "auto":
            return self.stt_provider
        if self.elevenlabs_api_key:
            return "elevenlabs"
        if self.gemini_api_key:
            return "gemini"
        return "openrouter" if self.openrouter_api_key else "fake"

    def resolved_tts_provider(self) -> str:
        if self.tts_provider != "auto":
            return self.tts_provider
        if self.elevenlabs_api_key:
            return "elevenlabs"
        if self.yandex_api_key:
            return "yandex"
        return "openrouter" if self.openrouter_api_key else "fake"

    def resolved_stt_stream_provider(self) -> str:
        if self.stt_stream_provider != "auto":
            return self.stt_stream_provider
        return "elevenlabs" if self.elevenlabs_api_key else "buffered"

    def resolved_vision_provider(self) -> str:
        if self.vision_provider != "auto":
            return self.vision_provider
        return self._auto_llm()


def _split(value: str) -> list[str]:
    return [m.strip() for m in value.split(",") if m.strip()]
