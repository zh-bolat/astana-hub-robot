# Новый UI ↔ приложение, сервер и робот

Полная таблица по разделу брифа «Связь с бэкендом и инфрой»: каждое событие и API (`docs/api-contract.md`) →
что делает новый интерфейс → где в коде → чем проверено. Бизнес-логика (`MainViewModel`, live, voice, robot,
places, backend) не переписана: новый UI читает `MainViewModel.state` и `MainViewModel.faceCues`, звук — из
`AudioLevels`. Ничего из прежнего экрана не потеряно — раздел «Экран 2».

Статус: **M1** — экран 1 (лицо); **M2** — интерактив (тап, удержание → админка, поглаживание, ручка, сон 3 мин);
**M3** — экран 2 (чат с живыми субтитрами и источниками, плитки, частые вопросы, язык, управление) вместо прежнего
синего; **M4** — режим поездки (остров «куда + Остановить», итог), остров where_is на лице, полировка.

Пути: `ui/` = `android-app/app/src/main/java/kz/astanahub/robot/ui/`; тесты — `android-app/app/src/test/…`
(общие) и `src/testRobotos/…` (RobotAPI робота). «Запись» — `.claude/ui-redesign/shots/<этап>/`.

## 1. Как состояние приложения попадает на экраны

```
live / chat / ask / voice / robot / presence ─► MainViewModel ─► MainUiState ─┬─► экран 1: FaceController (лицо),
                                       └─► faceCues (реакции) ─────────────────┤   IslandModel (остров)
AudioLevels (микрофон, VAD, речь робота) ──────────────────────────────────────┤─► экран 2: ChatLog, ScreenTwoMapper
                                                                               ▼
MainActivity.render → FaceInputMapper → FaceInputs → кадр: FaceEngine → FaceDirector → пружины → FaceRenderer
                    → ScreenTwoState (Compose) · FaceScreenState (переход, остров, ручка)
```

| Вход | Откуда | Где | Тест |
|---|---|---|---|
| лицо: `awake`, `activity`, `emotion`, `userSpeaking` | `MainUiState.mode / phase / emotion / partialQuestion` | `ui/face/FaceInputMapper.kt` | `FaceInputMapperTest` |
| лицо: реакции | `MainViewModel.faceCues` (`cue`, `troubleCue`, `tripCue`) | `ui/MainViewModel.kt` → `FaceDirector.cue` | `MainViewModelFaceCueTest`, `FaceDirectorTest` |
| посетитель говорит (**VAD**, первичный сигнал) | VAD приложения (`EnergyVad`, `LiveEndpointer`), сервера (`user_speaking` → `user_final`); гаснет через 1 с / 8 с без «тишины» | `audio/AudioLevels.kt`, `live/LiveSession.kt` | `AudioLevelsTest`, `LiveSessionTest.server VAD…` |
| громкость голоса / речи робота | RMS микрофона (фон вычитается, не догоняет монолог); RMS реально звучащего PCM (live), `Visualizer` (MP3), синтетические слоги | `audio/AudioLevels.kt`, `live/StreamPlayer.kt`, `audio/PlaybackVisualizer.kt`, `ui/face/FaceAudio.kt` | `AudioLevelsTest`, `FaceAudioEnvelopeTest`, `FaceEngineTest` |
| касания лица | `FaceGestureRecognizer` → `FaceInputs.touching/hold/petting/stroke` | `ui/face/FaceGestures.kt`, `FaceScreen.kt` | `FaceGestureRecognizerTest`, `FaceEngineTest` |
| экран 1 ↔ 2 | `ScreenTwoMapper.menuOpen` (MENU, не едем, остров не досказывает поездку) | `ui/screen/ScreenTwoMapper.kt`, `RobotScreens.kt` | `ScreenTwoMapperTest`, `ScreenSwipeTest` |
| чат экрана 2 | ходы ViewModel (`question`, `answer`, `subtitle`, `citations`, `conversation`) | `ui/screen/ChatLog.kt` | `ChatLogTest`, `MainViewModelScreenTwoTest` |
| остров экрана 1 | `guidingTo`, `whereIs`, реакции итога поездки | `ui/face/Island.kt` (`IslandModel`) | `IslandModelTest` |

## 2. Live `WS /v1/live`

### Сервер → робот

