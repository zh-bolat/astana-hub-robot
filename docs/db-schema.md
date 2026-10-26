# Схема базы данных (PostgreSQL + pgvector)

**Статус: v1.2, 2026-10-26** (миграции `0001_baseline` … `0004_rag_chunks`; v1.2 — поиск по базе, раздел 2). Источник истины для `backend/` (модели, миграции Alembic) и `infra/` (образ Postgres). Изменения — сначала здесь.

## Принципы
- Одна БД **PostgreSQL 16 с расширением pgvector** (образ `pgvector/pgvector:pg16`), в Docker — локально и на VPS одинаково. SQLite не используется нигде (старый лог переносится командой `python -m app.cli db import-sqlite`).
- Схема меняется **только миграциями Alembic** (`backend/migrations/`), никаких `create_all`/ручных `ALTER` при старте.
- Языки фиксированы: `ru`, `kk`, `en` — переводы хранятся колонками `*_ru/_kk/_en`, не отдельной таблицей.
- Редактирование базы знаний — по-прежнему через **Google-таблицу** (сотрудники хаба). Синк пишет в таблицы знаний; YAML — только для разработки и тестов.
- Время — `timestamptz` (UTC). Идентификаторы знаний — человекочитаемые `text` (`wifi`, `cafe`), служебные — `bigint identity`.
- Персональные данные: тексты вопросов и сообщений хранятся ограниченное время (`QUESTION_LOG_RETENTION_DAYS`, по умолчанию 180), аудио и кадры камеры **не хранятся никогда**.

Допустимые значения `source`, `status`, `role`, `rating`, `source_type` проверяются CHECK-ограничениями.

## 1. Знания (что робот знает)

### `facts` — факты и ответы
| Колонка | Тип | Примечание |
|---|---|---|
| `id` | text PK | `wifi`, `hours` |
| `tags` | text[] | темы |
| `keywords` | text[] | синонимы на всех языках |
| `question_ru/kk/en` | text null | типичная формулировка |
| `answer_ru/kk/en` | text null | хотя бы один язык обязателен (CHECK) |
| `is_active` | bool default true | |
| `source` | text | `sheet` \| `yaml` \| `admin` |
| `updated_at` | timestamptz | |

### `places` — точки карты робота
| Колонка | Тип | Примечание |
|---|---|---|
| `id` | text PK | `cafe` |
| `map_name` | text, уникален среди активных (частичный UNIQUE `WHERE is_active`) | имя точки ровно как в `getPlaceList` робота |
| `name_ru/kk/en` | text | |
| `synonyms` | text[] | «кафешка», «асхана» |
| `description_ru/kk/en` | text null | |
| `visual_hints` | text null | как выглядит (для `/look`) |
| `photos` | text[] | фото-эталоны помещения |
| `floor` | smallint null | |
| `is_active`, `source`, `updated_at` | | как у `facts` |

### `quick_questions` — кнопки на экране
`id` text PK · `text_ru/kk/en` · `sort_order` int · `is_active` · `source` · `updated_at`

### `events` — мероприятия (для агента и вопросов «что сегодня»)
`id` text PK · `title_ru/kk/en` · `description_ru/kk/en` · `starts_at`, `ends_at` timestamptz · `place_id` → `places.id` null · `is_active` · `source` · `updated_at`. Индекс по `starts_at`.

### `documents` — длинные материалы (программы, правила, FAQ)
`id` bigint PK · `slug` text UNIQUE (имя файла без `.md`, `[a-z0-9_]+`; стабильный ключ) · `source` text default `'yaml'`
(`sheet`\|`yaml`\|`drive`\|`admin`) · `title` · `source_url` text null · `lang` char(2) null · `content` text (без шапки) ·
`content_hash` text · `is_active` · `updated_at`. Режутся на фрагменты в `kb_chunks`.

Источник сейчас — папка `KNOWLEDGE_DIR/documents/<slug>.md` (markdown с шапкой `title`, `lang`, `source_url`, `active`,
`updated`; формат — `backend/app/knowledge/documents.py`). Пишутся при `kb seed-yaml` и автосиде — той же транзакцией,
что и факты: upsert по `slug`, документ, исчезнувший из папки (или `active: false`), — `is_active=false`; документы других
источников (`drive`, `admin`) папка не трогает. Синк Google-таблицы документы не трогает. Файл с ошибкой пропускается.

