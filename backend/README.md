# Astana Hub Robot — backend

FastAPI-сервер для робота: `/v1/ask` (вопросы о хабе по базе знаний, JSON или SSE-стриминг), `/v1/look`
(кадр с камеры → vision LLM), `/v1/stt` и `/v1/tts` (голос через сервер), `/v1/places`, `/v1/quick-questions`,
`/v1/health`. Контракт — [`docs/api-contract.md`](../docs/api-contract.md) (истина), OpenAPI — `openapi.json`
(генерируется, см. ниже). `/docs`, `/redoc`, `/openapi.json` по умолчанию выключены (404); локально включить:
`EXPOSE_DOCS=true uv run uvicorn app.main:app --port 8000` → `http://localhost:8000/docs`.

## Быстрый старт

Нужен PostgreSQL 16 + pgvector — единственная рабочая БД (схема: [`docs/db-schema.md`](../docs/db-schema.md)).
Локально он поднимается из `infra/` в Docker:

```bash
# 1) БД (порт 127.0.0.1:${POSTGRES_PORT:-5432}; пароль — POSTGRES_PASSWORD в infra/.env)
cd infra && cp -n .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres

# 2) сервер вне Docker (hot reload)
cd ../backend
uv sync
cp .env.example .env          # ROBOT_TOKEN; DATABASE_URL с паролем из infra/.env; GEMINI_API_KEY — по желанию
uv run uvicorn app.main:app --reload --port 8000
```

При старте сервер сам применяет миграции (`DB_AUTO_MIGRATE=true`) и, если база знаний пуста и Google-таблица
не задана, засевает её из `knowledge/*.yaml` (`KB_SEED_YAML_IF_EMPTY=true`). Вручную:

```bash
uv run python -m app.cli db upgrade      # миграции Alembic до head (то же: uv run alembic upgrade head)
uv run python -m app.cli db current      # текущая ревизия (код 1 — схема не на head)
uv run python -m app.cli kb seed-yaml    # YAML → Postgres (разработка)
uv run python -m app.cli kb sync         # Google-таблица → Postgres
uv run python -m app.cli kb status       # активная база и журнал синхронизаций (kb_sync)
uv run python -m app.cli db import-sqlite /data/questions.db   # перенос старого лога вопросов из SQLite
```

Весь стек в Docker (api + postgres): `cd infra && docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build`.

Без `GEMINI_API_KEY` сервер работает на **FakeProvider** (детерминированные ответы по ключевым словам базы, без сети)
и **FakeSpeechProvider**. Так же работают тесты и CI.

```bash
# вопрос
curl -s localhost:8000/v1/ask -H "Authorization: Bearer $ROBOT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"Где можно поесть?","session_id":"6f1c2a9e-0000-0000-0000-000000000000"}'

# то же со стримингом (SSE): фразы приходят по мере генерации
curl -sN localhost:8000/v1/ask -H "Authorization: Bearer $ROBOT_TOKEN" -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' -d '{"question":"Wi-Fi құпиясөзі қандай?","session_id":"s1"}'

# «что это?»
curl -s localhost:8000/v1/look -H "Authorization: Bearer $ROBOT_TOKEN" \
  -F 'image=@frame.jpg;type=image/jpeg' -F 'meta={"session_id":"s1","place_id":"cafe","lang":"ru"}'

# голос: WAV 16 кГц mono 16-bit → текст; фраза → MP3 (заголовок X-Speech-Provider)
curl -s localhost:8000/v1/stt -H "Authorization: Bearer $ROBOT_TOKEN" \
  -F 'audio=@q.wav;type=audio/wav' -F 'meta={"lang":null,"session_id":"s1"}'
curl -si localhost:8000/v1/tts -H "Authorization: Bearer $ROBOT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"Асхана бірінші қабатта.","lang":"kk"}' -o phrase.mp3

curl -si localhost:8000/v1/places -H "Authorization: Bearer $ROBOT_TOKEN"            # ETag / If-None-Match → 304
curl -s 'localhost:8000/v1/quick-questions?lang=kk' -H "Authorization: Bearer $ROBOT_TOKEN"
```

С эмулятора Android сервер доступен по `http://10.0.2.2:8000/v1` (с других устройств — `--host 0.0.0.0`).