| Событие | Что делает UI | Где | Тест |
|---|---|---|---|
| `ready` | формат звука для плеера; `offline` снят; после него — `visitor_arrived`, `state.directions` | `LiveSession`, `MainViewModel.onLive` | `LiveSessionTest.hello first, visitor_arrived after ready…`, `LiveProtocolTest.audio format from ready` |
| `listening` | фаза LISTENING → лицо в покое (микрофон открыт всегда — это не «слушаю») | `FaceDirector` | `FaceDirectorTest.activities map to moods` |
| `user_speaking` | VAD сервера → лицо «слушает»; спит — будит (если рядом человек или нет PersonApi) | `LiveSession` → `AudioLevels.reportVoice(sticky)`, `MainViewModel.onVisitorVoice` | `LiveSessionTest.server VAD drives the face…`, `MainViewModelSleepTest` |
| `user_partial` | лицо — внимательный взгляд; экран 2 — полупрозрачный пузырь посетителя «…» | `FaceInputMapper`, `ScreenTwo.Chat` | `FaceDirectorTest.visitor partial text…`, `ScreenTwoMapperTest.partial…` |
| `user_final` | реплика посетителя в чате; «стоп» в поездке — стоп; «да» на «Проводить?» — едем сразу | `MainViewModel.onLiveUserFinal`, `ChatLog` | `MainViewModelLiveTest.local yes to the guide offer…`, `ChatLogTest` |
| `ignored` | без реакции; отозванная реплика убирается из чата, экран — как был | `MainViewModel.onLiveIgnored`, `ChatLog` | `MainViewModelLiveTest.ignored - no reaction…`, `ChatLogTest.noise retracted…` |
| `thinking` | лицо THINKING; чат — «…» | `Expressions.THINKING`, `ScreenTwo.TypingDots` | `ExpressionsTest`, `MainViewModelLiveTest.full turn…` |
| `turn_start` | ход начат (служебное) | `LiveSession` | `LiveSessionTest.full turn…` |
| `emotion` | эмоция поверх состояния лица (и мини-лица) | `Expressions.overlay` | `ExpressionsTest`, `FaceEngineTest` |
| `phrase` | растущая реплика робота в чате, живые субтитры | `MainViewModel` (`answer`, `subtitle`) → `ChatLog`, `ScreenTwo.RobotLine` | `ChatLogTest`, `MainViewModelLiveTest.full turn…`; запись `M3/m3_chat_live.mp4` |
| бинарный звук | плеер с джиттер-буфером; рот — по громкости реально звучащего | `live/StreamPlayer.kt` → `PcmPlaybackMeter` | `LiveSessionTest.full turn - audio plays after jitter buffer…`, `AudioLevelsTest.live pcm level…` |
| `action where_is` (+`turn_id`) | направление по карте робота: **остров where_is на лице** (стрелка, место, расстояние, «Проводить»; ~9 с; глаза в сторону места); экран 2 — строка направления и «Проводить: …» | `MainViewModel.liveWhereIs` → `WhereIsHint` → `IslandModel`, `IslandView`; `ScreenTwo.Composer` | `MainViewModelLiveTest.where_is - direction shown on screen…`, `IslandModelTest`, `FaceEngineTest.where_is island…` |
| `action guide_to` (+`turn_id`) | поездка после `playback_done` своего хода; экран 1 сам, **остров «Иду к: Кафе · Остановить»**; итог — коротко на острове и на лице | `MainViewModel.onLiveAction/startGuide`, `RobotScreens`, `IslandModel` | `MainViewModelLiveTest.guide_to starts navigation only after playback_done…`, `.real order…`, `IslandModelTest` |
| `action look` (+`turn_id`) | снимок → `/v1/look`; лицо LOOKING; описание — в чате | `MainViewModel.liveLook` | `MainViewModelLiveTest.look - frame goes to look…` |
| `turn_end` | ход звука закончен (`playback_done` — когда реально доиграл); `found: false` — **без реакции**; язык ответа | `LiveSession`, `MainViewModel.onLive` | `MainViewModelLiveTest.turn_end with found false…`, `LiveSessionTest.turn without audio…` |
| `turn_end` `sources`/`citations` (v1.3, необяз.) | подпись-источник под ответом в чате («Источник: …», до 3 названий); нет поля — подписи нет | `LiveProtocol` → `LiveSignal.TurnEnd.citations` → `MainUiState.citations` → `ChatLog` → `Tiles.sourceCaption` | `LiveProtocolTest.server events are parsed`, `ChatLogTest.citations…`, `TilesTest.source caption…` |
| `interrupted` | мгновенный сброс звука хода, его действия отбрасываются, субтитры убраны | `LiveSession`, `MainViewModel` | `LiveSessionTest.interrupted - instant flush…`, `MainViewModelLiveTest.interrupted turn drops its guide_to…` |
| `error` | запасной режим: вопрос уходит агенту (`/v1/chat`), live снова через 30 с | `MainViewModel` | `MainViewModelLiveTest.server error during a playing answer…`, `.connection drop…` |

