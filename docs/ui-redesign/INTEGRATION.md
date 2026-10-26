# Новый UI ↔ приложение и сервер

Что новый интерфейс делает с каждым событием приложения и API (`docs/api-contract.md`), где это в коде и чем
проверено. Бизнес-логика (`MainViewModel`, live, voice, robot, places, backend) не переписывается: новый UI
читает `MainViewModel.state` и `MainViewModel.faceCues`, звук — из `AudioLevels`.

Статус: **M1** — экран 1 (лицо). Экран 2 (чат, плитки, язык, citations) — M3, остров where_is и поездки — M4.
До M3 всё прежнее живёт на старом экране (свайп влево с лица / «К роботу» обратно) — функционал не потерян.

Пути: `ui/` = `android-app/app/src/main/java/kz/astanahub/robot/ui/`, тесты — `android-app/app/src/test/...`.

## Как состояние приложения попадает на лицо

```
live / chat / voice / robot / presence ──► MainViewModel ──► MainUiState ─┐
                                              └─► faceCues (реакции) ────┤
AudioLevels (микрофон, речь робота) ─────────────────────────────────────┤
                                                                         ▼
MainActivity.render → FaceController.onState → FaceInputMapper → FaceInputs
                    → FaceController.onCue  → FaceDirector.cue
кадр (vsync) → FaceEngine.update → FaceDirector (состояние) → пружины → FaceRenderer
```

| Вход лица | Откуда | Где | Тест |
|---|---|---|---|
| `awake` | `MainUiState.mode != SLEEP` | `ui/face/FaceInputMapper.kt` | `FaceInputMapperTest` |
| `activity` | `MainUiState.phase` (IDLE/LISTENING/THINKING/LOOKING/SPEAKING/NAVIGATING) | `FaceInputMapper.activity` | `FaceInputMapperTest` |
| `emotion` | `MainUiState.emotion` (сервер `emotion` + события робота, держится 6 с) | `FaceInputMapper.emotion` | `FaceInputMapperTest` |
| `userSpeaking` | `MainUiState.partialQuestion` при покое/слушании | `FaceInputMapper.apply` | `FaceInputMapperTest` |
| реакции | `MainViewModel.faceCues` | `ui/MainViewModel.kt` (`cue`, `troubleCue`, `tripCue`) | `MainViewModelFaceCueTest` |
| посетитель говорит (**первичный сигнал — VAD**) | `AudioLevels.voiceActive`: VAD приложения (`MicRecorder.speaking` — `EnergyVad`, `LiveEndpointer`) и сервера (`user_speaking` → `user_final`) | `audio/AudioLevels.kt`, `voice/ServerVoiceGateway.kt`, `live/LiveSession.kt` | `AudioLevelsTest`, `LiveSessionTest.server VAD…`, `FaceDirectorTest`, `FaceEngineTest.steady hall noise…` |
| громкость голоса (только сила реакции) | `AudioLevels.micLevel`, шумовой фон вычитается | `audio/AudioLevels.kt`, `FaceAudioEnvelope.mic` | `FaceAudioEnvelopeTest`, `FaceEngineTest` |
| речь робота | `AudioLevels.playbackLevel` | `audio/AudioLevels.kt`, `live/StreamPlayer.kt`, `audio/PlaybackVisualizer.kt` | `AudioLevelsTest`, `FaceEngineTest` |

## Live `WS /v1/live` (сервер → робот)

| Событие | Что делает новый UI (M1) | Где | Тест |
|---|---|---|---|
| `ready` | — (лицо не меняется; `offline` снимается) | `MainViewModel.onLive` | `MainViewModelLiveTest` |
| `listening` | фаза LISTENING → лицо в покое (микрофон открыт всегда — это не «слушаю») | `FaceDirector` | `FaceDirectorTest.activities map to moods` |
| `user_speaking` | VAD сервера → «слушает» (до `user_final` / `ignored` / `thinking`, удержание 500 мс); громкость микрофона — только сила реакции | `live/LiveSession.kt` → `AudioLevels.reportVoice`, `FaceDirector`, `VoiceHold` | `LiveSessionTest.server VAD…`, `FaceDirectorTest.vad voice…` |
| `user_partial` | `partialQuestion` → LISTENING (внимательный взгляд, реакция на громкость) | `FaceInputMapper` | `FaceInputMapperTest`, `FaceDirectorTest.visitor partial text…` |
| `user_final` | вопрос → дальше THINKING по `thinking`; реплика обрывает идущую реакцию | `FaceDirector` | `FaceDirectorTest.visitor starting to talk…` |
| `ignored` | без реакции (как и раньше) | `MainViewModel.onLiveIgnored` | `MainViewModelLiveTest` |
| `thinking` | THINKING: взгляд вверх-в сторону, прищур, бровь | `Expressions.THINKING` | `ExpressionsTest`, `FaceDirectorTest` |
| `turn_start` | — | | |
| `emotion` | наложение эмоции поверх состояния (`happy`, `excited`, `surprised`, `sad`, `thinking`) | `Expressions.overlay` | `ExpressionsTest`, `FaceEngineTest` |
| `phrase` | субтитры — экран 2 (M3); лицо — SPEAKING по звуку | | |
| бинарный звук | рот по громкости того, что **реально звучит** (RMS блоков PCM по позиции воспроизведения) | `live/StreamPlayer.kt` → `PcmPlaybackMeter` | `AudioLevelsTest.live pcm level…` |
| `action where_is` | M1: без изменений лица (подсказка — на прежнем экране); M4 — остров и взгляд в сторону места | | |
| `action guide_to` | поездка → TRAVEL + остров «→ место / Стоп» на экране 1; итог → реакция | `FaceScreen.TravelIsland`, `MainViewModel.tripCue` | `MainViewModelFaceCueTest` |
| `action look` | LOOKING пока идёт снимок (если долго), эмоция `surprised` | `Expressions.LOOKING` | `ExpressionsTest` |
| `turn_end` (`found: false`) | **без реакции**: так помечены и ответы из общих знаний (`ANSWER_MODE=open`); «не знаю» передаёт эмоция `sad` | `MainViewModel.onLive` | `MainViewModelLiveTest.turn_end with found false…` |
| `turn_end` `citations` | экран 2 (M3) | | |
| `interrupted` | фаза из голоса → лицо слушает/покой | | |
| `error` | запасной режим (как раньше); лицо — по фазам agent | | |

