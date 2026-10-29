# Astana Hub Robot

Голосовой робот-помощник технопарка **Astana Hub** на OrionStar GreetingBot Nova (экран 14", Android + RobotOS / AgentOS).
Встречает гостей в холле, разговаривает голосом на **русском, казахском и английском**, отвечает на вопросы о хабе
по базе знаний, показывает дорогу и сам провожает до нужного места, по камере рассказывает, что перед ним.

| Лицо робота | Сон (ждёт касания) |
|---|---|
| ![Лицо робота](docs/images/face.webp) | ![Сон](docs/images/sleep.webp) |
| **Страница приложения: робот-компаньон в казахском костюме** | **Проводы до места** |
| ![Главная](docs/images/home-kazakh.webp) | ![Поездка](docs/images/guide.webp) |

<sub>Снимки с эмулятора 1920×1080 (как экран робота). Поле ввода текста в правом нижнем углу есть только в сборке для эмулятора.</sub>

## Что умеет

**Для посетителя**
- **Живой голосовой разговор, как голосовой режим ChatGPT.** Робот слушает непрерывно, сам понимает, когда человек
  договорил, и сразу отвечает голосом. Язык ответа — язык вопроса (ru / kk / en). Голос — мужской, ElevenLabs
  «Dmitry», характер — дружелюбный и энергичный, умеет пошутить и рассмеяться.
- **Ответы о хабе по базе знаний:** программы, резидентство, Hub Space, залы, правила здания, как добраться, контакты.
  Под ответом — источники.
- **«Где X?»** — направление и расстояние от робота по его карте («Кафе — справа, примерно 15 метров»).
  **«Проводи»** — робот едет к точке, на экране стрелка и «Иду к: …». Работает и **без интернета**.
- **«Что это?»** — снимок с камеры робота → описание помещения или объекта.
- **Экран 14"**:
  - **лицо** — выразительные глаза с эмоциями: думает, радуется, не понял, удивлён;
  - **страница приложения** — по свайпу:
    - робот-компаньон в одном из 4 костюмов: классический, казахский с домброй, айтишник, космонавт;
    - 6 разделов: «Где что находится», «Мероприятия», «Об Astana Hub», «Wi‑Fi и сервисы», «Частые вопросы», «Что это?»;
    - карточки ответов, субтитры, смена языка RU / KZ / EN.
- **Шумный холл.**
  - Робот после запуска **спит** и не слушает. Один тап по лицу будит его, он здоровается.
  - Двойной тап усыпляет; сам засыпает через 5 минут без разговора.
  - Перебить робота голосом можно только командой «стоп» («хватит», «тоқта», «stop»).
  - Чужие разговоры рядом робот старается не принимать за вопросы.

**Для сотрудников**
- Выход из приложения (× в шапке) — только по паролю сотрудника.
- Экран диагностики (удержание логотипа 3 с + тот же пароль):
  - адрес сервера и режим разговора — без пересборки;
  - чувствительность микрофона;
  - проверка головы и камеры;
  - сброс кеша голоса;
  - последние сбои приложения.
- Режим киоска:
  - автозапуск при включении робота (Startup Programs RobotOS);
  - после падения приложение само перезапускается, при серии падений — экран «техническая пауза».
- Журнал вопросов, вопросы без ответа, страница задержек `/v1/admin/metrics/page`.

## Как устроено

```
Посетитель ─ голос ─► Android-приложение на роботе (android-app/)
                        │  лицо, страница приложения, костюмы, сон / пробуждение
                        │  «где X», «проводи» ─► карта и навигация робота (RobotAPI) — без сети
                        │  камера (SurfaceShareApi) ─► кадр для «Что это?»
                        ▼
            WS /v1/live (голос туда и обратно)      запасной путь: /v1/chat (SSE) + /v1/stt + /v1/tts
                        ▼
Сервер (backend/, FastAPI + Pipecat) ─ VAD, конец фразы, распознавание речи, агент, голос ElevenLabs
   агент: OpenAI Agents SDK + Claude Haiku 5.5 (OpenRouter)
     ├─ поиск по базе знаний до ответа: Postgres + pgvector, гибридный поиск (векторы + полнотекст)
     ├─ тулы: where_is / guide_to / look → действия робота
     └─ фильтры: шум, «не мне», служебный текст не озвучивается
   распознавание речи: Gemini Flash Lite (OpenRouter); «что это»: vision-модель
   голос: ElevenLabs v4 Turbo + нормализация чисел, дат, телефонов для ru / kk / en, кеш фраз
```

Все ключи (OpenRouter, ElevenLabs) — **только на сервере**, в APK их нет. Робот ходит на сервер по HTTPS с токеном.
Договорённость робот ↔ сервер — [`docs/api-contract.md`](docs/api-contract.md) (v1.4): сначала правим контракт, потом код.

## Технологии

| Часть | Стек |
|---|---|
| Приложение на роботе | Kotlin, Jetpack Compose, собственный движок лица на Canvas, AgentOS SDK 0.4.12, RobotAPI, OkHttp / Retrofit; minSdk 26, Java 11 |
| Сервер | Python 3.12, FastAPI, Pipecat (live-голос), OpenAI Agents SDK, SQLAlchemy + Alembic, uv |
| Модели | Claude Haiku 5.5 — агент; Gemini 3.1 Flash Lite / 3.5 Flash — речь и зрение; gemini-embedding-2 — поиск (всё через OpenRouter) |
| Голос | ElevenLabs `eleven_v4_turbo`, голос Dmitry (ru / kk / en) |
| Данные | PostgreSQL 16 + pgvector: база знаний, фрагменты для поиска, сессии и история, журнал вопросов, метрики |
| Инфраструктура | Docker Compose (api, postgres, caddy), Caddy — HTTPS, VPS; GitHub Actions — тесты, сборка APK, gitleaks |

## Структура репозитория

| Папка | Что внутри | Документация |
|---|---|---|
| `android-app/` | Приложение на роботе; сборки `emulator` (заглушки вместо железа), `robotos` (RobotOS 10.4), `robot` | [`android-app/README.md`](android-app/README.md) |
| `backend/` | Сервер: API, live-голос, агент, база знаний, eval | [`backend/README.md`](backend/README.md) |
| `backend/knowledge/` | База знаний: документы, факты, точки карты, частые вопросы, произношение | [`backend/README.md#база-знаний`](backend/README.md#база-знаний) |
| `infra/` | Docker Compose, Caddy, скрипты сервера и ADB | [`infra/README.md`](infra/README.md) |
| `docs/` | Контракт API, архитектура, подключение робота, дизайн, решения, документация OrionStar | ниже |
| `.github/workflows/` | CI: тесты сервера и приложения, сборка APK, gitleaks, деплой | [`infra/README.md`](infra/README.md#4-cicd-github-actions) |

## Запуск

**Сервер локально** (Python 3.12, [uv](https://docs.astral.sh/uv/), Docker для Postgres):
```bash
cd infra && cp -n .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres   # БД с pgvector
cd ../backend
cp .env.example .env               # ROBOT_TOKEN, OPENROUTER_API_KEY, ELEVENLABS_API_KEY, ...
uv sync && uv run python -m app.cli db upgrade
uv run python -m app.cli kb seed-yaml  # база знаний из backend/knowledge → Postgres
uv run python -m app.cli kb index      # фрагменты для поиска и эмбеддинги
uv run uvicorn app.main:app --reload --port 8000
```
Без ключей сервер работает на заглушках (Fake-провайдеры) — удобно для разработки и тестов.

**Приложение** (JDK 17+, Android SDK 36):
```bash
cd android-app
cp secrets.properties.example secrets.properties   # BACKEND_URL, ROBOT_TOKEN, DIAG_PIN (пароль сотрудника)
./gradlew assembleEmulatorDebug    # обычный эмулятор 1920×1080, заглушки вместо робота
./gradlew assembleRobotosDebug     # для робота
./gradlew publishTestApks          # оба APK → ~/astana-hub-apk/
```
APK для робота собирайте только из основной копии репозитория. В git worktree нет `secrets.properties`, и APK
получится без адреса сервера и токена: робот скажет «не могу говорить».

**Робот:** [`docs/robot-setup.md`](docs/robot-setup.md).
1. Поставить APK:
   - по ADB;
   - или через браузер робота: раздать папку с APK командой `python3 -m http.server 8090` и открыть адрес компьютера в той же Wi‑Fi сети.
2. Запустить из RobotOS Home.
3. Включить автозапуск: три пальца вниз → Настройки → Developer settings → Startup Programs.
4. Режим разговора `live` выбирается на экране диагностики или в `CHAT_MODE` при сборке.

**Продакшен-сервер:** [`infra/README.md`](infra/README.md).
- Код выкатывается на сервер в `/opt/astana-hub-robot`.
- Сборка и запуск: `cd infra && docker compose up -d --build api`.
- Остановить сервер, чтобы не тратить токены: `docker compose stop api`.
- Запустить снова: `docker compose start api`.

## Проверки

```bash
cd backend && uv run pytest && uv run ruff check          # 956 тестов; нужен Postgres для тестов, см. tests/conftest.py
cd android-app && ./gradlew testEmulatorDebugUnitTest testRobotosDebugUnitTest   # 1795 тестов
```
Проверки на настоящей модели (`backend/eval/`):
- RAG-вопросы;
- характер робота;
- устойчивость к prompt injection;
- «не мне»: на 20 настоящих вопросах ни одного ложного молчания.

## Стоимость эксплуатации

Замеры октября 2026, 22 рабочих дня:
- один ответ — около $0.001 OpenRouter;
- голос — около 20 кредитов ElevenLabs на ответ.

| Нагрузка | OpenRouter | ElevenLabs | VPS | Итого в месяц |
|---|---|---|---|---|
| 50 ответов в день | ~$1–2 | $6 (Starter, 40k кредитов) | 3–4 тыс. ₸ | ≈ 7–8,5 тыс. ₸ |
| 150 ответов в день | ~$3–5 | $22 (Creator) | 3–4 тыс. ₸ | ≈ 16–18 тыс. ₸ |
| 400 ответов в день | ~$9–13 | $22–99 | 3–4 тыс. ₸ | ≈ 19–62 тыс. ₸ |

Следите за лимитом на ключе OpenRouter и за кредитами ElevenLabs. Когда кредиты ElevenLabs кончаются, робот
переходит на запасной голос через OpenRouter.

## Известные ограничения

- **Выход по ×** закрывает приложение, но если оно назначено в Startup Programs, RobotOS сразу запускает его снова.
  Чтобы выйти надолго, снимите приложение с автозапуска.
- **«Не мне»** отсекает примерно 2/3 чужих разговоров. Остальное закрывают сон по умолчанию и перебивание только по «стоп».
- **Встроенные ASR и TTS AgentOS** не поддерживают русский и казахский, поэтому распознавание и голос — свои, на сервере.
  Без интернета работают только «где X» и проводы.

## Документация

- [`docs/api-contract.md`](docs/api-contract.md) — контракт робот ↔ сервер: REST, SSE, WebSocket live, история, метрики.
- [`docs/architecture.md`](docs/architecture.md) — компоненты, сценарии, сборки, провайдеры.
- [`docs/robot-setup.md`](docs/robot-setup.md), [`docs/server-setup.md`](docs/server-setup.md) — подключение робота и сервера.
- [`docs/design/robot-mvp.html`](docs/design/robot-mvp.html) — прототип интерфейса, по которому сделан экран: лицо, страница, костюмы, слои. Открывается в браузере.
- [`docs/ui-redesign/`](docs/ui-redesign/) — как интерфейс связан с логикой (`INTEGRATION.md`), ход работ и решения по отличиям от прототипа.
- [`docs/stack.md`](docs/stack.md), [`docs/decisions/`](docs/decisions/), [`docs/context.md`](docs/context.md) — стек, принятые решения, факты о платформе.
- [`docs/db-schema.md`](docs/db-schema.md) — схема БД.
- [`docs/vendor/`](docs/vendor/) — документация OrionStar: AgentOS SDK, RobotAPI, деплой, FAQ.
- [`docs/roadmap.md`](docs/roadmap.md), [`docs/tasks.md`](docs/tasks.md) — история планирования MVP.

## Правила разработки

- Секреты (`backend/.env`, `infra/.env`, `android-app/secrets.properties`, `local.properties`, keystore) в git не
  попадают, шаблоны — `*.example`. В CI работает gitleaks.
- Изменения на стыке робот ↔ сервер — сначала в `docs/api-contract.md`, потом в коде.
- На роботе приложение запускается только через RobotOS Home, иначе RobotAPI не даёт доступа к железу.
  Hot Reload и Apply Changes не используем.
