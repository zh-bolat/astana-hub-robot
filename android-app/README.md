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
- Экран диагностики: удержание логотипа ≥ 3 с → PIN (`DIAG_PIN` в secrets, в APK только хеш).
  5 неверных попыток → блокировка 5 мин. Без `DIAG_PIN` на robot/robotos диагностика недоступна, на emulator — без PIN.
- Адрес сервера с экрана диагностики: только https на хост `BACKEND_URL` или из `BACKEND_ALLOWED_HOSTS`;
  в debug ещё http на приватные IP (10.x, 172.16–31.x, 192.168.x). `/health` вызывается без токена.
- Контракт: `../docs/api-contract.md`. Таймауты клиента: `/ask` 8 с, `/look` 12 с, справочники 5 с.

## Голос
Настройка (secrets `VOICE_ASR`, `VOICE_TTS_RU/KK/EN`: `builtin` | `server`; на роботе без пересборки —
**удержание «Astana Hub» ≥ 3 с + PIN** → экран диагностики). По умолчанию во всех flavor-ах:
- распознавание — `server` (live `WS /v1/stt/stream`, конец фразы — свой VAD после `VAD_END_SILENCE_MS` = 500 мс
  тишины, шлём `end`), встроенный ASR робота — только если выбран явно;
- синтез — только голос ElevenLabs через `/v1/tts` на всех языках, потоково (`?stream=true`, `TTS_STREAMING`):
  MediaPlayer читает растущий буфер (`GrowingAudio` → MediaDataSource), обрыв потока — дозапрос фразы без stream;
- сбой `/v1/tts` (ошибка, таймаут, 503, `X-Speech-Provider: fake`) → 1 повтор → текст субтитрами без звука +
  «не могу сейчас говорить» из кеша (не чаще раза в минуту). Встроенный голос — только при `VOICE_BUILTIN_FALLBACK=true`;
- кеш голоса: фразы, которые робот говорит сам (`PhraseKey`, фиксированные фразы навигации),
  синтезируются фоном при старте, смене сервера и восстановлении связи и лежат в `filesDir/voice_cache` — играются без сети;
- встроенный ассистент заглушён, пока приложение на экране (robotos: `SkillApi.setRecognizable(false)`; robot:
  `AgentCore.isMicrophoneMuted/isDisablePlan`), в фоне — возвращается как было;
- быстрый отклик: лицо «думает» сразу по концу речи и пока ждём ответ; заполнителей голосом («хм…», «секунду…»)
  нет; тайминги хода уходят в `POST /v1/metrics/turn`;
- приложение ушло с экрана — постоянное прослушивание на паузе и live закрыт; вернулось — включается снова.

Экран диагностики: тесты TTS (встроенный / серверный / Android) RU/KZ/EN, лог встроенного ASR и серверного STT,
уровень микрофона, статус RobotApi, дамп `getPlaceList` / `getPosition`, `/v1/places`, масштаб интерфейса, метрики экрана.
Навигация без adb: список точек карты робота с кнопкой «Поехать» у каждой (startNavigation напрямую) и «Стоп»;
**журнал навигации** (`robot/NavJournal`, в памяти процесса — переживает закрытие экрана): запрос, rc
startNavigation, статусы/коды колбэков RobotApi с именами, итог, ошибки, `isRobotEstimate`, а также решения по
live-действию `guide_to` (turn_id, точка, «еду / жду playback_done / игнор»).

## «Где X?» и «Проводи»
Работают без сети: `/v1/places` (ETag) + кеш `files/places.json` + точки карты робота. Фраза → место
(`PlaceMatcher`: names/synonyms/map_name, казахские буквы, падежи, опечатки) → сторона и расстояние от
`getPosition` (`Directions`, посетитель считается стоящим лицом к роботу) → фраза RU/KZ/EN (`WayPhrases`).
Кнопка «Проводить» / «проводи до …» → `startNavigation`, «Стоп» отменяет. Не узнали место → вопрос в `/v1/ask`.

