# Контекст проекта (перенесено из первой сессии, 2026-10-15)

## Робот: OrionStar GreetingBot Nova
- Экран 14" FHD, камера 13 Мп в голове, 3D LiDAR (170°, 240° по горизонтали) + RGBD.
- 12–14 ч работы, автозарядка при < 10%.
- Встроенное «зрение» — только люди и лица. Объекты/помещения не распознаёт.
- Лидар из API доступен только как 2D-точки препятствий (`setNavigationLineData` + `STATUS_LINE_DATA`), не облако 3D.

## Ключевые API
### AgentOS SDK (docs/vendor/AgentOS_SDK_Doc_v0.4.12_en.md)
- `AppAgent` (один на приложение) в `Application.onCreate`: `setPersona`, `setObjective`, `onExecuteAction`.
- `PageAgent` — Actions на уровне экрана.
- Actions: LLM робота сама выбирает Action по описанию; `onExecute` получает параметры.
- Встроенный `Actions.KNOWLEDGE_QA` — база знаний из панели управления OrionStar.
- `setOnTranscribeListener` → `onASRResult(transcription)` (`.text`, `.final`), `onTTSResult`.
- `AgentCore.isDisablePlan = true` — отключить планирование LLM робота, обрабатывать запросы самим.
- `AgentCore.llmSync/llm` — прямой вызов встроенной LLM; `AgentCore.query(text)` — текстовый запрос как голосовой.
- `uploadInterfaceInfo` — сообщить LLM, что на экране.
- В AgentOS старые ASR/TTS/NLP из RobotAPI отключены.

### RobotAPI (docs/vendor/RobotAPI_en.md)
- Карта: `getPosition`, `getPlaceList` (name, x, y, theta, status), `isRobotInLocations`, `getMapName`.
- Навигация к точке / координатам, лифты, турникеты.
- Камера: `SurfaceShareApi` (startRequest / onImageAvailable / stopPushStream), 640×480 YUV, не мешает встроенному зрению. Закрывать после использования (OOM). Прямое открытие через Android API временно выключает зрение робота.
- Нельзя одновременно управлять одним устройством двумя API (напр. поворот во время навигации).

### Старый RobotOS SkillApi (fallback, если AgentOS недоступна)
- `skillApi.playText(text, listener)` (≤300 симв., макс 1000), `onQueryAsrResult`, `queryByText`, `setRecognizeMode`.
- Запись звука: 16 кГц, стерео 16-bit PCM, левый канал.

## Подключение и деплой (docs/vendor/APP_DEPLOYMENT_cn.md)
1. Свайп одним пальцем вниз → много раз тапнуть по времени → динамический пароль (по SN у поставщика или https://wp.orionstar.com/public/dynpass/ для партнёров).
2. Включить «Enable debugging» + «Persistent debugging», перезагрузить.
3. USB micro-USB на голове или `adb connect <IP>:5555`.
4. Gradle: Maven `https://npm.ainirobot.com/repository/maven-public/` (логин/пароль `agentMaven`/`agentMaven`), dependency `com.orionstar.agent:sdk:0.4.12-SNAPSHOT`.
5. `app/src/main/assets/actionRegistry.json` с `appId` из панели (https://jiedai.ainirobot.com/...).
6. Запуск — только через RobotOS Home. Не использовать Hot Reload / Apply Changes.
7. Автозапуск: intent-filter `action.orionstar.default.app` + настройка (свайп тремя пальцами → Settings → Developer settings).
8. Удалённая установка — через облачную панель (ORM/EO).

## Решения
- Ответы «где X» — по карте робота (офлайн, надёжно), не по камере.
- «Что это» — камера + vision LLM на сервере, с подсказкой текущей точки на карте.
- База знаний — своя, на сервере (RAG), редактируемая без перевыпуска APK.
- Стек утверждён 2026-10-15 (`docs/stack.md`): Android — Kotlin + XML Views; backend — Python/FastAPI + Gemini (текст и vision), DeepSeek как альтернатива для текста; база знаний — Google-таблица → Postgres; хостинг — зарубежный VPS. План работ — `docs/roadmap.md`.
- При отсутствии RU/KZ в ASR/TTS робота — сторонние сервисы (Yandex SpeechKit / Google / Azure), если удастся получить сырой звук.

## Факты с первого визита к роботу (2026-10-15)
- Прошивка робота: **OrionStar OS 10.4 OS-R-SG** (RobotOS), не AgentOS V12.6 → AgentOS SDK не работает. Используем flavor `robotos` с `robotservice.jar` из официального примера [OrionStarGIT/RobotSample](https://github.com/OrionStarGIT/RobotSample) (RobotOS-Version 5.2.2026051505): RobotApi, SkillApi, SurfaceShareApi. Версию jar под 10.4 подтвердить у OrionStar.
- У робота есть встроенный синтез речи на русском → RU/EN озвучивает робот, KZ — сервер (ElevenLabs `eleven_v3`, единственная их модель с казахским).
- Распознавание речи — на сервере через Gemini (`gemini-3.1-flash-lite`, JSON `{lang, text}`), проверено на RU/KZ/EN.
- Gemini: доступны `gemini-3.1-flash-lite` (основная, ~2 с) и `gemini-3.5-flash` (резерв, часто 503); `gemini-2.5-flash` недоступна новым пользователям. Бесплатный ключ упирается в 429 при серии запросов → для пилота нужен платный.
- В одной из сетей API Gemini (`generativelanguage.googleapis.com`) обрывался на TLS-рукопожатии, в сети хаба — работает. В эксплуатации — сервер на VPS в Европе.
- ADB по Wi-Fi на роботе изначально закрыт (порт 5555 refused); открытые порты робота 6670/6671/7890/9191 — не ADB. APK удалось поставить через браузер робота с HTTP-сервера на ноутбуке.
- Интерфейс по умолчанию был слишком крупным на 14" → масштаб считается от ширины экрана (1600 dp на 1920 px) + ползунок на экране диагностики.
- Подробно: `docs/robot-setup.md`, `docs/architecture.md`.

## Ссылки
- ТЗ: https://claude.ai/code/artifact/8fa31ed9-94b4-4d7e-8909-f90be8e07ab1
- SDK репозиторий: https://github.com/orionagent/agentos-sdk
- RobotOS docs: https://doc.orionstar.com/en/knowledge-base/get-started/
- Облачный OpenAPI: https://global-openapi.orionstar.com/
- Nova: https://en.orionstar.com/nova.html · AgentOS: https://en.orionstar.com/agentos.html
- Контакт OrionStar: global_business@orionstar.com