Клиент → сервер (`hello`, `state.directions`, `playback_done`, `interrupt`, `visitor_arrived`) — без изменений.

## `/v1/chat` (SSE), `/v1/ask`, `/v1/look`, голос

| Событие / API | Что делает новый UI (M1) | Где | Тест |
|---|---|---|---|
| ожидание первой фразы | THINKING | фаза | `FaceDirectorTest` |
| `emotion` | наложение эмоции | `Expressions.overlay` | `ExpressionsTest` |
| `phrase` + озвучка | SPEAKING; рот — один Visualizer на общей аудиосессии клипов MP3 TTS (включается на своём потоке) или синтетические слоги | `audio/PlaybackVisualizer.kt`, `audio/Mp3Player.kt`, `SpeechSynth` | `FaceEngineTest.speaking…` |
| `action guide_to` / `where_is` / `look` | как в live | | |
| `done` `found: false` | без реакции (как и `ignored`); эмоция сервера — как пришла | `MainViewModel.runAgentTurn` | `MainViewModelFaceCueTest.agent turn with found false…` |
| `/v1/ask` `found: false` | без реакции | `MainViewModel.deliver` | `MainViewModelFaceCueTest.found false is not a reaction…` |
| ошибка сети/сервера (`/ask`, `/look`, `/chat` фолбэк) | ERROR (огорчение, капля, «нет» головой) | `troubleCue` | `MainViewModelFaceCueTest.network failure…` |
| 400 → «не расслышал» | CONFUSED | `troubleCue` | `MainViewModelFaceCueTest.bad request…` |
| камера не дала кадр | ERROR | `handleLook`, `agentLookResult`, `liveLook` | `MainViewModelFaceCueTest.camera…` |
| `/v1/stt` нет связи → «нет связи» | ERROR | `onConnectionLost` | — |
| микрофон (обычный режим) | VAD записи → «слушает»; громкость → сила реакции | `voice/ServerVoiceGateway.kt`, `audio/MicRecorder.kt` | `FaceEngineTest.listening face…`, `.vad voice alone…` |
| `/v1/tts`, `/v1/places`, быстрые вопросы, история, метрики | без изменений (прежний экран до M3) | | существующие тесты |

## Робот

| Событие | Что делает новый UI (M1) | Где | Тест |
|---|---|---|---|
| поездка (NAVIGATING) | экран 1 показывается сам (даже из меню), TRAVEL + остров со «Стоп» | `MainActivity.renderFaceScreen`, `FaceScreen` | ручная проверка (запись `guide_travel_arrive.mp4`) |
| «Стоп» на острове | `MainViewModel.stop()` → реакция ACK | `FaceScreen.PillButton` | `MainViewModelFaceCueTest.stop during the trip…` |
| тап по лицу | спит → `wake()`; говорит → `stop()` (tap-to-interrupt); в поездке — ничего (только «Стоп») | `FaceInputMapper.tap`, `MainActivity.onFaceTap` | `FaceInputMapperTest.tap…`, запись `fix/tap_*.mp4` |
| итог поездки | приехали → HAPPY; ошибка / не локализован → ERROR; точки нет → CONFUSED; отмена → ACK | `MainViewModel.tripCue` | `MainViewModelFaceCueTest` |
| человек подошёл (presence) | пробуждение WAKE → приветствие EXCITED | `MainViewModel.onPresence` → `GREET` | `MainViewModelFaceCueTest.visitor arriving…` |
| сон (бездействие) | зевок → сон («z», медленное дыхание); голос/касание/человек — бодрое пробуждение | `FaceDirector` | `FaceDirectorTest`, `FaceEngineTest.sleep…` |
| локальное «да» на «Проводить?» | как поездка | | существующие тесты |

## Настройки, диагностика, сборки

| Что | Статус в M1 |
|---|---|
| PIN, масштаб UI, микрофон, адрес бэкенда, режим разговора | без изменений: диагностика — удержанием логотипа на прежнем экране; long press по лицу — M2 |
| масштаб UI (`UiScale`) | лицо вписывается в экран само (виртуальный холст), прежний экран — как был |
| режим legacy | «бодрый» экран — меню (как раньше), лицо — во сне и в поездке |
| flavor `emulator` | отладочные команды лицу (adb broadcast) — только debug + emulator |
| flavor `robotos` / `robot` | собираются; отладочного ресивера нет |
| уменьшение движения | системные анимации выключены → без толчков и покачиваний |

## Проверить на роботе

- Visualizer на общей аудиосессии MediaPlayer (громкость MP3 TTS): создаётся ли на прошивке Nova, задержка; если
  нет — рот по синтетическим слогам (в логе `playback visualizer unavailable`).
- VAD на микрофонном массиве Nova: адаптивный порог `EnergyVad` (тот же, что режет фразы для STT) — «слушает» лицо
  включает то же, что начинает распознавание; громкость голоса для реакции — после вычета шумового фона.
- 60 fps на железе робота (на эмуляторе с GPU хоста — 0.35 % janky, см. `shots/M1/gfxinfo_*.txt`).
- Версия Android (API 28?): лицо не использует ничего новее API 26.