### Робот → сервер

| Кадр | Когда | Где | Тест |
|---|---|---|---|
| `hello` (язык, место, реальный флаг AEC) | открытие сессии | `LiveFrames`, `LiveSession.start` | `LiveProtocolTest.hello carries lang, place and the real aec flag` |
| звук микрофона (PCM16 16 кГц, 20–40 мс) | всё время сессии; пока говорит своя озвучка — тишина | `live/LiveMicrophone.kt`, `LiveSession.setLocalSpeech` | `LiveProtocolTest.mic frame…`, `LiveSessionTest.robot's own local speech…` |
| `state.directions` (фразы «где X» по `place_id` на 3 языках) | после `ready`, после каждой поездки, при сдвиге ≥ 0.5 м / повороте ≥ 20° | `live/LiveDirections.kt` (`LiveDirectionsReporter`), `WayFinder.directionsFrom` | `LiveDirectionsTest`, `MainViewModelLiveTest.live ready - robot sends where-is phrases…` |
| `state` (место, `navigating`) | начало и конец поездки | `LiveSession.sendState` | `LiveSessionTest.navigation state frame` |
| `playback_done` | звук хода реально доиграл | `LiveSession` / `StreamPlayer` | `LiveSessionTest.full turn…` |
| `interrupt` | «Стоп» (экран 2), тап по лицу, пока робот говорит | `LiveSession.interrupt`, `MainViewModel.stop/interruptSpeech` | `LiveSessionTest.local stop…`, `MainViewModelLiveTest.stop button…`, `MainViewModelSleepTest.tap in a trip…` |
| `visitor_arrived` | человек подошёл (presence) | `LiveSession.visitorArrived`, `MainViewModel.onPresence` | `MainViewModelLiveTest.visitor arrives…` |

## 3. Запасной путь и остальные API