### `kb_sync` — журнал синхронизаций
`id` bigint PK · `source` (`sheet`\|`yaml`\|`drive`\|`admin`) · `started_at`, `finished_at` · `status` (`ok`\|`error`\|`skipped`) · `version` text (хеш) · `counts` jsonb · `error` text null.

## 2. Векторный поиск (pgvector, RAG)

### `kb_chunks` — фрагменты знаний для поиска
| Колонка | Тип | Примечание |
|---|---|---|
| `id` | bigint PK | |
| `source_type` | text | `fact` \| `place` \| `event` \| `document` (события пока не индексируются) |
| `source_id` | text | id факта / места, `slug` документа |
| `lang` | char(2) NOT NULL | язык фрагмента |
| `chunk_no` | int default 0 | факт и место — 0; документ — 1..N по порядку |
| `title` | text null | документ — «Заголовок документа — заголовок раздела»; факт — вопрос; место — название |
| `content` | text | текст фрагмента |
| `content_hash` | text | sha256 заголовка и текста: эмбеддинг пересчитывается, только если они (или модель) изменились |
| `embedding` | **vector(768)** | `google/gemini-embedding-2` через OpenRouter, `dimensions: 768` |
| `embedding_model` | text | модель вектора; поиск сравнивает только векторы текущей модели |
| `tsv` | tsvector GENERATED (`simple`) | полнотекст по `title` + `content` (словаря для казахского в PG нет → `simple`) |
| `is_active` | bool default true | фрагмент исчез из базы → `false` (поиск их не видит); вернулся тем же текстом — включается без эмбеддинга |
| `updated_at` | timestamptz | |

Ключ — UNIQUE `(source_type, source_id, lang, chunk_no)`. Индексы: HNSW по `embedding` (`vector_cosine_ops`, пока не
используется — см. ниже), GIN по `tsv`, `(source_type, source_id)`, `lang`. id фрагмента в ответах API —
`document:<slug>#<n>` / `fact:<id>` / `place:<id>` (`docs/api-contract.md`, «Источники и поиск по документам»).

**Что индексируется** (`app/knowledge/chunks.py`): активные факты — фрагмент на каждый язык с ответом (вопрос + ответ);
активные места — фрагмент на язык (название, синонимы, описание); активные документы — по заголовкам `#`/`##`/`###`:
мелкие разделы склеиваются до ~1200 символов (заголовки следующих разделов — строкой в тексте), раздел длиннее 2400
символов (~600 токенов) режется по абзацам (абзац — по строкам и предложениям) с перекрытием ≤ 300 символов (последний
абзац или последние предложения предыдущего куска). Шапка документа во фрагменты не попадает.

**Индексация** (`app/knowledge/index.py`, CLI `kb index [--force]`): желаемые фрагменты сравниваются с таблицей по ключу,
`content_hash` и `embedding_model`; эмбеддинги — только для новых и изменённых (батчи ≤ 64, повторы на 429/5xx), лишние —
`is_active=false` (до эмбеддингов: выключенный факт пропадает из поиска и при недоступном провайдере). Повторный запуск —
ноль вызовов провайдера. Ошибка провайдера → лог, неудавшиеся фрагменты остаются старыми до следующей попытки. Один
индексатор за раз (`pg_try_advisory_lock`). Сервер сам переиндексирует в фоне (не блокируя старт и ответы): при старте и
после синка/сида, изменившего базу, — отпечаток «версия последнего `kb_sync` + отпечаток активных документов + модель»
сверяется раз в `KNOWLEDGE_RELOAD_INTERVAL_S`; после ошибки — повтор не раньше чем через 60 с (растёт до 10 мин).

**Поиск — гибридный** (`app/knowledge/search.py`), только через инструмент агента `search_kb`:
1. эмбеддинг вопроса (`task: search result | query: …`; фрагменты — `title: … | text: …`), таймаут
   `EMBEDDING_QUERY_TIMEOUT_S` (1 с); не успел или ошибка — только полнотекст;
2. по смыслу — top-20 по `<=>` среди `is_active` фрагментов текущей модели. **Поиск точный**, мимо HNSW (расстояние
   считается в `MATERIALIZED` CTE): фрагментов сотни, перебор — миллисекунды, а HNSW с фильтром `is_active` отдаёт
   кандидатов до фильтра и мог бы вернуть меньше 20 строк. Когда фрагментов станет десятки тысяч — запрос по индексу
   с `SET hnsw.iterative_scan = relaxed_order` (pgvector 0.8);
