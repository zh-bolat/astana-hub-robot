# Astana Hub Robot

Голосовой помощник Astana Hub на роботе OrionStar GreetingBot Nova (экран 14", RobotOS 10.4):

- отвечает на вопросы о хабе на русском, казахском и английском по базе знаний;
- на вопрос «Где X?» называет направление и расстояние по карте робота, может проводить до точки;
- на вопрос «Что это?» делает снимок камерой и описывает помещение;
- встречает посетителей и запускается сам при включении робота.

## Статус (MVP, октябрь 2026)

| Функция | Код | Проверено на роботе |
|---|---|---|
| Ответы по базе знаний (Gemini, RU/KZ/EN) | ✅ | ✅ текстом и кнопками |
| Потоковая выдача (SSE): сервер — готово, приложение пока ждёт полный ответ | частично | — |
| Распознавание речи на сервере (Gemini) | ✅ | ⏳ |
| Озвучка: RU/EN встроенным голосом робота, KZ через ElevenLabs | ✅ | ⏳ нужен ключ ElevenLabs |
| Агентский чат (OpenAI Agents SDK + Haiku 5.5 + ElevenLabs) | ⏳ решение принято | — |
| «Где X?», «Проводи», «Где я?» без интернета | ✅ | ⏳ нужна карта с точками |
| «Что это?» (камера → vision-модель) | ✅ | ⏳ |
| Интерфейс-киоск под 14", заставка, автозапуск | ✅ | частично (масштаб) |
| База знаний из Google-таблицы | ✅ | — нужна таблица |
| Доставка: Docker, HTTPS, CI | ✅ конфиги | ⏳ нет VPS |

Подробный план: [`docs/roadmap.md`](docs/roadmap.md).

## Как это устроено

```
Посетитель → микрофон робота → Android-приложение (android-app/)
   ├─ «где X / проводи / где я» → карта и навигация робота (RobotAPI), без сети
   ├─ вопрос о хабе             → backend /v1/ask  → Gemini по базе знаний → текст
   ├─ «что это»                 → кадр камеры → backend /v1/look → Gemini vision
   └─ речь                      → backend /v1/stt (Gemini); озвучка: встроенный TTS
                                  робота или backend /v1/tts (ElevenLabs)
Ответ → голос робота + субтитры на экране
```

Ключи LLM и голосовых сервисов хранятся только на сервере, в APK их нет. Подробнее: [`docs/architecture.md`](docs/architecture.md).

## Агентский чат: утверждённый стек
Решение от 2026-10-21, подробности и условия: [`docs/decisions/0001-agent-chat-stack.md`](docs/decisions/0001-agent-chat-stack.md).

| Слой | Выбор |
|---|---|
| Каркас агента | **OpenAI Agents SDK** (в `backend/`, трассировка в OpenAI выключена) |
| Модель (агент + зрение) | **Claude Haiku 5.5** через `LitellmModel` |
| Голос | **ElevenLabs** (`eleven_v3` для казахского, `eleven_flash_v2_5` для RU/EN) |
| Распознавание речи | Gemini (без изменений) |
| Тулы | база знаний, «где X», «проводи», камера — только наши функции |
| Память | Postgres: `sessions` / `messages` |
| API | новый `/v1/chat`; `/v1/ask` остаётся запасным |

Статус: решение принято, реализация не начата. Нужны ключи Anthropic и ElevenLabs.

## Структура репозитория

| Папка | Что внутри | Документация |
|---|---|---|
| `android-app/` | Приложение на роботе: Kotlin, XML Views, MVVM; варианты сборки `emulator` / `robotos` / `robot` | [`android-app/README.md`](android-app/README.md) |
| `backend/` | Сервер: Python 3.12, FastAPI, Gemini, голос, база знаний | [`backend/README.md`](backend/README.md) |
| `infra/` | Docker Compose, Caddy (HTTPS), скрипты VPS и ADB | [`infra/README.md`](infra/README.md) |
| `.github/workflows/` | CI: тесты, сборка APK, gitleaks, деплой по тегу | [`infra/README.md`](infra/README.md#4-cicd-github-actions) |
| `docs/` | Контракт API, архитектура, подключение робота, стек, roadmap, документация вендора | см. ниже |

### Документы

- [`docs/api-contract.md`](docs/api-contract.md): контракт робот ↔ сервер. Это источник истины для обеих сторон.
- [`docs/architecture.md`](docs/architecture.md): компоненты, сценарии, варианты сборки, провайдеры.
- [`docs/frontend-chat.md`](docs/frontend-chat.md): чат на экране, голос и прерывания — как приложению работать с сервером.
- [`docs/robot-setup.md`](docs/robot-setup.md): как подключить робота, поставить APK и проверить его через экран диагностики.
- [`docs/stack.md`](docs/stack.md): утверждённый стек и причины выбора.
- [`docs/roadmap.md`](docs/roadmap.md): этапы и шаги с критериями готовности.
- [`docs/context.md`](docs/context.md): факты о роботе и платформе, принятые решения.
- [`docs/vendor/`](docs/vendor/): документация OrionStar (AgentOS SDK, RobotAPI, деплой, FAQ).

## Быстрый старт

**Сервер** (нужны Python 3.12 и [uv](https://docs.astral.sh/uv/)):
```bash
cd backend
uv sync
cp .env.example .env        # указать ROBOT_TOKEN и GEMINI_API_KEY
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Без `GEMINI_API_KEY` сервер работает на заглушке: ищет ответ по ключевым словам.

**Приложение** (Android Studio или JDK 17+ и Android SDK):
```bash
cd android-app
cp secrets.properties.example secrets.properties   # ROBOT_TOKEN как на сервере, BACKEND_URL
./gradlew assembleEmulatorDebug    # для эмулятора: заглушки вместо железа робота
./gradlew assembleRobotosDebug     # для робота на RobotOS 10.4
./gradlew publishTestApks          # собрать оба и скопировать в ~/astana-hub-apk/
```

**Робот**: см. [`docs/robot-setup.md`](docs/robot-setup.md). Если ADB нет, APK ставится через браузер робота, а адрес сервера меняется на экране диагностики без пересборки.

**Тесты:**
```bash
cd backend && uv run pytest && uv run ruff check
cd android-app && ./gradlew testEmulatorDebugUnitTest testRobotosDebugUnitTest
```

## Правила

- Секреты (`backend/.env`, `android-app/secrets.properties`, `local.properties`, keystore) **никогда не коммитим**, шаблоны лежат в `*.example`. В CI работает gitleaks.
- Изменение контракта робот ↔ сервер: сначала правим `docs/api-contract.md`, потом код.
- Работаем в ветках, в `main` — через pull request.
- На роботе приложение запускается только через RobotOS Home (список приложений робота), иначе RobotAPI не даст доступа к железу. Hot Reload / Apply Changes не используем.

## Что нужно для пилота

- платный ключ Gemini: бесплатный упирается в лимит запросов (`429`);
- ключ ElevenLabs: казахский голос есть только у модели `eleven_v3`;
- Google-таблица с базой знаний от Astana Hub (шаблоны: `backend/knowledge/sheet_template/`);
- карта здания с точками на роботе;
- VPS в Европе и домен (в некоторых сетях API Gemini заблокирован, сервер за рубежом это решает);
- от OrionStar: `robotservice.jar` под RobotOS 10.4 (сейчас используется публичный из RobotSample), ответ про ASR/TTS на RU/KZ, доступ к облачной панели ORM/EO.