| API / событие | Что делает UI | Где | Тест |
|---|---|---|---|
| `/v1/chat` SSE: ожидание | лицо THINKING, чат — «…»; 8 с без ответа (приветствие — 2 с) → `/v1/ask` | `AgentTurnRunner`, `MainViewModel.handleAgent` | `AgentTurnRunnerTest`, `MainViewModelAgentTest.no phrase or action in 8 s…` |
| `/v1/chat` `emotion`, `phrase` | эмоция; фразы — одним потоком в голос, растущая реплика и субтитры в чате | `MainViewModel.handleAgent`, `ChatLog` | `MainViewModelAgentTest.phrases are spoken in order…` |
| `/v1/chat` `action where_is` / `guide_to` / `look` | как в live: остров where_is, поездка после последней фразы, снимок | `agentWhereIs`, `agentGuide`, `agentLookResult` | `MainViewModelAgentTest.where_is action…`, `.guide_to action…`, `.look action…` |
| `/v1/chat` `done` (`found`, `lang`, `actions`, `ignored`, `sources`, `citations`) | `found: false` и `ignored` — без реакции; язык ответа — язык экрана; источники — подпись под ответом | `Sse.done`, `CitationParser`, `MainViewModel` | `SseParserTest`, `ChatApiTest`, `MainViewModelAgentTest.agent language from done…` |
| `/v1/chat` ошибка / нет чата (404) / офлайн | ERROR на лице; `/v1/ask` или локальные правила | `MainViewModel` | `MainViewModelAgentTest.error event before a reply…`, `.server without chat (404)…`, `.offline…` |
| `/v1/ask` | ответ в чате, фразы по очереди (≤ 300 симв.), источники — подпись; 400 → CONFUSED, 429 — ждёт и повторяет, сеть — ERROR + «нет связи» | `RetrofitBackendApi.ask`, `MainViewModel.deliver/troubleCue` | `RetrofitBackendApiTest`, `MainViewModelTest`, `MainViewModelFaceCueTest` |
| `/v1/tts` (поток MP3, кеш фраз) | голос робота; рот — `Visualizer`; нет голоса — субтитры без звука | `voice/ServerVoiceGateway.kt`, `TtsStream`, `PhraseAudioCache` | `StreamingTtsTest`, `ElevenLabsOnlyVoiceTest`, `RoutingVoiceGatewayTest` |
| `/v1/stt` (WAV) и live STT (поток) | реплика посетителя → вопрос; «Спросить голосом» — одна фраза; нет связи — плашка «Нет связи» и фраза раз в минуту | `ServerVoiceGateway`, `backend/LiveStt.kt`, `MainActivity.listenOnce` | `RetrofitBackendApiTest.stt…`, `LiveSttTest`, `LiveSttGateTest`, `MainViewModelReliabilityTest.lost STT connection…` |
| `/v1/look` (кадр SurfaceShareApi) | «Что это?» (плитка или голос): лицо LOOKING, описание в чате; нет камеры — ERROR и готовая фраза | `camera/CameraGateway.kt`, `MainViewModel.handleLook` | `MainViewModelTest.look takes a photo…`, `.camera unavailable…`, `RetrofitBackendApiTest.look sends multipart…` |
| `/v1/places` (ETag / 304) | кеш мест: «где X» и «проводи» офлайн, остров where_is, `state.directions` | `places/PlaceRepository.kt`, `WayFinder` | `PlaceRepositoryTest`, `RetrofitBackendApiTest.places…`, `WayFinderTest` |
| быстрые вопросы (`/v1/quick-questions`) | плитки экрана 2 (до 4) + «Частые вопросы» (все, по 3 на страницу); кеш по языку, офлайн — кнопки мест | `Tiles.home`, `ScreenTwo.TileGrid/FaqPanel`, `QuickQuestionCache`, `OfflineQuickButtons` | `TilesTest`, `QuickQuestionCacheTest`, `MainViewModelReliabilityTest.quick questions are cached…` |
| история `/v1/sessions/{id}/messages` | локальные ответы робота («где X», «где я») уходят в историю разговора; на экране — тот же чат | `MainViewModel.postLocal` | `ChatApiTest.local answer goes to session history`, `MainViewModelAgentTest.legacy where is answers locally and goes to history` |
| метрики `/v1/metrics/turn` | после каждого хода (без текстов) | `MainViewModel.sendTurnMetrics` | `StreamingAndMetricsApiTest.turn metrics…`, `MainViewModelLiveTest.full turn…` |
| `/health` | экран диагностики: адрес, статус, версия | `SwitchableBackendApi.health`, `DiagnosticsActivity` | `SwitchableBackendApiTest.health parses status and version` |

## 4. Робот

