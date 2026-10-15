# Astana Hub Robot — Android-приложение

Приложение для робота OrionStar GreetingBot Nova (AgentOS SDK `com.orionstar.agent:sdk:0.4.12-SNAPSHOT`).
Стек и решения: `../docs/stack.md`, контекст: `../docs/context.md`.

## Требования
- Android Studio 2026.2 (Rabbit) или новее; Gradle JDK — 17+ (подойдёт встроенный JBR).
- Android SDK: `platforms;android-36`, `build-tools;35.0.0`, `platform-tools`.
- Сеть до `https://npm.ainirobot.com/repository/maven-public/` (репозиторий OrionStar, логин/пароль
  публичные из доки вендора, прописаны в `settings.gradle.kts`).

| Компонент | Версия |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 |
| compileSdk / minSdk / targetSdk | 36 / 26 / 34 (targetSdk уточнить по прошивке робота) |
| Build Tools | 35.0.0 |
| Байткод | Java 11 (`jvmTarget = 11`) |

## Первый запуск
1. Android Studio → **Open** → папка `android-app/` (не корень репозитория).
2. Если Studio не создала `local.properties`, создайте его: `sdk.dir=/home/<user>/Android/Sdk`.
3. Скопируйте `secrets.properties.example` → `secrets.properties` и заполните (можно оставить пустым —
   сборка пройдёт, в `BuildConfig` будут пустые строки):
   - `AGENT_APP_ID` — appId из панели OrionStar; из него при сборке генерируется
     `assets/actionRegistry.json` (сам файл в git не хранится);
   - `ROBOT_TOKEN` — токен робота для backend;
   - `BACKEND_URL` — адрес backend.
4. Build Variants → выберите `emulatorDebug` (обычный эмулятор, заглушки) или `robotDebug` (робот).

## Сборка и тесты из консоли
```bash
cd android-app
./gradlew assembleEmulatorDebug        # app/build/outputs/apk/emulator/debug/
./gradlew assembleRobotDebug           # app/build/outputs/apk/robot/debug/
./gradlew assembleRobotosDebug         # app/build/outputs/apk/robotos/debug/
./gradlew testEmulatorDebugUnitTest    # JUnit4 + MockK + Turbine + MockWebServer
./gradlew publishTestApks              # emulator + robotos → ~/astana-hub-apk/ (раздаётся роботу по http)
```

## Backend
- `BACKEND_URL` пуст: flavor `emulator` использует `FakeBackendApi` (шаблонные ответы),
  flavor `robot` — честное «нет связи».
- Локальный backend с эмулятора: `BACKEND_URL=http://10.0.2.2:8000/v1` (HTTP разрешён только в debug,
  `src/debug/res/xml/network_security_config.xml`).
- Контракт: `../docs/api-contract.md`. Таймауты клиента: `/ask` 8 с, `/look` 12 с, справочники 5 с.

## Голос
Настройка (secrets `VOICE_ASR`, `VOICE_TTS_RU/KK/EN`: `builtin` | `server`; на роботе без пересборки —
**долгое нажатие на «Astana Hub»** → экран диагностики). По умолчанию во всех flavor-ах:
- распознавание — `server` (наш микрофон → VAD → `/v1/stt`), встроенный ASR робота — опция;
- синтез: ru и en — встроенный (robotos: SkillApi.playText; robot: AgentOS; emulator: Android TTS),
  kk — `/v1/tts`; при `503 speech_unavailable` или `X-Speech-Provider: fake` — встроенный.

Экран диагностики: тесты TTS (встроенный / серверный / Android) RU/KZ/EN, лог встроенного ASR и серверного STT,
уровень микрофона, статус RobotApi, дамп `getPlaceList` / `getPosition`, `/v1/places`, масштаб интерфейса, метрики экрана.

## «Где X?» и «Проводи»
Работают без сети: `/v1/places` (ETag) + кеш `files/places.json` + точки карты робота. Фраза → место
(`PlaceMatcher`: names/synonyms/map_name, казахские буквы, падежи, опечатки) → сторона и расстояние от
`getPosition` (`Directions`, посетитель считается стоящим лицом к роботу) → фраза RU/KZ/EN (`WayPhrases`).
Кнопка «Проводить» / «проводи до …» → `startNavigation`, «Стоп» отменяет. Не узнали место → вопрос в `/v1/ask`.

## Заставка
После `SCREENSAVER_SECONDS` (по умолчанию 60) бездействия — заставка, сброс сессии и языка; касание или голос — назад.

## Структура
```
app/src/main/java/kz/astanahub/robot/
  AstanaHubApp.kt, MainActivity.kt      Application (DI) и экран-киоск
  di/AppContainer.kt                    ручной DI, выбор BackendApi
  core/                                 Lang, PhraseSplitter (≤300 символов, без ссылок), IntentRouter, фразы RU/KZ/EN
  voice/ robot/ camera/                 интерфейсы VoiceGateway, RobotGateway, CameraGateway
  backend/                              BackendApi, RetrofitBackendApi, FakeBackendApi, DTO
  ui/MainViewModel.kt                   MVVM + StateFlow
app/src/emulator/.../fake/              заглушки голоса, карты, камеры
app/src/robot/.../agent/                AppAgent, AgentOS-голос, RobotAPI-карта, SurfaceShareApi-камера
```

## Flavors
| Flavor | Назначение | `BuildConfig.IS_ROBOT` |
|---|---|---|
| `emulator` | обычный Android-эмулятор, железо робота подменено заглушками | `false` |
| `robot` | робот с AgentOS V12.6: AgentOS SDK, RobotAPI, камера | `true` |
| `robotos` | робот с RobotOS 10.4 (без AgentOS): `libs/robotos/robotservice.jar` из OrionStar RobotSample — RobotApi, SkillApi, SurfaceShareApi | `true` |

Код, общий для обоих — `app/src/main`, специфичный — `app/src/emulator` / `app/src/robot`.

## Секреты
`secrets.properties` и `local.properties` — вне git. Ключи LLM в APK не попадают никогда:
они только на сервере.