## Проверки

Тесты идут на настоящем Postgres + pgvector (`TEST_POSTGRES_URL`, по умолчанию
`postgresql+psycopg://hub:hub@127.0.0.1:55432/hub_test`; схема `hub_test` пересоздаётся миграциями):

```bash
docker run -d --rm --name hubtest-pg -e POSTGRES_PASSWORD=hub -e POSTGRES_USER=hub \
  -e POSTGRES_DB=hub_test -p 127.0.0.1:55432:5432 pgvector/pgvector:pg16
uv run pytest                 # тесты (на Fake-провайдерах, без ключей и сети)
docker stop hubtest-pg        # контейнер удалится сам (--rm)
uv run ruff check && uv run ruff format --check
uv run python -m eval.run_eval [--min-accuracy 0.9] [--no-lang] [--json report.json]   # eval, шаг 3.8
LLM_PROVIDER=fake uv run python -m eval.run_eval --min-accuracy 0.9                     # eval без ключа (CI)
uv run python -m eval.run_injection [--sleep 4] [--json report.json]  # prompt injection на модели из .env
uv run python -m eval.run_injection --fake                            # то же без сети
```

Eval использует провайдер из `.env`: без ключа — fake (проверяет пайплайн), с `GEMINI_API_KEY` — реальную модель
(тратит запросы). Postgres для eval не нужен: база знаний — YAML из `knowledge/`, лог вопросов — в памяти.

## Конфигурация (env / `backend/.env`)

Полный список с комментариями — `.env.example`. Главное:

| Переменная | По умолчанию | |
|---|---|---|
| `ROBOT_TOKEN` | — (обязательно) | Bearer-токен робота; без него сервер не стартует |
| `LLM_PROVIDER` | `auto` | `gemini` / `deepseek` / `fake`; `auto` = gemini при наличии ключа, иначе fake |
| `VISION_PROVIDER` | `auto` | `gemini` / `fake` (у DeepSeek нет vision) |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | —, `gemini-3.1-flash-lite` | основная модель /ask и /look (быстрее, больший лимит бесплатного ключа) |
| `GEMINI_FALLBACK_MODELS` | `gemini-3.5-flash` | резервные модели через запятую |
| `GEMINI_THINKING_LEVEL` | `minimal` | размышления Gemini 3.x; `GEMINI_THINKING_BUDGET` — только для 2.5 |
| `ASK_TIMEOUT_S`, `LOOK_TIMEOUT_S` | 6.5, 11 | бюджет сервера на ответ LLM, включая повтор и резерв; иначе `504 timeout` |
| `ASK_STREAM_TOTAL_S` | 12 | SSE /ask: предел на весь стрим |
| `ANSWER_MODE` | `open` | `open` — база, иначе ответ модели из общих знаний; `strict` — только база + small talk |
| `RATE_LIMIT_PER_MIN` | 30 | /ask и /look, на токен, in-memory (один процесс) |
| `KB_RATE_LIMIT_PER_MIN` | 60 | отдельный бакет для /places и /quick-questions |
| `EXPOSE_DOCS` | `false` | отдавать `/docs`, `/redoc`, `/openapi.json` (только локально) |
| `LLM_MAX_OUTPUT_TOKENS` | 320 | потолок длины ответа модели; при включённых размышлениях Gemini поднять |
| `KNOWLEDGE_DIR` | `./knowledge` | YAML-база: сид для разработки и запасной вариант без БД |
| `KB_SEED_YAML_IF_EMPTY` | `true` | пустая база знаний и нет таблицы → сид из YAML при старте |
| `KNOWLEDGE_RELOAD_INTERVAL_S` | 5 | как часто сервер сверяет `kb_sync.version` и перечитывает базу из БД |
| `KNOWLEDGE_SHEET_ID`, `KNOWLEDGE_SYNC_INTERVAL_S` | —, 300 | синк Google-таблица → Postgres (см. ниже) |
| `STT_PROVIDER`, `TTS_PROVIDER` | `auto`, `auto` | голос через сервер (см. ниже) |
| `ELEVENLABS_API_KEY`, `YANDEX_API_KEY` | — | ключи провайдеров речи |
| `SPEECH_RATE_LIMIT_PER_MIN` | 120 | отдельный лимит для /stt и /tts |
| `DATABASE_URL` | `postgresql+psycopg://hub@127.0.0.1:5432/hub` (в compose — `…@postgres:5432/hub`) | Postgres 16 + pgvector; SQLite не поддерживается |
| `DB_AUTO_MIGRATE` | `true` | миграции Alembic при старте, до приёма запросов; ошибка миграции — процесс завершается с ненулевым кодом |
| `DB_TIMEOUT_S` | 2 | подключение/запрос Postgres (`connect_timeout`, `statement_timeout`, `lock_timeout`) |
| `QUESTION_LOG_RETENTION_DAYS` | 180 | срок хранения лога вопросов, сессий и сообщений (0 — вечно); очистка раз в сутки фоном |
| `KNOWLEDGE_SNAPSHOT_PATH`, `TTS_CACHE_DIR` | `backend/data/...` (в образе `/data/...`) | снимок базы знаний (старт без БД) и кеш синтеза |
| `KNOWLEDGE_SYNC_TIMEOUT_S` | 5 | общий таймаут загрузки всех вкладок таблицы |
| `TRUSTED_PROXIES` | `172.16.0.0/12,127.0.0.1` | откуда принимать `X-Forwarded-For` (IP клиента в access-логе; в образе — и для uvicorn `--forwarded-allow-ips`) |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | —, `production` | ошибки в Sentry; без DSN выключено; ПДн и тексты вопросов/ответов вырезаются |