| Что | Что делает UI | Где | Тест |
|---|---|---|---|
| поездка (`guide_to`, «Проводить», «да») | **экран 1 сам**: если открыт экран 2 — лицо вырастает из шапки тем же путём; внизу остров «→ Иду к: Кафе · Остановить» (материализуется: scale 0.92 → 1 + прозрачность пружиной); экран 2 и удержание (админка) в пути выключены | `RobotScreens`, `ScreenTwoMapper.menuOpen/faceHoldEnabled`, `IslandView` | `ScreenTwoMapperTest`, `IslandModelTest`, `ScreenSwipeTest.during a trip…` |
| «Остановить» на острове | `MainViewModel.stop()` → навигация остановлена, реакция ACK, остров «Остановились»; касание забирает кнопка (удержание на ней — не админка) | `IslandView` (`PillButton`, `pressToClick`) | `MainViewModelTest.stop cancels guiding`, `FaceGestureRecognizerTest.touch taken by a button…` |
| итог поездки | лицо: приехали → HAPPY, ошибка → ERROR, отмена → ACK, места нет → CONFUSED; остров: «Пришли: Кафе» / «Не получилось дойти» / «Остановились» ~2 с и мягко уходит; потом — экран, где был посетитель | `MainViewModel.tripCue`, `IslandModel.onOutcome`, `MainActivity.refreshMenuOpen` | `MainViewModelFaceCueTest`, `IslandModelTest.trip end…`, `ScreenTwoMapperTest` |
| тап по лицу в поездке | робот говорит фразу пути — замолкает, поездка продолжается; молчит — рефлекс | `FaceInputMapper.tap`, `MainViewModel.interruptSpeech` | `FaceInputMapperTest`, `MainViewModelSleepTest.tap in a trip…` |
| навигация RobotAPI (`startNavigation`, результаты, таймауты, стоп с подтверждением) | без изменений: исходы → итог поездки выше | `robotShared/…/RobotApiGateway.kt` | `RobotApiGatewayTest`, `MainViewModelReliabilityTest.new guide during a trip waits for stop confirmation…` |
| NavJournal | журнал поездок (решения по `guide_to`, исходы, коды) — на экране диагностики | `robot/NavJournal.kt`, `DiagnosticsActivity` | `NavJournalTest`, `RobotApiGatewayTest.result codes map to outcomes…` |
| «где X» по карте | остров where_is на лице (стрелка от посетителя, место, расстояние; глаза — туда же) ~9 с; «да» на «Проводить?» превращает его в остров поездки (а не гасит); на экране 2 — ответ в чате и «Проводить: …» | `WayFinder.describe` (`WayAnswer.bearing`), `Directions.visitorAngle`, `MainViewModel.showWhereIs`, `IslandModel`, `IslandGeometry` | `WayFinderTest`, `DirectionsTest.visitor angle…`, `MainViewModelTest.where is answered locally…`, `IslandModelTest` |
| локальное «да» на «Проводить вас?» (live) | едем сразу, не дожидаясь сервера; остров where_is → остров поездки | `MainViewModel.onLiveUserFinal` | `MainViewModelLiveTest.local yes to the guide offer…`, `MainViewModelAgentTest.yes after the robot offered to guide…` |
| presence (PersonApi) | подошёл — пробуждение WAKE → приветствие (GREET, `visitor_arrived`); ушёл — экран не засыпает сразу; голос будит, только если рядом человек | `presence/Presence.kt`, `MainViewModel.onPresence/onVisitorVoice` | `PresenceTrackerTest`, `MainViewModelSleepTest`, `MainViewModelAgentTest.visitor arrives…` |
| голова (слежение за посетителем) | без изменений; в поездке и при снимке — выключено | `robot/Head.kt`, `RobotHeadGateway` | `HeadControllerTest` |
| камера SurfaceShareApi | «Что это?» — кадр, `stopPushStream` после снимка | `robotShared/…/SurfaceShareCameraGateway.kt` | `SurfaceShareCameraGatewayTest` |
| сон (3 мин бездействия) | зевок → сон, остров уходит; будят тап, человек, голос | `MainViewModel.restartIdleTimer`, `IslandModel.clear` | `MainViewModelSleepTest`, `IslandModelTest` |

## 5. Экраны

### Экран 1 — касания (M2)

| Жест | Действие | Где | Тест |
|---|---|---|---|
| тап | спит → проснуться; говорит → замолчать (`stop`/`interrupt`); в поездке говорит → только речь; молчит → рефлекс | `FaceInputMapper.tap`, `MainActivity.onFaceTap` | `FaceInputMapperTest`, `FaceEngineTest.tap reflex…` |
| удержание 3 с | PIN (`DiagPinDialog`) → `DiagnosticsActivity`; без PIN и **в поездке** — выключено | `faceGestures`, `ScreenTwoMapper.faceHoldEnabled` | `FaceGestureRecognizerTest.holding still…`, `ScreenTwoMapperTest.hold on the face…` |
| поглаживание | PETTED, пока гладят; рот двигается по речи | `FaceInputs.petting` | `FaceEngineTest.petting…` |
| свайп влево | экран 2 (палец 1:1); в поездке — нет | `RobotScreens`, `ScreenSwipe` | `ScreenSwipeTest` |

### Экран 1 — остров (M4)