## Заставка
После `SCREENSAVER_SECONDS` (по умолчанию 180 = 3 мин) бездействия — лицо зевает и засыпает, сессия и язык сбрасываются; касание, голос (если рядом человек или нет PersonApi) или подошедший человек — бодрое пробуждение.

## Агентский чат и «живой» робот
`CHAT_MODE` (secrets или диагностика): `agent` (по умолчанию) — реплики идут в `POST /v1/chat` (SSE, `chat/`),
`legacy` — прежний путь (локальные правила + `/v1/ask`). Запрос как у `/ask` (`question`, `lang`, `session_id`,
`place_id`) + `event: visitor_arrived`; SSE: `emotion`, `action {type, place_id}` (до фраз, которые его описывают),
`phrase`, `done`/`error`. Нет первой фразы или действия за 8 с (приветствие — 2 с), ошибка до первого ответа или нет
связи → запасной путь `/v1/ask` + `IntentRouter`. Действия: `where_is` (направление по `WayFinder` голосом и на экране,
кнопка «Проводить», не едем), `guide_to` (едем после последней фразы ответа), `look` (кадр → `POST /v1/look` с
репликой посетителя, описание звучит после фраз агента).

Режимы экрана (`ScreenMode`): SLEEP — спящее лицо; FACE — лицо на весь экран, крупные субтитры, кнопки частых
вопросов; MENU — прежние плитки (кнопка «Меню» или жест вверх по лицу, без касаний 30 с — назад к лицу).
Эмоции агента (`neutral/happy/thinking/surprised/sad/excited`) и события на роботе меняют выражение лица.

Пробуждение (`presence/`): PersonApi, человек ≤ 2.5 м дольше 0.7 с → FACE и приветствие (`visitor_arrived`,
нет фразы за 2 с — своя); тот же id — не чаще раза в 5 мин; никого ближе 3 м 15 с → SLEEP. Голова
(`robot/Head.kt`): focus follow собеседника, выключено во время навигации и снимка, `resetHead` при уходе.
На эмуляторе «подошёл / ушёл» — кнопки на экране диагностики (сам «уходит» через 90 с).

## Живой голосовой режим (`CHAT_MODE=live`, `live/`)
`WS /v1/live` (docs/api-contract.md): включается в диагностике (кнопка `live`) или `CHAT_MODE=live`; по умолчанию — `agent`.
Сессия открывается, когда робот просыпается (человек / касание; при подходе — `visitor_arrived`), закрывается во сне.
Микрофон (`AndroidLiveMicrophone`: VOICE_COMMUNICATION + AcousticEchoCanceler/NoiseSuppressor, кадры 40 мс PCM16 16 кГц)
уходит на сервер непрерывно; `hello.aec` — реально ли включён AEC. Звук ответа (`StreamPlayer`, AudioTrack MODE_STREAM,
формат из `ready`) играет сразу, джиттер-буфер 120 мс; `interrupted` / «Стоп» — мгновенно pause+flush, хвост хода
отбрасывается; `playback_done` — когда реально доиграло. Без AEC «слышу» на собственный голос не показывается.
Действия: `where_is` — направление только на экране (говорит сервер), `guide_to` — после `playback_done` хода (без
своей фразы старта), `look` — кадр → `POST /v1/look`, описание на экране; сервер не начал ход за 4 с — робот озвучивает
сам. Своя озвучка робота мимо сервера (фразы навигации) — вместо микрофона уходит тишина. Сбой (сеть, `error`, нет
`ready` за 5 с, сервер без `/v1/live`) → режим agent с той же `session_id` (недоотвеченная реплика уходит в `/v1/chat`),
повтор live через 30 с. Метрики: `POST /v1/metrics/turn` с `mode: live`, все `*_ms` — от прихода `user_final`.

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