## Режимы ответа и категории

Модель возвращает категорию реплики; она пишется в лог вопросов (колонка `category`):

| Категория | Что это | `found` | Ответ |
|---|---|---|---|
| `kb` | ответ есть в базе знаний | true | по базе, `sources` заполнены |
| `small_talk` | «привет», «ты меня слышишь?», «спасибо», «что ты умеешь?»… | true | дружелюбный ответ от лица помощника |
| `general` | в базе нет (только `ANSWER_MODE=open`) | false | ответ модели из общих знаний; конкретику о хабе (цены, телефоны, имена, расписания) не выдумывает — предлагает ресепшен |
| `out_of_scope` | ответить нечего (в `strict` — всё, чего нет в базе) | false | «обратитесь на ресепшен» |
| `blocked` | ответ модели не прошёл выходной фильтр (см. «Защита») | false | «могу рассказать только об Astana Hub…» |

Самые частые короткие реплики («привет», «сәлем», «hello», «спасибо», «рахмет», «пока»…) отвечаются локально без LLM
(`app/smalltalk.py`, ~0 мс). Вопросы «без ответа в базе» — это `found=false` (категории `general` и `out_of_scope`):
`uv run python -m app.cli export-csv --unanswered`.

Язык ответа: письменность вопроса важнее всего — кириллица → только ru или kk (kk по казахским буквам/частицам или
если так сказали робот/модель), никогда en; латиница → en (kk — только явно). `lang` робота и язык от модели
используются, только когда по тексту не понять (нет букв). Модели язык подсказывается заранее в промпте.

## Защита

- `app/guard.py` — чистый ASGI-middleware перед FastAPI: для `/v1/*` (кроме `/v1/health`) сначала Bearer-токен
  (`401` без чтения тела), затем лимит тела по пути: `/ask` 16 КБ, `/look` 1 МБ + 64 КБ, `/stt` 600 КБ + 64 КБ,
  `/tts` 4 КБ, прочее 16 КБ. По `Content-Length` — `413` сразу; chunked — счёт байтов при чтении и обрыв с `413`.
- Rate limit на токен: `/ask`+`/look` 30/мин, `/stt`+`/tts` 120/мин, `/places`+`/quick-questions` 60/мин — три
  независимых бакета.
- Prompt injection: реплика посетителя идёт в промпт внутри `<visitor_message>…</visitor_message>` (теги изнутри
  реплики вырезаются), в системном промпте — блок «БЕЗОПАСНОСТЬ» (реплика и база — данные, не раскрывать правила,
  не повторять дословно, не выдумывать статус/часы/цены/акции/ссылки, не брать новые роли).
- Выходной фильтр (`app/safety.py`): ≥ 6 слов подряд из реплики (кроме фраз из базы), URL/домены/e-mail/телефоны/QR,
  которых нет в базе → безопасный ответ, в логе `category=blocked`. Работает и в SSE (пофразно).