| Содержимое | Когда | Что внутри | Тест |
|---|---|---|---|
| where_is | новый ответ «где X» с направлением | стрелка (поворот от посетителя), место, расстояние («15 м» / «рядом»), «Проводить» если можно; глаза — в сторону места | `IslandModelTest.where_is island shows for 9 s…`, `.arrow and gaze…` |
| поездка | `guidingTo` | «→ Иду к: Кафе» + «Остановить» | `IslandModelTest.a new question does not hide…` |
| итог | конец поездки + реакция (ждёт до 0.6 с) | галочка «Пришли: Кафе» / крестик «Не получилось дойти» / квадрат «Остановились», 2 с | `IslandModelTest.trip end…`, `.outcome that arrives before the state…`, `.no outcome within the wait…` |

Одна пилюля: содержимое перетекает (`AnimatedContent`, размер — пружиной), высота постоянна; появление и уход —
«материализация» (scale 0.92 + прозрачность). На экране 2 острова нет (там чат и «Проводить»).

### Экран 2 (M3): прежний функционал → где теперь

| Было на прежнем экране | Теперь | Код | Проверка |
|---|---|---|---|
| вопрос и ответ, облачко субтитров, крупные субтитры в режиме лица | чат: реплики посетителя и робота, растущий ответ, живые субтитры | `ChatLog`, `ScreenTwo.Chat` | `ChatLogTest`, `MainViewModelScreenTwoTest`, `M3/m3_chat_live.mp4` |
| «что слышу» (`partialQuestion`) | полупрозрачный пузырь посетителя «…» | `ScreenTwo.Chat` | `ScreenTwoMapperTest.partial…` |
| статус «Слушаю / Думаю / Говорю» | строка под «Astana Hub»; «думаю» — три точки в чате | `ScreenTwo.Header` | снимки `M3` |
| плитки приветствия (4 вопроса + «Частые вопросы» + «Что это?») | плитки 2 × 3, монохромные значки | `Tiles`, `ScreenTwo.TileGrid` | `TilesTest` |
| частые вопросы (аккордеон, «Ещё вопросы (1/3)», «Проводить», «Показать полностью») | то же — справа | `ScreenTwo.FaqPanel`, `ScreenTwoMapper.faq` | `ScreenTwoMapperTest.faq…`, `M3/m3_faq.mp4` |
| «Проводить: Кафе» под ответом | белая пилюля под последним ответом | `ScreenTwo.RobotLine`, `ScreenTwoMapper.guideOffer` | `ScreenTwoMapperTest.guide offer…` |
| «Стоп» (пока занят) | «Стоп» вместо «Спросить голосом» | `ScreenTwo.Composer`, `ScreenTwoMapper.busy` | `ScreenTwoMapperTest.busy…` |
| «Просто скажите вопрос» | «Спросить голосом» | `MainActivity.listenOnce` | — (микрофон — на роботе) |
| «Что это?» внизу | плитка «Что это?» | `TileAction.Look` | — (камера — на роботе) |
| «В начало» / «Назад» | история — в чате; из частых вопросов — «‹» и системное «Назад»; «Назад» на экране 2 — к лицу | `MainActivity` (back) | `ChatLogTest.back from the FAQ…` |
| RU / KZ / EN | сегменты в шапке | `LanguageSwitch` | `TilesTest.labels…`, `M3/m3_lang.mp4` |
| выход (× → подтверждение, 15 с) | × в шапке → диалог (блюр на API 31+, затемнение на старых) | `ExitDialog`, `MainActivity.askExit` | `M3/m3_exit.mp4` |
| «Нет связи», «Микрофон выключен» | плашки в шапке | `StatusBadge` | снимок `M3/m3_14_mic_off.png` |
| удержание логотипа → PIN → диагностика | удержание «Astana Hub» в шапке (и по лицу) | `holdToActivate`, `MainActivity.openDiagnostics` | `DiagnosticsLockTest` |
| поле ввода (эмулятор) | поле и «отправить» внизу чата (только эмулятор) | `ScreenTwo.TextInput` | записи `M3` |
| версия сборки | подпись внизу справа | `ScreenTwoState.buildLabel` | снимки |
| слои «поездка» и «заставка» | экран 1 (лицо, остров, сон) — из экрана 2 переход сам пружиной | `RobotScreens` | `M3/m3_where_guide.mp4` |

## 6. Настройки, диагностика, сборки

