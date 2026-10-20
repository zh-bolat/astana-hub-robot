# Схема базы данных (PostgreSQL + pgvector)

**Статус: v1.1, 2026-10-20** (реализовано в миграции `0001_baseline`). Источник истины для `backend/` (модели, миграции Alembic) и `infra/` (образ Postgres). Изменения — сначала здесь.

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

### `documents` — длинные материалы (из папки Google Drive: программы, правила, FAQ)
`id` bigint PK · `title` · `source_url` text null · `lang` char(2) null · `content` text · `content_hash` text · `is_active` · `updated_at`. Режутся на чанки в `kb_chunks`.

### `kb_sync` — журнал синхронизаций
`id` bigint PK · `source` (`sheet`\|`yaml`\|`drive`\|`admin`) · `started_at`, `finished_at` · `status` (`ok`\|`error`\|`skipped`) · `version` text (хеш) · `counts` jsonb · `error` text null.

## 2. Векторный поиск (pgvector, RAG)

### `kb_chunks` — фрагменты знаний для поиска
| Колонка | Тип | Примечание |
|---|---|---|
| `id` | bigint PK | |
| `source_type` | text | `fact` \| `place` \| `event` \| `document` |
| `source_id` | text | id исходной записи |
| `lang` | char(2) | |
| `content` | text | текст фрагмента (для документов — чанк ~500–800 токенов с перекрытием) |
| `content_hash` | text | пересчитываем эмбеддинг только если текст изменился |
| `embedding` | **vector(768)** | модель эмбеддингов — в `embedding_model`; размерность уточняется при выборе модели |
| `embedding_model` | text | например `gemini-embedding-…` |
| `tsv` | tsvector GENERATED (`simple`) | полнотекст (словаря для казахского в PG нет → `simple`) |
| `updated_at` | timestamptz | |

Индексы: **HNSW** по `embedding` (`vector_cosine_ops`), GIN по `tsv`, `(source_type, source_id)`, `lang`.

**Поиск — гибридный:** top-k по косинусной близости + top-k по полнотексту → слияние (RRF) → в промпт модели идут найденные фрагменты с `source_id`. Пока база маленькая, сервер может по-прежнему класть всю базу в контекст; переключатель `RETRIEVAL_MODE = full | hybrid`.

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

## Порядок внедрения
1. **Шаг 1 (сейчас):** образ с pgvector, Alembic, все таблицы выше (включая `kb_chunks` без заполнения), синк таблица/YAML → Postgres, сервер читает знания из Postgres, сид из YAML для разработки.
2. **Шаг 2:** эмбеддинги (Gemini), заполнение `kb_chunks`, гибридный поиск, `RETRIEVAL_MODE=hybrid`, загрузка документов из Drive.
3. **Шаг 3 (агентский чат):** `sessions`/`messages` как память, инструменты агента используют поиск из шага 2; `robots` — токены по роботам.