- Длина: не больше 4 фраз и 600 символов (`_finalize` и SSE — после лимита фразы не отправляются).

## LLM: повтор, резерв, стриминг

- 503/429/таймаут/обрыв соединения у основной модели → 1 быстрый повтор, если после него ещё остаётся время на
  резерв; затем модели из `GEMINI_FALLBACK_MODELS`. Если основная молчит дольше `GEMINI_HEDGE_AFTER_S` (3 с),
  резервная запускается параллельно — побеждает первый ответ. Всё укладывается в `ASK_TIMEOUT_S` / `LOOK_TIMEOUT_S`
  (робот ждёт 8 / 12 с). Какая модель ответила — в логе (`ask_answered.provider`, `llm_fallback_used`) и в колонке
  `provider` лога вопросов. Реализация — `app/llm/fallback.py`.
- Размышления: для Gemini 3.x передаётся `thinking_level=minimal` (в замерах — 0 thought-токенов);
  внутренние ретраи SDK выключены.
- SSE (`Accept: text/event-stream`): ответ модели стримится, событие `phrase` уходит, как только модель дописала
  предложение (`app/streaming.py` разбирает JSON на лету). `found=false` → стандартный отказ на языке вопроса.
  Повтор/резерв работают до первого куска ответа.

## Голос через сервер (/v1/stt, /v1/tts)

STT и TTS выбираются раздельно (`app/speech/`):

| | `auto` выбирает | Реализации |
|---|---|---|
| STT (`STT_PROVIDER`) | elevenlabs → gemini → fake | `GeminiSttProvider`, `ElevenLabsProvider` (Scribe), `YandexSpeechKitProvider`, `FakeSpeechProvider` |
| TTS (`TTS_PROVIDER`) | elevenlabs → yandex → fake | `ElevenLabsProvider`, `YandexSpeechKitProvider`, `FakeSpeechProvider` |

Сейчас (есть только ключ Gemini): **STT = gemini, TTS = fake**. Все ответы `/v1/tts` несут заголовок
`X-Speech-Provider: <имя>`; при `fake` тело — беззвучный MP3, и робот озвучивает фразу встроенным синтезом.

Общее для STT: WAV проверяется (не PCM 16-bit mono 16 кГц → 400; > 600 КБ или > 15 с → 413); тишина
(пик < `STT_SILENCE_PEAK`) → пустой текст без вызова провайдера; «транскрипты» без букв (`00:00`, `[music]`) →
пустой текст. Аудио посетителей не сохраняется и не логируется: в лог идут длительность, язык, задержка.

- **Gemini STT**: `gemini-3.1-flash-lite` (резерв `gemini-3.5-flash`), JSON `{lang, text}` — транскрипт и язык
  одним запросом, ~2–3 с на фразу 2–3 с. `gemini-3.5-transcribe` не используем: нет JSON-режима и языка в ответе,
  в замерах часто 503 и до 18 с.
- **ElevenLabs**: TTS `POST /v1/text-to-speech/{voice_id}` (`xi-api-key`, mp3): kk → `eleven_v3` (единственная
  модель с казахским), ru/en → `eleven_flash_v2_5`, `language_code` передаётся. STT — Scribe `scribe_v2`, сырой PCM
  (`file_format=pcm_s16le_16`), `language_code` при известном языке, иначе автоопределение; `confidence` =
  `language_probability`. Голоса — `ELEVENLABS_VOICE_RU/KK/EN` (voice_id из библиотеки ElevenLabs).
- **Yandex SpeechKit** (API v1): STT `stt:recognize` (`lpcm`, 16 кГц; без `lang` — параллельно ru/kk/en и выбор
  гипотезы, втрое дороже), TTS `tts:synthesize` (`mp3`, голоса jane / amira / john). Ключ сервисного аккаунта
  (`Api-Key`); API v1 доступен в регионе Россия Yandex Cloud.
- Кеш TTS по (провайдер, голос+модель, язык, текст): LRU в памяти + `data/tts_cache` с лимитом размера;
  заголовок `X-Cache: hit|miss`.

## База знаний