| Что | Статус | Где | Тест |
|---|---|---|---|
| PIN диагностики (5 попыток, блок 5 мин, сессия) | без изменений; входы — удержание «Astana Hub» на экране 2 и удержание по лицу (не в поездке) | `security/`, `DiagPinDialog`, `DiagnosticsActivity` | `DiagnosticsLockTest` |
| масштаб UI (60–150 %) | экраны 1 и 2 — в dp под ширину 1600 dp; лицо вписывается само; применяется без перезапуска | `UiScale`, `MainActivity.onResume` | `UiScaleTest` |
| чувствительность микрофона, конец фразы (VAD) | без изменений | `voice/VoiceMode.kt` (`VoiceSettings`), `LiveEndpointer`, `EnergyVad` | `LiveEndpointerTest.sensitivity…`, `EnergyVadTest` |
| адрес бэкенда | без изменений; смена — обновление данных без перезапуска | `BackendUrlPolicy`, `SwitchableBackendApi`, `MainActivity.onResume` | `BackendUrlPolicyTest`, `SwitchableBackendApiTest` |
| режим разговора (live / agent / legacy) | legacy: «бодрый» экран — экран 2, лицо — во сне и в поездке, свайп вправо пружинит назад | `ChatSettings`, `MainViewModel.awakeMode` | `MainViewModelLiveTest.switching chat mode…`, `MainViewModelAgentTest.menu opens by button…` |
| голос по языкам (сервер / встроенный), микрофон AgentOS | без изменений | `RoutingVoiceGateway`, `AssistantGuard` | `RoutingVoiceGatewayTest`, `AssistantGuardTest` |
| техническая пауза после серии падений | без изменений (XML) | `TechPauseActivity`, `CrashLoopPolicy` | `CrashLoopPolicyTest`, `CrashReportTest` |
| уменьшение движения (системные анимации выкл.) | лицо без толчков, переходы и появление — без анимации | `FaceEngine.reducedMotion`, `RobotScreens`, `appear` | `FaceEngineTest` |
| flavor `emulator` | фейки (бэкенд без адреса, робот, камера, человек), отладка лица adb broadcast (только debug) | `src/emulator/…/fake`, `FaceDebug` | `FakeBackendApiTest`, все общие тесты |
| flavor `robotos` (RobotOS 10.4) | RobotAPI, PersonApi, SurfaceShareApi, голос — скиллы RobotOS | `src/robotos`, `src/robotShared` | `src/testRobotos` (`RobotApiGatewayTest` и др.) |
| flavor `robot` (AgentOS) | AgentOS SDK (`AppAgent` в `Application`) | `src/robot` | компиляция |
| сборка APK | `assembleEmulatorDebug`, `assembleRobotosDebug`, `assembleRobotDebug`; `publishTestApks` копирует emulator / robotos в `~/astana-hub-apk/` (`astana-hub-test.apk`, `astana-hub-robotos.apk`) для раздачи роботу по http | `app/build.gradle.kts` | сборки exit 0 |
| установка на робот | `infra/scripts/robot-install.sh <ip> [apk]` — без APK собирает `robot` (AgentOS); для RobotOS 10.4 передать `app-robotos-debug.apk` | `infra/scripts/` | — (зона devops) |

## 7. Проверить на роботе

- Visualizer на общей аудиосессии MediaPlayer (громкость MP3 TTS): создаётся ли на прошивке Nova, задержка; если
  нет — рот по синтетическим слогам (в логе `playback visualizer unavailable`).
- VAD на микрофонном массиве Nova и PersonApi: «слушает» и пробуждение голосом подошедшего, разговоры в зале не будят.
- 60 fps на железе: лицо, переход экран 1 ↔ 2 с живым мини-лицом, чат, остров (на эмуляторе с GPU хоста — переходы
  0 janky, см. `shots/M3/gfxinfo_*`, `shots/M4/gfxinfo_*`).
- Версия Android (API 28?): блюр диалога выхода — только на API 31+, на старых — затемнение.
- Касания на сенсоре 14": гистерезис 10 dp, кольцо удержания, ручка заметна с 1–1.5 м, свайп вправо не путается с
  прокруткой чата; читаемость (Body 28 sp, подписи 20 sp, остров — Headline 34 sp) с 1–1.5 м.
- Стрелка острова where_is: посетитель стоит лицом к роботу (`Directions.VISITOR_FACES_ROBOT`) — стрелка и слова
  («справа от вас») должны совпасть с реальностью.
- Тап в поездке обрывает фразу пути встроенного TTS / серверного голоса (`stopSpeaking`, `interrupt`).