3. по словам — все фрагменты, где есть хоть одно слово вопроса (ИЛИ; служебные слова и «Astana Hub» отброшены,
   длинные слова — префиксом `резиден:*` вместо стемминга, названия с точкой/дефисом/цифрами — целиком: `c4.6`,
   `alem.ai`, `wi-fi`) → BM25 по совпавшим словам (IDF + штраф за длину; у `ts_rank_cd` нет IDF) → top-20;
4. слияние RRF (k = 60), один фрагмент на разных языках — один результат: текст на языке вопроса → ru → en → kk;
   ссылка — `documents.source_url`;
5. инструмент берёт 5 кандидатов и отдаёт модели до 3 (документы раньше фактов и мест — те и так в промпте),
   текст ≤ 2500 символов на всё (длинный фрагмент — выдержка по словам вопроса).

Переключатели: `RETRIEVAL_MODE = off | hybrid` (по умолчанию `hybrid`, но только при доступных эмбеддингах:
`EMBEDDING_PROVIDER = auto | openrouter | fake | off`, auto — openrouter при `OPENROUTER_API_KEY`). `off` — инструмента
нет, агент работает только с фактами в промпте, как раньше. Факты и места по-прежнему целиком лежат в промпте агента;
поиск — за подробностями из документов.

## 3. Разговоры и аналитика

### `robots` — роботы и их токены (токен на каждого робота, с отзывом)
`id` text PK · `name` · `token_hash` text (sha256, токен в открытом виде не хранится) · `is_active` · `created_at` · `last_seen_at`.

### `sessions` — разговор с посетителем
`id` text PK (= `session_id` из контракта) · `robot_id` → `robots.id` null · `lang` · `started_at` · `last_seen_at` · `ended_at` null.

### `messages` — память агента (история разговора)
`id` bigint PK · `session_id` → `sessions.id` ON DELETE CASCADE · `role` (`visitor`\|`assistant`\|`tool`) · `content` text · `tool_name` text null · `tool_args` jsonb null · `created_at`. Индекс `(session_id, created_at)`. Срок хранения — как у лога вопросов.

### `question_log` — уже есть, переносится в миграции без изменений смысла
`id` (bigserial), `created_at`, `request_id`, `session_id`, `endpoint`, `question`, `lang`, `place_id`, `found`, `category`, `answer`, `sources` (→ `jsonb`), `provider`, `latency_ms`.

### `feedback` — оценки ответов (сотрудники хаба, будущая панель)
`id` bigint PK · `question_log_id` → `question_log.id` ON DELETE CASCADE · `rating` smallint (-1/1) · `comment` text null · `created_by` text · `created_at`.

### `latency_events` — тайминги этапов (миграция 0003, без текстов)
`id` bigint identity PK · `ts` timestamptz (индекс) · `kind` (`stt`/`chat`/`ask`/`look`/`tts`/`robot`) · `stage` (`stt_ms`, `llm_first_token_ms`, `first_phrase_ms`, `llm_total_ms`, `kb_search_ms` (поиск агента за ход), `tts_first_byte_ms`, `tts_total_ms`, поля `/v1/metrics/turn`, `turn`) · `ms` int null (null — строка хода с флагами) · `lang` · `provider` (провайдер или модель) · `request_id` · `session_id` · `ok` bool · `extra` jsonb (только скаляры: `fallback`, `action`, `cache`, `chars`, …). Индекс `(kind, ts)`. Срок хранения — как у `question_log`.

## Порядок внедрения
1. **Шаг 1 (сейчас):** образ с pgvector, Alembic, все таблицы выше (включая `kb_chunks` без заполнения), синк таблица/YAML → Postgres, сервер читает знания из Postgres, сид из YAML для разработки.
2. **Шаг 2 (сделано, миграция 0004):** эмбеддинги (gemini-embedding-2 через OpenRouter), заполнение `kb_chunks`, гибридный
   поиск, `RETRIEVAL_MODE=hybrid`, документы из папки `KNOWLEDGE_DIR/documents`. Впереди — загрузка документов из Drive.
3. **Шаг 3 (агентский чат):** `sessions`/`messages` как память, инструменты агента используют поиск из шага 2; `robots` — токены по роботам.