База знаний живёт в Postgres: таблицы `facts`, `places`, `quick_questions`, `events` (+ `kb_sync` — журнал
синхронизаций). Сотрудники хаба редактируют **Google-таблицу**, сервер синхронизирует её в БД; YAML-файлы в
`knowledge/` (**сейчас там примеры-заглушки**, не реальные данные хаба) — только для разработки и тестов:

- `facts.yaml` — факты: `id`, `keywords`, `question`/`answer` на ru/kk/en;
- `places.yaml` — точки карты робота (`map_name` = имя в `getPlaceList`), синонимы, описания, `visual_hints` для `/look`;
- `quick_questions.yaml` — кнопки частых вопросов.

Как это работает:

- Любая запись в базу (синк таблицы, `kb seed-yaml`) — одна транзакция: upsert строк, исчезнувшие строки
  получают `is_active=false` (не удаляются; строки с `source='admin'` синк не трогает), в `kb_sync` — статус
  `ok` / `skipped` (ничего не изменилось) / `error` и `version` — хеш содержимого базы.
- Сервер держит базу в памяти и раз в `KNOWLEDGE_RELOAD_INTERVAL_S` (5 с) сверяет последнюю `kb_sync.version`;
  изменилась — перечитывает. Перезапуск сервера и робота не нужен; синк, сделанный другим контейнером или через
  CLI, подхватывается так же. Запросы к БД — в фоновом потоке, `/ask` их не ждёт.
- Postgres недоступен при старте → последний снимок (`KNOWLEDGE_SNAPSHOT_PATH`), нет снимка → YAML; когда БД
  поднимется, база подтянется фоном.

Правка YAML на работающем сервере: `uv run python -m app.cli kb seed-yaml` → изменения видны за ~5 с. Битый
YAML — ошибка команды (`kb_sync.status=error`), база в БД не меняется. Проверка YAML без БД:
`uv run python -m app.cli check-kb`.

### Google-таблица (шаг 3.3)

1. Создайте таблицу с тремя вкладками: **Факты**, **Места**, **Кнопки** (имена меняются через
   `KNOWLEDGE_SHEET_FACTS/PLACES/BUTTONS`). Первая строка — заголовки колонок, как в шаблонах
   `knowledge/sheet_template/{facts,places,buttons}.csv` (их можно импортировать: Файл → Импорт → вставить в текущий лист).
2. Доступ: «Все, у кого есть ссылка — Читатель» (OAuth не нужен, сервер читает CSV-экспорт
   `…/gviz/tq?tqx=out:csv&sheet=<вкладка>`).
3. В `.env`: `KNOWLEDGE_SHEET_ID=<часть ссылки между /d/ и /edit>`. Проверить: `uv run python -m app.cli kb sync`.

| Вкладка | Колонки (обязательные — **жирным**) |
|---|---|
| Факты | **id**, active, tags, keywords, question_ru, question_kk, question_en, **answer_ru / answer_kk / answer_en** (хотя бы один) |
| Места | **id**, active, **map_name**, name_ru, name_kk, name_en, synonyms, description_ru, description_kk, description_en, visual_hints, photos |
| Кнопки | **id**, active, **text_ru / text_kk / text_en** (хотя бы один) |

- `active`: `нет` / `no` / `0` / `false` — строка не используется (удобно временно выключить факт).
- Списки (`tags`, `keywords`, `synonyms`, `photos`) — через запятую или `;`.
- Ответы — 1–3 коротких предложения, без markdown, ссылок, эмодзи (их озвучивает робот).
- Строки без обязательных полей пропускаются (`sheet_row_skipped` в логе); дубликаты `id` и пустая вкладка
  фактов — ошибка синхронизации.

Поведение: старт не ждёт Google — сервер поднимается на базе из Postgres (пустая БД до первого синка —
запасной YAML; автосида из YAML при заданной таблице нет). Сразу после старта и далее раз в
`KNOWLEDGE_SYNC_INTERVAL_S` (по умолчанию 5 мин) — фоновая синхронизация таблица → Postgres: все вкладки
параллельно, общий таймаут `KNOWLEDGE_SYNC_TIMEOUT_S` (5 с). При ошибке (нет сети, таблица закрыта, битые
данные, дубли `id`/`map_name`, таймаут) база в БД не меняется, в `kb_sync` — строка `error`
(`uv run python -m app.cli kb status`).

