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
    # Статистика задержек (GET /v1/admin/metrics, /v1/admin/metrics/page): отдельный токен админа.
    # Не задан — оба адреса отвечают 404.
    admin_token: SecretStr | None = None
    admin_rate_limit_per_min: int = 30

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
    # Агентский чат /v1/chat (docs/decisions/0001-agent-chat-stack.md): OpenAI Agents SDK + Claude Haiku
    # через OpenRouter. auto — включён при OPENROUTER_API_KEY; off — /v1/chat отвечает как /v1/ask.
    chat_provider: Literal["auto", "openrouter", "off"] = "auto"
    agent_model: str = "anthropic/claude-haiku-5.5"
    agent_max_tokens: int = 400
    # До первой фразы (включая вызовы тулов), сек; робот ждёт ответ 8 с
    agent_timeout_s: float = 6.5
    # visitor_arrived: до приветствия модели, сек; дольше — готовое приветствие (робот ждёт 2 с)
    agent_greeting_timeout_s: float = 1.8
    # Весь ответ агента, сек
    agent_total_s: float = 15.0
    # Запросов к модели на одну реплику (ответ + вызовы тулов)
    agent_max_turns: int = 4
    # Сколько последних сообщений разговора агент видит (память — таблица messages)
    agent_history_messages: int = 12
    # Устаревшее (агент на LiteLLM): AGENT_ENABLED=false при CHAT_PROVIDER=auto = off; иначе игнорируется.
    # AGENT_FALLBACK_MODELS больше не читается (резерв агента — ответ как /ask).
    agent_enabled: bool | None = None
    # ElevenLabs
    elevenlabs_api_key: SecretStr | None = None
    elevenlabs_base_url: str = "https://api.elevenlabs.io"
    # казахский умеет только v3 (другая модель для kk заменяется на eleven_v3 — flash отвечает unsupported_language)
    elevenlabs_tts_model_kk: str = "eleven_v3"
    elevenlabs_tts_model_default: str = "eleven_flash_v2_5"  # ru/en, минимальная задержка
    # /v1/tts: своя модель для ru / en (пусто — ELEVENLABS_TTS_MODEL_DEFAULT)
    elevenlabs_tts_model_ru: str | None = None
    elevenlabs_tts_model_en: str | None = None
    # Голоса (и /v1/tts, и live), выбраны на прослушивании 2026-10: RU Alina, EN Laura (eleven_flash_v2_5),
    # KZ Kari (eleven_v3)
    elevenlabs_voice_ru: str = "dVRDrbP5ULGXB94se4KZ"
    elevenlabs_voice_kk: str = "Jbte7ht1CqapnZvc4KpK"
    elevenlabs_voice_en: str = "FGY2WhTYpPnrIDTdsKH5"
    # voice_settings flash / multilingual (ru и en, общие): энергичная подача — stability 0.35, style 0.45,
    # speed 1.1 (0.7–1.2). Ровнее и чуть быстрее синтез — stability выше, style 0.
    elevenlabs_stability: float = Field(0.35, ge=0.0, le=1.0)
    elevenlabs_similarity: float = Field(0.8, ge=0.0, le=1.0)
    elevenlabs_style: float = Field(0.45, ge=0.0, le=1.0)
    elevenlabs_speaker_boost: bool = True
    elevenlabs_speed: float = Field(1.1, ge=0.7, le=1.2)
    # Eleven v3 (kk) берёт только stability, округляется до 0.0 Creative / 0.5 Natural / 1.0 Robust
    elevenlabs_v3_stability: float = Field(0.5, ge=0.0, le=1.0)
    # Звуковой тег v3 перед каждой фразой, которая уходит в v3 (только в запрос синтеза: не в субтитры,
    # историю и журнал); пусто — без тега
    elevenlabs_v3_tag: str = "[cheerfully]"
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
    # Живой голосовой режим WS /v1/live (Pipecat, app/live). Пусто — включён, если есть ELEVENLABS_API_KEY
    # и агент (OPENROUTER_API_KEY); выключен — /v1/live сразу закрывается с error, робот в запасном режиме.
    live_enabled: bool | None = None
    live_max_sessions: int = Field(2, ge=1)  # одновременных live-сессий на токен
    live_idle_timeout_s: float = 120.0  # ни речи, ни ответа, ни команд робота — сессия закрывается
    # VAD (Silero): подтверждение начала/конца речи, порог уверенности и громкости
    live_vad_start_s: float = 0.2
    live_vad_stop_s: float = 0.2
    live_vad_confidence: float = 0.7
    live_vad_min_volume: float = 0.6
    # Конец реплики: smart-turn v3 (локальная ONNX-модель) по паузе VAD; модель сказала «не договорил» —
    # ждём тишину до live_turn_max_silence_s. Без smart-turn — конец реплики после live_turn_silence_s тишины.
    live_smart_turn: bool = True
    live_turn_silence_s: float = 0.5
    live_turn_max_silence_s: float = 0.8
    # После конца реплики ждём финальный текст STT не дольше (потом — последний промежуточный)
    live_stt_final_timeout_s: float = 1.5
    # Распознавание live: segmented — отрезки речи по VAD целиком в STT_PROVIDER (OpenRouter / Gemini): платим
    # только за речь, тишина бесплатна, но без промежуточного текста; elevenlabs — потоковый Scribe Realtime
    # (промежуточный текст, быстрее на ~0,3–0,5 с, но платится каждая секунда открытого микрофона).
    live_stt_mode: Literal["segmented", "elevenlabs"] = "segmented"
    # segmented: ждём текст отрезка не дольше (от конца речи), затем отрезок считается пустым
    live_stt_segment_timeout_s: float = 4.0
    # Передавать STT язык экрана; false (по умолчанию) — автоопределение: язык реплики — по тексту и языку от STT,
    # а если STT вернул язык не из ru/kk/en — язык сессии (hello.lang)
    live_stt_language_hint: bool = False
    # Перебивание: речь посетителя во время ответа не короче, мс, и текст не шум (app/noise.py)
    live_barge_in_min_ms: int = 300
    live_barge_in_require_text: bool = True
    # Без эхоподавления на роботе (hello.aec=false), пока звучит ответ: тихий звук (RMS PCM16 ниже порога)
    # считается эхом и глушится, перебивание — от live_echo_min_ms речи; хвост — реверберация зала после звука
    live_echo_rms: int = 1800
    live_echo_min_ms: int = 600
    live_echo_tail_s: float = 0.3
    # TTS live: ru/en — потоковый WebSocket ElevenLabs (модели v3 он не поддерживает), kk — HTTP-поток eleven_v3.
    # Голоса — ELEVENLABS_VOICE_RU/KK/EN, voice_settings — ELEVENLABS_STABILITY… / ELEVENLABS_V3_STABILITY.
    live_tts_model: str = "eleven_flash_v2_5"
    # Своя модель live для языка (пусто: ru/en — LIVE_TTS_MODEL, kk — ELEVENLABS_TTS_MODEL_KK; kk — только v3)
    live_tts_model_ru: str | None = None
    live_tts_model_en: str | None = None
    live_tts_model_kk: str | None = None
    # Прогрев голосов: холодный первый запрос к голосу ElevenLabs — 10–19 с, тёплый — 0,5–0,8 с. При открытии
    # live-сессии — короткий синтез в фоне (звук роботу не уходит), не чаще раза в интервал на голос и модель.
    # active — только язык сессии; all — сначала он, затем остальные; off — без прогрева.
    live_tts_warmup: Literal["off", "active", "all"] = "all"
    live_tts_warmup_interval_s: float = 600.0
    # Звук шаблонных фраз быстрого пути (места) — из кеша TTS (память + диск TTS_CACHE_DIR), PCM 24 кГц
    live_tts_phrase_cache: bool = True
    # Фраза длиннее — режется для синтеза по запятым (субтитр остаётся целым): короче кусок — раньше звук.
    # kk (eleven_v3 по HTTP) начинает тем позже, чем длиннее текст, поэтому режется мельче, первая — особенно.
    live_tts_chunk_chars: int = 120
    live_tts_chunk_chars_kk: int = 70
    live_tts_first_chunk_chars_kk: int = 40
    # Быстрый путь live: «где X?» / «проводи до X» с однозначным местом из базы и «да» на «Проводить вас?» —
    # шаблонный ответ + действие без модели (app/agent/fastpath.py); false — всё через агента
    live_fast_places: bool = True
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
    tts_timeout_s: float = 4.0  # робот ждёт /tts 5 с (в потоке — до первых байт)
    # Потоковый /v1/tts?stream=true: весь поток не дольше, сек (дальше — обрыв, клиент дозапрашивает)
    tts_stream_total_s: float = 20.0
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
        "admin_token",
        "openrouter_api_key",
        "tts_fallback_provider",
        "live_enabled",
        "elevenlabs_tts_model_ru",
        "elevenlabs_tts_model_en",
        "live_tts_model_ru",
        "live_tts_model_en",
        "live_tts_model_kk",
        mode="before",
    )
    @classmethod
    def _empty_to_none(cls, v):
        return None if isinstance(v, str) and not v.strip() else v

    @field_validator("agent_model", mode="before")
    @classmethod
    def _agent_model_openrouter_id(cls, v):
        # Совместимость со строками LiteLLM: openrouter/anthropic/… → anthropic/… (id OpenRouter)
        return v.strip().removeprefix("openrouter/") if isinstance(v, str) else v

    def gemini_fallback_list(self) -> list[str]:
        return _split(self.gemini_fallback_models)

    def gemini_stt_fallback_list(self) -> list[str]:
        return _split(self.gemini_stt_fallback_models)

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

    def resolved_chat_provider(self) -> str:
        if self.chat_provider != "auto":
            return self.chat_provider
        if self.agent_enabled is False:
            return "off"
        return "openrouter" if self.openrouter_api_key else "off"

    def resolved_stt_stream_provider(self) -> str:
        if self.stt_stream_provider != "auto":
            return self.stt_stream_provider
        return "elevenlabs" if self.elevenlabs_api_key else "buffered"

    def resolved_live_enabled(self) -> bool:
        if self.live_enabled is not None:
            return self.live_enabled
        return bool(self.elevenlabs_api_key) and self.resolved_chat_provider() == "openrouter"

    def resolved_vision_provider(self) -> str:
        if self.vision_provider != "auto":
            return self.vision_provider
        return self._auto_llm()


def _split(value: str) -> list[str]:
    return [m.strip() for m in value.split(",") if m.strip()]