Заголовки вкладок проверяются: «Факты» — `id` и хотя бы один из `answer_ru/kk/en`, «Места» — `id`, `map_name`,
«Кнопки» — `id`, `text_ru`. Это важно: при неверном имени вкладки Google молча отдаёт первую вкладку, и без
проверки «Места» прочитались бы из «Фактов». Несовпадение — ошибка синхронизации, база не меняется.

Поиск на MVP — «вся база в контексте» LLM: база идёт в system instruction стабильным префиксом
(Gemini кеширует его неявно). Источник скрыт за интерфейсом `KnowledgeSource` (`app/knowledge/source.py`,
рабочая реализация — `DbKnowledgeSource` в `app/knowledge/pg.py`). Таблица `kb_chunks` (pgvector, HNSW + GIN)
уже создана, но пока не заполняется — гибридный поиск будет на шаге 2 (docs/db-schema.md).

## Лог вопросов

Каждый `/ask` и `/look` пишется в таблицу `question_log` (только текст: вопрос, ответ, язык, место, `found`,
источники, задержка, провайдер и модель). Кадры камеры и аудио не сохраняются.

Запись идёт фоном (отдельный поток с ограниченной очередью): ответ роботу не ждёт БД, а зависшая или упавшая БД
на `/ask` не влияет (в логе — `question_log_failed`, при переполнении очереди — `question_log_dropped`). Так же
фоном обновляется сессия посетителя (`sessions.last_seen_at`). Схема — миграции Alembic (`migrations/`, под
advisory lock — безопасно при одновременном старте нескольких контейнеров); baseline-миграция подхватывает
таблицу `question_log`, созданную до Alembic (`sources` → jsonb, `id` → bigint). Записи старше
`QUESTION_LOG_RETENTION_DAYS` (180) удаляются раз в сутки (вместе с сессиями и сообщениями).

Новая миграция: `uv run alembic revision -m "что меняем"` → правка файла в `migrations/versions/` и моделей в
`app/db/models.py` (тест `tests/test_migrations.py` сверяет модели с миграциями).

```bash
uv run python -m app.cli export-csv --out questions.csv               # всё
uv run python -m app.cli export-csv --out unanswered.csv --unanswered # только «без ответа»
```

## Прочее

```bash
uv run python -m app.cli openapi --out openapi.json   # обновить OpenAPI-схему
docker build -t astana-hub-backend .                   # образ: uv, non-root, порт 8000, healthcheck /v1/health
# запуск образа — через infra/docker-compose.yml (нужен Postgres); снимок базы и кеш TTS — в volume /data
```

## Структура

```
app/
  main.py          create_app(): миграции, база знаний из Postgres, фоновые синк/сверка, X-Request-Id + access-лог
  api.py           эндпоинты v1, авторизация, rate limit, SSE, multipart
  service.py       логика /ask (JSON и стрим) и /look: база → промпт → LLM → очистка → фразы → лог
  streaming.py     разбор JSON-ответа модели на лету (фразы по готовности)
  prompts.py       промпты и тексты отказов
  lang.py          определение языка ru/kk/en
  text.py          очистка для TTS, разбиение на фразы ≤ 300 символов
  llm/             LLMProvider: gemini.py, deepseek.py (заготовка), fake.py, fallback.py, factory.py
  speech/          STT/TTS: gemini_stt.py, elevenlabs.py, yandex.py, fake.py, wav.py, cache.py, factory.py
  speech_api.py    эндпоинты /v1/stt, /v1/tts
  knowledge/       модель базы, Postgres (pg.py), синк (sync.py), Google-таблица (sheets.py), YAML
  db/              engine, модели всех таблиц (models.py), репозитории (repos.py), Alembic (migrate.py)
  cli.py           db upgrade|current|import-sqlite, kb seed-yaml|sync|status, export-csv, openapi, check-kb
migrations/        Alembic: env.py, versions/ (0001_baseline — все таблицы docs/db-schema.md)
knowledge/         база знаний (примеры) + sheet_template/ (CSV-шаблоны вкладок таблицы)
eval/              questions.yaml + run_eval.py (шаг 3.8)
tests/             pytest
```
