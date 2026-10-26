package kz.astanahub.robot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.AskContext
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.LocalMessage
import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.backend.QuickQuestionCache
import kz.astanahub.robot.backend.TurnMetrics
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.chat.AgentTurnRunner
import kz.astanahub.robot.chat.ChatAction
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.chat.ChatRequest
import kz.astanahub.robot.chat.ChatTimeouts
import kz.astanahub.robot.chat.TurnHandler
import kz.astanahub.robot.chat.TurnOutcome
import kz.astanahub.robot.core.IntentRouter
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.PhraseSplitter
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.core.UtteranceFilter
import kz.astanahub.robot.core.NoiseContext
import kz.astanahub.robot.core.NoiseStats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kz.astanahub.robot.places.OfflineQuickButtons
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.places.WayPhrases
import kz.astanahub.robot.presence.PresenceEvent
import kz.astanahub.robot.robot.HeadController
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavJournal
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.voice.SpeechChunk
import kz.astanahub.robot.voice.SpeechEvent
import kz.astanahub.robot.voice.SpeechListener
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.core.LogSafe
import kz.astanahub.robot.live.DirectionsReason
import kz.astanahub.robot.live.LiveDirectionsReporter
import kz.astanahub.robot.live.LiveSession
import kz.astanahub.robot.live.LiveSessionFactory
import kz.astanahub.robot.live.LiveSignal
import kz.astanahub.robot.ui.face.FaceCue
import timber.log.Timber

/** Индикатор на экране. */
enum class Phase { IDLE, LISTENING, THINKING, LOOKING, SPEAKING, NAVIGATING }

/** Что показано в правой части экрана: приветствие с плитками, ответ или список частых вопросов. */
enum class Screen { HOME, ANSWER, FAQ }

data class MainUiState(
    val lang: Lang = Lang.RU,
    val phase: Phase = Phase.IDLE,
    /** Промежуточный результат ASR (пока посетитель говорит). */
    val partialQuestion: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val quickQuestions: List<QuickQuestion> = emptyList(),
    /** Последний запрос к серверу не прошёл из-за связи. */
    val offline: Boolean = false,
    /** Место, до которого можно проводить (кнопка «Проводить» под ответом). */
    val guideTarget: Place? = null,
    /** Куда сейчас ведём (название), null — не ведём. */
    val guidingTo: String? = null,
    /** SLEEP — «спящее» лицо (заставка), FACE — лицо на весь экран (agent), MENU — плитки и ответы. */
    val mode: ScreenMode = ScreenMode.MENU,
    /** Выражение лица: `emotion` агента или событие на роботе; сбрасывается в NEUTRAL после ответа. */
    val emotion: Emotion = Emotion.NEUTRAL,
    /** Фраза ответа, которая звучит сейчас (крупные субтитры в FACE); null — показываем весь [answer]. */
    val subtitle: String? = null,
    val screen: Screen = Screen.HOME,
    /** Живой режим, `action: where_is`: направление по карте робота (озвучивает сервер, робот только показывает). */
    val wayHint: String? = null,
    /** Страница частых вопросов (по [FAQ_PAGE_SIZE] на странице). */
    val faqPage: Int = 0,
    /** Раскрытый вопрос в списке частых вопросов (id), null — все свёрнуты. */
    val faqOpenId: String? = null,
    /** Источники текущего ответа (`citations`, v1.3) — подпись под ответом на экране 2; старый сервер не шлёт. */
    val citations: List<Citation> = emptyList(),
    /** Номер разговора: меняется с session_id (новый посетитель) — экран 2 начинает чат с чистого листа. */
    val conversation: Int = 0,
) {
    /** Заставка после бездействия (режим SLEEP). */
    val screensaver: Boolean get() = mode == ScreenMode.SLEEP

    /** Кнопка «Назад» видна везде, кроме приветствия. */
    val canGoBack: Boolean get() = screen != Screen.HOME

    val faqPageCount: Int get() = ((quickQuestions.size + FAQ_PAGE_SIZE - 1) / FAQ_PAGE_SIZE).coerceAtLeast(1)

    /** Вопросы текущей страницы. */
    val faqPageItems: List<QuickQuestion>
        get() = quickQuestions.drop(faqPage.coerceIn(0, faqPageCount - 1) * FAQ_PAGE_SIZE).take(FAQ_PAGE_SIZE)

    companion object {
        const val FAQ_PAGE_SIZE = 3
    }

    /** Идёт запрос (думаю / смотрю): кнопки вопросов заблокированы. */
    val processing: Boolean get() = phase == Phase.THINKING || phase == Phase.LOOKING
}

class MainViewModel(
    private val backend: BackendApi,
    private val voice: VoiceGateway,
    private val camera: CameraGateway,
    private val phrases: Phrases,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdleMillis: Long = SESSION_IDLE_MILLIS,
    /** «Где X» / «Проводи» — локально по карте (и действия агента); null — всё уходит на сервер. */
    private val wayFinder: WayFinder? = null,
    private val robot: RobotGateway? = null,
    /** Через сколько мс бездействия показать заставку и сбросить сессию; null — выключено (тесты). */
    private val screensaverMillis: Long? = null,
    /** Кеш кнопок частых вопросов на диске (показываем без сети). */
    private val quickCache: QuickQuestionCache? = null,
    /** agent — реплики идут в `/v1/chat`, legacy — прежний путь. Читается на каждую реплику (меняется в диагностике). */
    private val chatMode: () -> ChatMode = { ChatMode.LEGACY },
    private val chatTimeouts: ChatTimeouts = ChatTimeouts(),
    /** «Подошёл / ушёл» (PersonApi или заглушка); null — будят только касание и голос. */
    private val presence: Flow<PresenceEvent>? = null,
    /** Голова: слежение за собеседником, сброс, кивки. */
    private val head: HeadController? = null,
    /** Порог тишины VAD (для `/v1/metrics/turn`). */
    private val vadSilenceMillis: () -> Int? = { null },
    /** Живой голосовой режим `WS /v1/live` (ChatMode.LIVE); null — нет. */
    private val liveFactory: LiveSessionFactory? = null,
    /** Сбой live → запасной режим (agent), повторная попытка live через столько. */
    private val liveRetryMillis: Long = LIVE_RETRY_MILLIS,
    /** Приложение на экране; false — микрофон не слушает, live закрыт. null — всегда на экране (тесты). */
    private val foreground: StateFlow<Boolean>? = null,
    /**
     * Live: как часто проверять, сдвинулся ли робот, чтобы обновить серверу фразы «где X» (`state.directions`).
     * null — без периодической проверки (тесты); на старте сессии и после поездки фразы уходят всегда.
     */
    private val liveDirectionsPeriodMillis: Long? = null,
    /** VAD: посетитель говорит (AudioLevels.voice) — голос будит из сна; null — нет (тесты). */
    private val visitorVoice: Flow<Boolean>? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _faceCues = MutableSharedFlow<FaceCue>(extraBufferCapacity = 16)

    /**
     * Короткие реакции лица на события: ошибка, «не понял», приветствие, итог поездки
     * (docs/ui-redesign/INTEGRATION.md). Состояние лица в остальном следует из [state].
     */
    val faceCues: SharedFlow<FaceCue> = _faceCues

    private fun cue(c: FaceCue) {
        _faceCues.tryEmit(c)
    }

    /** Реакция лица на ошибку сервера: «не расслышал» (400) — растерянность, остальное — огорчение. */
    private fun troubleCue(error: BackendError) = cue(if (phraseFor(error) == PhraseKey.NOT_HEARD) FaceCue.CONFUSED else FaceCue.ERROR)

    private var requestJob: Job? = null
    private var quickJob: Job? = null
    private var navJob: Job? = null
    /** Робот едет (от startNavigation до итога): по нему фаза NAVIGATING; фраза прибытия — уже не поездка. */
    private var tripActive = false
    /** Точка текущей поездки (id): повторный `guide_to` к ней во время поездки игнорируем. */
    private var guidingPlaceId: String? = null
    private var idleJob: Job? = null
    private var emotionJob: Job? = null
    private var menuJob: Job? = null
    private var busy = false

    private val turnRunner = AgentTurnRunner(backend, chatTimeouts)

    /** Перед роботом стоит человек (по PersonApi): не засыпаем по бездействию. */
    private var visitorPresent = false

    /** Экраны, на которые возвращает «Назад» (только устойчивые: приветствие, ответ, страница вопросов). */
    private data class Snapshot(
        val screen: Screen,
        val faqPage: Int,
        val question: String?,
        val answer: String?,
        val guideTarget: Place?,
    )
    private val history = ArrayDeque<Snapshot>()

    /** Язык выбран кнопкой — тогда передаём его серверу; иначе сервер определяет сам. */
    private var langChosenExplicitly = false

    /** session_id разговора; тот же уходит в /v1/stt (voice.setSessionId). */
    private var sessionId = newSessionId()
        set(value) {
            field = value
            voice.setSessionId(value)
            _state.update { it.copy(conversation = it.conversation + 1) }
        }
    private var lastInteraction = clock()

    /** Когда последний раз говорили «нет связи» — не чаще раза в [NO_CONNECTION_REPEAT_MILLIS]. */
    private var lastNoConnectionPhrase = Long.MIN_VALUE / 2

    /** Сервер ответил 404 на `/v1/chat` (старая версия) — до смены адреса сервера сразу запасной путь. */
    private var chatUnsupported = false

    /** Кнопки частых вопросов пришли с сервера (а не офлайн-набор). */
    private var quickFromServer = false

    /** Когда VAD решил, что посетитель закончил (clock), — начало отсчёта таймингов хода. */
    private var utteranceEndAt: Long? = null

    /** Лицо уже «думает» по концу речи (VAD), финальный текст ещё распознаётся. */
    private var thinkingEarly = false
    private var thinkingEarlyJob: Job? = null

    /** Тайминги текущего хода (`/v1/metrics/turn`). */
    private var turn: TurnClock? = null

    /** Ближайшая точка карты — последняя известная (запрос к агенту не ждёт позицию робота). */
    @Volatile private var placeHint: String? = null
    private var placeJob: Job? = null

    /** Слова названий мест (`/v1/places`): место одним словом — не шум. */
    @Volatile private var placeWords: Set<String> = emptySet()

    /** Когда последний раз менялся текст ответа робота (вопрос робота «свежий» [ROBOT_QUESTION_WINDOW_MILLIS]). */
    private var answerChangedAt = Long.MIN_VALUE / 2

    /** Последний промежуточный текст посетителя (по нему решаем, «думать» ли лицу по концу речи). */
    private var lastPartial: String? = null

    // --- Живой режим (WS /v1/live) ---
    /** Открытая живая сессия; null — live выключен, не открыт или упал (тогда работает agent). */
    private var live: LiveSession? = null
    private var liveJob: Job? = null
    private var liveRetryJob: Job? = null
    private var liveLookJob: Job? = null
    /** До этого момента (clock) live не открываем: недавно упал. */
    private var liveDownUntil = Long.MIN_VALUE
    /** Реплика посетителя, на которую live ещё не начал отвечать (при сбое — уходит в agent). */
    private var livePendingQuestion: String? = null
    /** Просили поздороваться (`visitor_arrived`), ответа ещё не было (при сбое — здороваемся через agent). */
    private var liveGreetPending = false
    /** Тайминги текущего хода live (`/v1/metrics/turn`, mode=live). */
    private var liveTurn: TurnClock? = null
    /** `guide_to` ждёт `playback_done` своего хода (только если этот ход ещё звучит). */
    private val liveGuides = mutableMapOf<String?, ChatAction>()
    /** Ход live, который сейчас звучит или ещё придёт: от `turn_start` до `playback_done` / `interrupted`. */
    private var liveActiveTurn: String? = null
    /** Live: когда робот показал предложение проводить (where_is → кнопка «Проводить»). */
    private var guideOfferedAt = Long.MIN_VALUE / 2
    /** Live: посетитель сказал «да» на предложение — поехали сами; ответный `guide_to` сервера к этой точке — дубликат. */
    private var localGuideConfirm: Pair<String, Long>? = null
    /** Фразы текущего хода live (текст ответа на экране). */
    private val liveSpoken = mutableListOf<String>()
    /** Последний доигранный ход ([liveGuides] для действия, пришедшего уже после `playback_done`). */
    private var liveLastDone: String? = null
    /** Сколько ходов live начато (для решения, озвучил ли сервер результат `look`). */
    private var liveTurnStarts = 0
    /** Фразы «где X» для сервера (`state.directions`) текущей live-сессии; null — live не готов / нет карты. */
    private var liveDirections: LiveDirectionsReporter? = null
    private var liveDirectionsJob: Job? = null

    /** Тайминги одного хода: все отметки — мс от [origin] (конец речи по VAD или нажатие кнопки). */
    private class TurnClock(val origin: Long, val sttFinalMs: Long?, var lang: Lang?) {
        var mode: String = TurnMetrics.MODE_AGENT
        var requestId: String? = null
        var chatFirstEventMs: Long? = null
        var firstPhraseMs: Long? = null
        var ttsFirstByteMs: Long? = null
        var firstAudioMs: Long? = null
        var fallback = false
        var interrupted = false
    }

    init {
        _state.update { it.copy(mode = awakeMode()) }
        voice.setSessionId(sessionId)
        viewModelScope.launch {
            voice.transcripts.collect(::onTranscript)
        }
        viewModelScope.launch {
            voice.activity.collect {
                // Своя озвучка мимо сервера (навигация, agent-ответ на кнопку) — live шлёт вместо микрофона тишину.
                live?.setLocalSpeech(it == VoiceActivity.SPEAKING)
                refreshPhase()
                restartIdleTimer()
            }
        }
        viewModelScope.launch {
            voice.connection.collect { up -> if (up) onConnectionRestored() else onConnectionLost() }
        }
        viewModelScope.launch { voice.utteranceEnded.collect { onUtteranceEnded() } }
        viewModelScope.launch { voice.speechEvents.collect(::onSpeechEvent) }
        voice.setLanguage(_state.value.lang) // подсказка языка серверному STT — с первой фразы
        loadQuickQuestions()
        refreshPlaces()
        presence?.let { flow -> viewModelScope.launch { flow.collect(::onPresence) } }
        visitorVoice?.let { flow -> viewModelScope.launch { flow.collect { if (it) onVisitorVoice() } } }
        viewModelScope.launch {
            _state.map { it.answer }.distinctUntilChanged().collect { answerChangedAt = clock() }
        }
        // Спим и рядом никого (есть PersonApi): постоянное прослушивание на паузе — голос не будит.
        viewModelScope.launch {
            _state.map { it.mode == ScreenMode.SLEEP }.distinctUntilChanged().collect { updateListeningPaused() }
        }
        foreground?.let { flow ->
            viewModelScope.launch { flow.collect(::onForegroundChanged) }
        }
        restartIdleTimer()
        ensureLive(greet = false)
    }

    private fun inForeground(): Boolean = foreground?.value ?: true

    /**
     * Приложение ушло с экрана (другое приложение, настройки, системный экран): постоянное прослушивание на паузе,
     * живая сессия закрыта — робот не слушает и не отвечает из фона. Вернулось — включаем обратно.
     * Поездку не трогаем (её останавливает только «Стоп»).
     */
    private fun onForegroundChanged(visible: Boolean) {
        Timber.i("app foreground=%s", visible)
        if (!visible) {
            clearThinkingEarly(resetPhase = true)
            closeLive()
            updateListeningPaused()
        } else {
            updateListeningPaused()
            ensureLive(greet = false)
        }
    }

    private fun refreshPlaces() {
        val wf = wayFinder ?: return
        viewModelScope.launch {
            wf.refreshPlaces()
            placeWords = runCatching { wf.placeWords() }.getOrDefault(emptySet())
            // Live открылся раньше, чем пришли места: стартовый набор «где X» не ушёл — отправить сейчас.
            liveDirections?.tick()
        }
    }

    /**
     * Голос не будит: робот спит, а PersonApi рядом никого не видит (разговоры вдалеке, > 3 м).
     * Без PersonApi ([presence] = null) будят касание и голос, как раньше.
     */
    private fun voiceIgnoredAsleep(): Boolean =
        presence != null && _state.value.mode == ScreenMode.SLEEP && !visitorPresent

    /** Робот только что задал вопрос: тогда «да / нет / иә / жоқ» — ответ, а не шум. */
    private fun robotAskedQuestion(): Boolean {
        val answer = _state.value.answer?.trimEnd()?.trimEnd('"', '»', ')', ' ') ?: return false
        return answer.endsWith('?') && clock() - answerChangedAt <= ROBOT_QUESTION_WINDOW_MILLIS
    }

    private fun noiseContext() = NoiseContext(robotAskedQuestion(), placeWords)

    /** Робот занят: думает, смотрит, говорит или ведёт — посторонние реплики фильтруем. */
    private fun busyForVoice(): Boolean =
        if (thinkingEarly) navJob?.isActive == true
        else _state.value.phase in BUSY_PHASES || navJob?.isActive == true

    /**
     * VAD: посетитель замолчал. Лицо сразу «думает» (финальный текст ещё в пути), запоминаем начало отсчёта
     * таймингов и заранее уточняем ближайшую точку карты для запроса к агенту.
     */
    private fun onUtteranceEnded() {
        if (voiceIgnoredAsleep()) return
        utteranceEndAt = clock()
        // Промежуточный текст — шум («ну…», «(смеётся)»): лицо не «думает».
        if (lastPartial?.let { UtteranceFilter.isNoise(it, noiseContext()) } == true) return
        refreshPlaceHint()
        if (busyForVoice() || requestJob?.isActive == true) return
        thinkingEarly = true
        setBusy(Phase.THINKING)
        thinkingEarlyJob?.cancel()
        thinkingEarlyJob = viewModelScope.launch {
            delay(EARLY_THINKING_MAX_MILLIS) // финал так и не пришёл (ошибка STT) — не «думаем» вечно
            clearThinkingEarly(resetPhase = true)
        }
    }

    private fun clearThinkingEarly(resetPhase: Boolean) {
        if (!thinkingEarly) return
        thinkingEarly = false
        thinkingEarlyJob?.cancel()
        thinkingEarlyJob = null
        if (resetPhase) setBusy(null)
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        val t = turn ?: return
        val ms = clock() - t.origin
        when (event) {
            SpeechEvent.TTS_FIRST_BYTE -> if (t.ttsFirstByteMs == null) t.ttsFirstByteMs = ms
            SpeechEvent.AUDIO_START -> if (t.firstAudioMs == null) t.firstAudioMs = ms
        }
    }

    /**
     * Фраза посетителя. Пока робот занят, принимаем только «стоп» или новую содержательную фразу
     * (≥ 3 слов, не «угу/ага/да»); остальное — шум/реплики в сторону, игнорируем (в лог — без текста).
     */
    private fun onTranscript(t: Transcript) {
        val busyNow = busyForVoice()
        if (voiceIgnoredAsleep()) {
            if (t.isFinal && t.text.isNotBlank()) NoiseStats.ignoredAsleep()
            lastPartial = null
            return
        }
        if (!t.isFinal) {
            lastPartial = t.text.ifBlank { null }?.takeIf { it != "…" }
            if (busyNow) return
            // Шум не будит экран и не показывается: подпись появится, когда фраза станет осмысленной.
            if (lastPartial?.let { UtteranceFilter.isNoise(it, noiseContext()) } == true) return
            wake()
            if (_state.value.partialQuestion == null) refreshPlaceHint()
            _state.update { it.copy(partialQuestion = t.text.ifBlank { null }) }
            return
        }
        lastPartial = null
        val text = t.text.trim()
        if (text.isEmpty()) {
            clearThinkingEarly(resetPhase = true)
            utteranceEndAt = null
            if (_state.value.partialQuestion != null) _state.update { it.copy(partialQuestion = null) }
            return
        }
        clearThinkingEarly(resetPhase = false)
        if (UtteranceFilter.isStopCommand(text)) {
            Timber.i("voice command: stop (phase=%s)", _state.value.phase)
            _state.update { it.copy(partialQuestion = null) }
            stop()
            return
        }
        // Робот предложил проводить («Проводить вас туда?»): «да / давай / проводи» — едем сразу и без сервера,
        // даже если робот ещё договаривает (ответ посетителя почти всегда накладывается на конец фразы).
        if (_state.value.guideTarget != null && navJob?.isActive != true && UtteranceFilter.isGuideConfirmation(text)) {
            Timber.i("guide confirmed by voice (phase=%s)", _state.value.phase)
            _state.update { it.copy(partialQuestion = null) }
            guideToOffered()
            return
        }
        // Команда роботу («проводи к лифту», «где кафе») стоит прерывания, даже если она короткая.
        val command = IntentRouter.route(text).intent != IntentRouter.Intent.ASK
        if (busyNow && !UtteranceFilter.isSubstantial(text) && !command) {
            Timber.i("utterance ignored in %s (len=%d)", _state.value.phase, text.length)
            NoiseStats.utteranceFiltered("busy_" + _state.value.phase.name.lowercase())
            return
        }
        // Шум вместо реплики — во всех фазах, и в покое: на сервер не отправляем, лицо без реакции.
        UtteranceFilter.noiseReason(text, noiseContext())?.let { reason ->
            NoiseStats.utteranceFiltered(reason.name.lowercase())
            clearThinkingEarly(resetPhase = true)
            utteranceEndAt = null
            if (_state.value.partialQuestion != null) _state.update { it.copy(partialQuestion = null) }
            return
        }
        // Разговор голосом — лицом к лицу: из меню возвращаемся к лицу (agent).
        if (_state.value.mode == ScreenMode.MENU && awakeMode() == ScreenMode.FACE) _state.update { it.copy(mode = ScreenMode.FACE) }
        submitInternal(text, faqId = null, lang = t.lang, fromVoice = true)
    }

    /** /v1/stt не прошёл из-за связи: бейдж + «нет связи» голосом (не чаще раза в минуту и не перебивая). */
    private fun onConnectionLost() {
        _state.update { it.copy(offline = true, partialQuestion = null) }
        val now = clock()
        if (now - lastNoConnectionPhrase < NO_CONNECTION_REPEAT_MILLIS) return
        if (busyForVoice() || requestJob?.isActive == true) return
        lastNoConnectionPhrase = now
        val lang = _state.value.lang
        cue(FaceCue.ERROR)
        requestJob = viewModelScope.launch { say(phrases.get(PhraseKey.NO_CONNECTION, lang), offline = true) }
    }

    private fun onConnectionRestored() {
        Timber.i("connection restored")
        _state.update { it.copy(offline = false) }
        if (!quickFromServer) loadQuickQuestions()
    }

    /** Сменили адрес сервера (экран диагностики) — перечитать кнопки и места. */
    fun refreshFromServer() {
        chatUnsupported = false
        loadQuickQuestions()
        refreshPlaces()
    }

    /** Касание экрана / голос: убрать заставку и перезапустить таймер бездействия. */
    fun wake() {
        if (_state.value.mode == ScreenMode.SLEEP) _state.update { it.copy(mode = awakeMode()) }
        ensureLive(greet = false)
        restartIdleTimer()
        if (_state.value.mode == ScreenMode.MENU) restartMenuTimer()
    }

    private fun restartIdleTimer(timeoutOverride: Long? = null) {
        val timeout = timeoutOverride ?: screensaverMillis ?: return
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(timeout)
            val s = _state.value
            if (phaseIdle(s.phase) && navJob?.isActive != true && requestJob?.isActive != true && !visitorPresent) {
                goIdle()
            } else {
                restartIdleTimer()
            }
        }
    }

    /** Режим «проснувшегося» экрана: agent — лицо, legacy — прежнее меню. */
    private fun awakeMode(): ScreenMode = if (chatMode().usesAgent) ScreenMode.FACE else ScreenMode.MENU

    /** Кнопка «Меню» / жест на лице: плитки и частые вопросы. Без касаний [MENU_IDLE_MILLIS] — назад к лицу. */
    fun openMenu() {
        wake()
        if (_state.value.mode != ScreenMode.MENU) _state.update { it.copy(mode = ScreenMode.MENU) }
        restartMenuTimer()
    }

    /** «К роботу»: из меню обратно к лицу (только agent). */
    fun showFace() {
        wake()
        menuJob?.cancel()
        if (awakeMode() == ScreenMode.FACE && _state.value.mode == ScreenMode.MENU) _state.update { it.copy(mode = ScreenMode.FACE) }
    }

    /** Сменили режим разговора на экране диагностики. */
    fun onSettingsChanged() {
        val mode = _state.value.mode
        if (mode != ScreenMode.SLEEP && mode != awakeMode()) _state.update { it.copy(mode = awakeMode()) }
        if (chatMode() != ChatMode.LIVE) {
            closeLive()
        } else if (mode != ScreenMode.SLEEP) {
            liveDownUntil = Long.MIN_VALUE // включили live в диагностике — пробуем сразу
            ensureLive(greet = false)
        }
    }

    private fun restartMenuTimer() {
        menuJob?.cancel()
        if (awakeMode() != ScreenMode.FACE) return
        menuJob = viewModelScope.launch {
            delay(MENU_IDLE_MILLIS)
            val s = _state.value
            if (s.mode == ScreenMode.MENU && !s.processing && requestJob?.isActive != true) {
                _state.update { it.copy(mode = ScreenMode.FACE) }
            } else if (s.mode == ScreenMode.MENU) {
                restartMenuTimer()
            }
        }
    }

    /** Заставка: сброс сессии и экрана к начальному виду (шаг 7.4). */
    private fun goIdle() {
        Timber.i("idle → screensaver, session reset")
        closeLive()
        sessionId = newSessionId()
        langChosenExplicitly = false
        val langChanged = _state.value.lang != Lang.RU
        emotionJob?.cancel()
        menuJob?.cancel()
        head?.release()
        _state.update {
            it.copy(
                lang = Lang.RU, question = null, partialQuestion = null, answer = null, subtitle = null,
                guideTarget = null, offline = false, mode = ScreenMode.SLEEP, emotion = Emotion.NEUTRAL, wayHint = null,
                screen = Screen.HOME, faqPage = 0, faqOpenId = null, citations = emptyList(),
            )
        }
        history.clear()
        if (langChanged) {
            voice.setLanguage(Lang.RU)
            loadQuickQuestions()
        }
        refreshPlaces()
    }

    fun selectLanguage(lang: Lang) {
        langChosenExplicitly = true
        if (lang == _state.value.lang) return
        _state.update { it.copy(lang = lang) }
        voice.setLanguage(lang)
        loadQuickQuestions()
        // Язык живой сессии задаётся в `hello`: переоткрываем (память разговора — по session_id на сервере).
        if (live != null) {
            closeLive()
            ensureLive(greet = false)
        }
    }

    /** Вопрос текстом (поле ввода на эмуляторе) или финальный ASR: ответ — на экране ответа. */
    fun submit(text: String) = submitInternal(text, faqId = null)

    /** Плитка или кнопка частого вопроса. */
    fun submitButton(text: String) = submitInternal(text, faqId = null)

    private fun submitInternal(text: String, faqId: String?, lang: Lang? = null, fromVoice: Boolean = false) {
        val question = text.trim()
        if (question.isEmpty()) {
            if (thinkingEarly) clearThinkingEarly(resetPhase = true)
            return
        }
        // Повторное нажатие той же кнопки, пока ответ ещё готовится, — игнорируем (на роботе жали 6 раз подряд).
        val current = _state.value
        if (current.processing && requestJob?.isActive == true && current.question == question) {
            Timber.d("duplicate question ignored: %s", LogSafe.redact(question))
            return
        }
        touchSession()
        wake()
        voice.stopSpeaking()
        // Вопрос кнопкой/текстом при живом режиме: звук live обрываем, отвечает agent (/v1/chat, та же сессия).
        live?.interrupt()
        requestJob?.cancel()
        if (faqId == null) {
            // Вопрос из любого места → экран ответа; прежний экран запоминаем для «Назад».
            remember()
            _state.update { it.copy(screen = Screen.ANSWER, faqOpenId = null) }
        } else {
            _state.update { it.copy(screen = Screen.FAQ, faqOpenId = faqId) }
        }
        val now = clock()
        val vadEnd = utteranceEndAt?.takeIf { fromVoice && now - it in 0..UTTERANCE_END_FRESH_MILLIS }
        utteranceEndAt = null
        val clockForTurn = TurnClock(origin = vadEnd ?: now, sttFinalMs = vadEnd?.let { now - it }, lang = lang ?: _state.value.lang)
        turn = clockForTurn
        requestJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        question = question, partialQuestion = null, answer = null, subtitle = null, guideTarget = null,
                        wayHint = null, citations = emptyList(),
                    )
                }
                MoodRules.forUtterance(question)?.let { showEmotion(it) }
                if (chatMode().usesAgent && !_state.value.offline && !chatUnsupported) {
                    handleAgent(question, lang ?: _state.value.lang)
                } else {
                    clockForTurn.mode = TurnMetrics.MODE_ASK
                    handleLegacy(question)
                }
            } catch (e: CancellationException) {
                clockForTurn.interrupted = true
                throw e
            } finally {
                if (turn === clockForTurn) turn = null
                sendTurnMetrics(clockForTurn)
            }
        }
    }

    /** `POST /v1/metrics/turn` фоном; ошибка — только в лог. Текстов нет. */
    private fun sendTurnMetrics(t: TurnClock) {
        val metrics = TurnMetrics(
            sessionId = sessionId,
            requestId = t.requestId,
            lang = t.lang,
            mode = t.mode,
            sttFinalMs = t.sttFinalMs,
            chatFirstEventMs = t.chatFirstEventMs,
            firstPhraseMs = t.firstPhraseMs,
            ttsFirstByteMs = t.ttsFirstByteMs,
            firstAudioMs = t.firstAudioMs,
            vadSilenceMs = if (t.mode == TurnMetrics.MODE_LIVE) null else vadSilenceMillis(),
            fallback = t.fallback,
            interrupted = t.interrupted,
        )
        Timber.i(
            "turn %s: stt=%s chat=%s phrase=%s ttsByte=%s audio=%s fallback=%s interrupted=%s",
            metrics.mode, metrics.sttFinalMs, metrics.chatFirstEventMs, metrics.firstPhraseMs,
            metrics.ttsFirstByteMs, metrics.firstAudioMs, metrics.fallback, metrics.interrupted,
        )
        viewModelScope.launch {
            try {
                val r = backend.postTurnMetrics(metrics)
                if (r is ApiResult.Failure) Timber.w("turn metrics: %s", r.error.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "turn metrics failed")
            }
        }
    }

    /** Прежний путь (legacy и запасной для agent): локальные правила + `/v1/ask` / `/v1/look`. */
    private suspend fun handleLegacy(question: String) {
        val routed = IntentRouter.route(question)
        Timber.d("intent %s subject=%s", routed.intent, LogSafe.redact(routed.subject))
        when (routed.intent) {
            IntentRouter.Intent.LOOK -> handleLook(question)
            IntentRouter.Intent.WHERE_IS -> handleWhereIs(question, routed.subject)
            IntentRouter.Intent.GUIDE_TO -> handleGuide(question, routed.subject)
            IntentRouter.Intent.WHERE_AM_I -> handleWhereAmI(question)
            IntentRouter.Intent.ASK -> handleAsk(question)
        }
    }

    /** «Где X?» — локально; место не узнали → вопрос к серверу. */
    private suspend fun handleWhereIs(question: String, subject: String) {
        val wf = wayFinder ?: return handleAsk(question)
        turn?.mode = TurnMetrics.MODE_LOCAL
        setBusy(Phase.THINKING)
        val answer = wf.whereIs(subject.ifBlank { question }, _state.value.lang)
            ?: return handleAsk(question)
        setBusy(null)
        _state.update { it.copy(answer = answer.text, subtitle = null, guideTarget = answer.guideTarget) }
        postLocal(LocalMessage.KIND_WHERE_IS, question, answer.text, answer.place?.id)
        speakPhrases(PhraseSplitter.split(answer.text), _state.value.lang)
    }

    private suspend fun handleWhereAmI(question: String) {
        val wf = wayFinder ?: return handleAsk(question)
        turn?.mode = TurnMetrics.MODE_LOCAL
        setBusy(Phase.THINKING)
        val answer = wf.whereAmI(_state.value.lang)
        setBusy(null)
        postLocal(LocalMessage.KIND_WHERE_AM_I, question, answer.text, answer.place?.id)
        say(answer.text, offline = _state.value.offline)
    }

    /** «Проводи до X» — сразу ведём; место не узнали → честно говорим (без сервера это не решить). */
    private suspend fun handleGuide(question: String, subject: String) {
        val wf = wayFinder ?: return handleAsk(question)
        turn?.mode = TurnMetrics.MODE_LOCAL
        setBusy(Phase.THINKING)
        val place = wf.find(subject.ifBlank { question })
        setBusy(null)
        if (place == null) {
            val text = WayPhrases.unknownPlace(_state.value.lang)
            cue(FaceCue.CONFUSED)
            postLocal(LocalMessage.KIND_GUIDE_TO, question, text, null, found = false)
            say(text, offline = _state.value.offline)
            return
        }
        postLocal(LocalMessage.KIND_GUIDE_TO, question, WayPhrases.guideStart(WayFinder.displayName(place, _state.value.lang), _state.value.lang), place.id)
        startGuide(place, source = "«проводи» (локально)")
    }

    /** Кнопка «Проводить» под ответом. */
    fun guideToOffered() {
        val place = _state.value.guideTarget ?: return
        wake()
        startGuide(place, source = "кнопка «Проводить» / «да»")
    }

    /**
     * Ведём к [place]. Если уже едем — сначала гасим прежнюю поездку и ждём от робота подтверждения остановки
     * (или [RobotGateway.STOP_CONFIRM_TIMEOUT_MS]), только потом startNavigation.
     * Все фразы поездки (старт, препятствие, прибытие) — внутри navJob и отменяются вместе с ним.
     * [announce] — текст старта на экране, [speakStart] — и голосом (live: говорит сервер, робот молчит).
     * Фаза NAVIGATING держится ровно пока робот едет ([tripActive]) и сбрасывается при любом исходе.
     */
    private fun startGuide(place: Place, announce: Boolean = true, speakStart: Boolean = announce, source: String = "ui") {
        val nav = robot ?: run {
            NavJournal.log("поездка к ${place.id}: нет RobotGateway — не едем")
            return
        }
        val lang = _state.value.lang
        val name = WayFinder.displayName(place, lang)
        voice.stopSpeaking()
        val previous = navJob
        guidingPlaceId = place.id
        NavJournal.log("поездка: запрос к ${place.id} («${place.mapName}»), источник=$source")
        lateinit var job: Job
        job = viewModelScope.launch {
            if (previous?.isActive == true) {
                previous.cancelAndJoin()
                val confirmed = nav.stopNavigationAndWait(RobotGateway.STOP_CONFIRM_TIMEOUT_MS)
                Timber.i("previous navigation stopped (confirmed=%s)", confirmed)
                NavJournal.log("прежняя поездка остановлена (подтверждено=$confirmed)")
            }
            _state.update {
                it.copy(
                    guideTarget = null, guidingTo = name, subtitle = null,
                    answer = if (announce) WayPhrases.guideStart(name, lang) else it.answer,
                )
            }
            tripActive = true
            setBusy(Phase.NAVIGATING)
            // Слежение головой занимает шасси — на время поездки выключаем (одно устройство — одно API).
            head?.setNavigating(true)
            live?.sendState(placeHint, navigating = true)
            val trip = this
            // Фраза старта звучит параллельно с началом движения; агент («Провожаю вас…») её уже сказал.
            val startSpeech = launch { if (speakStart) voice.speak(listOf(WayPhrases.guideStart(name, lang)), lang) }
            var obstacleSpeech: Job? = null
            val result = try {
                // Страховка поверх таймаута шлюза: поездка не может «висеть» вечно (колбэк RobotApi потерялся).
                withTimeoutOrNull(MAX_TRIP_MILLIS) {
                    nav.navigateTo(place.mapName) { event ->
                        if (event == NavEvent.OBSTACLE) {
                            // Колбэк SDK может прийти из чужого потока: переходим в поездку, не копим фразы.
                            trip.launch {
                                if (obstacleSpeech?.isActive != true) {
                                    obstacleSpeech = launch { voice.speak(listOf(WayPhrases.guideObstacle(lang)), lang) }
                                }
                            }
                        }
                    }
                } ?: run {
                    NavJournal.log("поездка: нет итога за ${MAX_TRIP_MILLIS / 1000} с — stopNavigation")
                    nav.stopNavigation()
                    NavigationResult.Failed(TRIP_TIMEOUT_CODE, "trip timeout (view model)")
                }
            } catch (e: CancellationException) {
                NavJournal.log("поездка к ${place.id}: отменена приложением")
                throw e
            } finally {
                tripActive = false
                head?.setNavigating(false)
                live?.sendState(placeHint, navigating = false)
                // Робот уже в другом месте: свежие фразы «где X» серверу (любой исход поездки).
                liveDirections?.let { reporter -> viewModelScope.launch { reporter.report(DirectionsReason.AFTER_TRIP) } }
                _state.update { it.copy(guidingTo = null) }
                setBusy(null)
            }
            NavJournal.log("поездка к ${place.id}: итог ${describe(result)}")
            cue(tripCue(result))
            startSpeech.join()
            obstacleSpeech?.join()
            val text = when (result) {
                NavigationResult.Arrived -> WayPhrases.guideArrived(name, lang)
                NavigationResult.AlreadyThere -> WayPhrases.guideAlreadyThere(name, lang)
                NavigationResult.Cancelled -> WayPhrases.guideCancelled(lang)
                NavigationResult.NotLocalized -> WayPhrases.guideNotLocalized(lang)
                NavigationResult.UnknownPlace -> WayPhrases.notOnMap(name, null, lang)
                is NavigationResult.Failed -> {
                    Timber.w("navigation failed: %s", result)
                    WayPhrases.guideFailed(lang)
                }
            }
            say(text, offline = _state.value.offline)
            restartIdleTimer()
        }
        navJob = job
        job.invokeOnCompletion {
            // Поездка закончилась любым путём (итог, «Стоп», новая поездка, сбой) — фаза не остаётся NAVIGATING.
            if (navJob === job) {
                guidingPlaceId = null
                tripActive = false
            }
            refreshPhase()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Навигация по экранам: частые вопросы, «Назад», «В начало».
    // ---------------------------------------------------------------------------------------------

    /** Плитка «Частые вопросы»: список вопросов-аккордеон, первая страница. */
    fun openFaq() {
        wake()
        if (_state.value.screen == Screen.FAQ) return
        stopActivity()
        remember()
        _state.update { it.copy(screen = Screen.FAQ, faqPage = 0, faqOpenId = null) }
    }

    /** «Ещё вопросы»: следующая страница (по кругу). Текущую страницу можно вернуть кнопкой «Назад». */
    fun faqNextPage() {
        wake()
        val s = _state.value
        if (s.screen != Screen.FAQ || s.faqPageCount < 2) return
        stopActivity()
        remember()
        _state.update { it.copy(faqPage = (s.faqPage + 1) % s.faqPageCount, faqOpenId = null, question = null, answer = null, guideTarget = null) }
    }

    /** Нажатие на вопрос в списке: раскрыть (и спросить сервер) или свернуть уже раскрытый. */
    fun faqToggle(item: QuickQuestion) {
        wake()
        if (_state.value.faqOpenId == item.id) {
            stopActivity()
            _state.update { it.copy(faqOpenId = null, question = null, answer = null, guideTarget = null) }
            return
        }
        submitInternal(item.text, faqId = item.id)
    }

    /** «Назад»: на предыдущий экран без повторной озвучки; истории нет — на приветствие. */
    fun back() {
        wake()
        stopActivity()
        val prev = history.removeLastOrNull() ?: return goHome()
        _state.update {
            it.copy(
                screen = prev.screen, faqPage = prev.faqPage, faqOpenId = null,
                question = prev.question, answer = prev.answer, guideTarget = prev.guideTarget, partialQuestion = null,
            )
        }
    }

    /** «В начало»: приветствие, история сбрасывается. */
    fun goHome() {
        wake()
        stopActivity()
        history.clear()
        _state.update {
            it.copy(
                screen = Screen.HOME, faqPage = 0, faqOpenId = null,
                question = null, answer = null, guideTarget = null, partialQuestion = null,
            )
        }
    }

    /** Запомнить текущий экран для «Назад» (повтор того же экрана не копим). */
    private fun remember() {
        val s = _state.value
        val snap = Snapshot(s.screen, s.faqPage, s.question.takeIf { s.screen == Screen.ANSWER }, s.answer.takeIf { s.screen == Screen.ANSWER }, s.guideTarget.takeIf { s.screen == Screen.ANSWER })
        if (snap.screen == Screen.ANSWER && snap.answer == null) return // ответ ещё не пришёл — возвращаться некуда
        if (history.lastOrNull() == snap) return
        history.addLast(snap)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    /** Остановить запрос и речь при уходе с экрана. Поездку не трогаем: её останавливает только «Стоп». */
    private fun stopActivity() {
        requestJob?.cancel()
        voice.stopSpeaking()
        setBusy(null)
    }

    /**
     * Посетитель заговорил (VAD) — разбудить сразу, не дожидаясь первого распознанного слова. Те же правила, что у
     * распознанной речи: спит, а PersonApi рядом никого не видит, — голос не будит (разговоры вдалеке).
     */
    private fun onVisitorVoice() {
        if (_state.value.mode != ScreenMode.SLEEP || voiceIgnoredAsleep()) return
        Timber.i("visitor voice (VAD) → wake")
        wake()
    }

    /**
     * Тап по лицу в поездке, пока робот говорит: замолчать (фраза пути, приезд), а поездка продолжается —
     * её останавливает только «Стоп» на острове.
     */
    fun interruptSpeech() {
        wake()
        voice.stopSpeaking()
        live?.interrupt()
        if (_state.value.emotion != Emotion.NEUTRAL) relaxEmotion()
    }

    /** Звучит ли голос робота сейчас (своя озвучка или live-сервер). В поездке фаза NAVIGATING речь не показывает. */
    val robotSpeaking: Boolean
        get() = voice.activity.value == VoiceActivity.SPEAKING || live?.playing == true

    /** Только отладка на эмуляторе (FaceDebug, adb broadcast): уснуть сейчас, не дожидаясь таймера. */
    internal fun sleepNow() {
        if (_state.value.mode != ScreenMode.SLEEP) goIdle()
    }

    /** Кнопка «Что это?». */
    fun lookAround() = submitButton(LOOK_DEFAULT_QUESTION.getValue(_state.value.lang))

    fun stop() {
        wake()
        clearThinkingEarly(resetPhase = false)
        requestJob?.cancel()
        voice.stopSpeaking()
        live?.interrupt()
        liveGuides.clear()
        if (stopNavigationOnly()) {
            cue(FaceCue.TRIP_CANCELLED)
            viewModelScope.launch { say(WayPhrases.guideCancelled(_state.value.lang), offline = _state.value.offline) }
        }
        setBusy(null)
        // Ход оборван — эмоция ответа не должна застрять на лице: спокойное лицо через обычную паузу.
        if (_state.value.emotion != Emotion.NEUTRAL) relaxEmotion()
    }

    /** Остановить поездку (кнопка «Стоп», голосовое «стоп»). true — ехали. */
    private fun stopNavigationOnly(): Boolean {
        if (navJob?.isActive != true) return false
        navJob?.cancel()
        robot?.stopNavigation()
        _state.update { it.copy(guidingTo = null) }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Посетитель подошёл / ушёл (задача 14) и голова (задача 15).
    // ---------------------------------------------------------------------------------------------

    private fun onPresence(event: PresenceEvent) {
        when (event) {
            is PresenceEvent.Arrived -> {
                visitorPresent = true
                head?.follow(event.personId)
                val wasSleeping = _state.value.mode == ScreenMode.SLEEP
                if (wasSleeping) {
                    // Новый посетитель — новый разговор (goIdle уже сменил session_id, но экран мог проснуться иначе).
                    sessionId = newSessionId()
                    langChosenExplicitly = false
                }
                wake()
                showEmotion(MoodRules.visitorArrived)
                cue(FaceCue.GREET)
                // Приложение не на экране — не здороваемся (микрофон и голос выключены, говорить «из фона» нельзя).
                if (event.greet && !busyNow() && inForeground()) {
                    // Живой режим: здоровается агент на сервере (`visitor_arrived`); live не открылся — как раньше.
                    if (!ensureLive(greet = true)) greet()
                }
            }
            is PresenceEvent.FaceId -> head?.follow(event.personId)
            PresenceEvent.Left -> {
                visitorPresent = false
                // Ведём к точке: посетитель идёт сзади, камера его не видит — голову не трогаем.
                if (navJob?.isActive == true) return
                // Уход посетителя экран не усыпляет (мешало входу в диагностику) — только таймер бездействия.
                head?.release()
            }
        }
    }

    private fun busyNow(): Boolean =
        requestJob?.isActive == true || navJob?.isActive == true || !phaseIdle(_state.value.phase) ||
            live?.playing == true || liveLookJob?.isActive == true

    /** Покой: IDLE; в живом режиме и «слушаю» — покой (микрофон открыт всегда). */
    private fun phaseIdle(phase: Phase): Boolean = phase == Phase.IDLE || (live != null && phase == Phase.LISTENING)

    /** Приветствие подошедшему: агент (`visitor_arrived`), не ответил за 2 с или нет связи — своя фраза. */
    private fun greet() {
        val lang = _state.value.lang
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val agent = chatMode().usesAgent && !_state.value.offline && !chatUnsupported
            val outcome = if (agent) {
                runAgentTurn(
                    question = null, event = ChatRequest.EVENT_VISITOR_ARRIVED, lang = lang,
                    firstPhraseMillis = chatTimeouts.greetingPhraseMillis, showThinking = false,
                )
            } else null
            if (outcome == null || outcome is TurnOutcome.Fallback) {
                say(phrases.get(PhraseKey.GREETING, lang), offline = _state.value.offline)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Агентский чат: POST /v1/chat (SSE) → эмоции, фразы, действия. Таймауты → запасной путь.
    // ---------------------------------------------------------------------------------------------

    private suspend fun handleAgent(question: String, lang: Lang) {
        val outcome = runAgentTurn(question, event = null, lang, chatTimeouts.firstPhraseMillis)
        if (outcome is TurnOutcome.Fallback) {
            Timber.w("agent fallback: %s (%s)", outcome.reason, outcome.error?.kind)
            if (outcome.error?.httpStatus == 404) {
                Timber.w("server without /v1/chat, using /v1/ask")
                chatUnsupported = true
            }
            turn?.let {
                it.fallback = true
                it.mode = TurnMetrics.MODE_ASK
            }
            handleLegacy(question)
        }
    }

    /** Что озвучить или сделать по порядку событий хода (фразы и where_is — строго друг за другом). */
    private sealed interface TurnStep {
        data class Say(val text: String, val lang: Lang) : TurnStep
        class Run(val block: suspend () -> Unit) : TurnStep
    }

    /**
     * Один ход агента (`POST /v1/chat`, SSE). Фразы озвучиваются по мере прихода (очередь [TurnStep]), текст копится
     * в `answer`, текущая фраза — в `subtitle`. Действия:
     * - `where_is` — в очереди на своём месте (до фраз, которые его описывают): направление по WayFinder робота
     *   на экране и голосом, кнопка «Проводить»; робот не едет;
     * - `guide_to` — едем, когда доиграла последняя фраза ответа;
     * - `look` — кадр снимается сразу и уходит в `POST /v1/look` (вопрос — реплика посетителя); описание звучит
     *   после фраз агента («Сейчас посмотрю.»).
     * Отмена (новая реплика, «Стоп») обрывает SSE: сервер пишет ход с `interrupted: true`.
     */
    private suspend fun runAgentTurn(
        question: String?,
        event: String?,
        lang: Lang,
        firstPhraseMillis: Long,
        showThinking: Boolean = true,
    ): TurnOutcome = coroutineScope {
        if (showThinking) setBusy(Phase.THINKING)
        val steps = Channel<TurnStep>(Channel.UNLIMITED)
        val pending = AtomicInteger(0)
        var streaming = true
        val spoken = mutableListOf<String>()
        var guide: ChatAction? = null
        var look: Deferred<ApiResult<Answer>?>? = null

        suspend fun sayPart(part: String, partLang: Lang) {
            spoken += part
            _state.update { it.copy(answer = spoken.joinToString(" "), subtitle = part) }
            setBusy(null)
            voice.speak(listOf(part), partLang)
            touchSession()
        }
        val say: suspend (String, Lang) -> Unit = { part, partLang -> sayPart(part, partLang) }

        fun stepDone(count: Int) {
            if (pending.addAndGet(-count) == 0 && streaming && showThinking && navJob?.isActive != true) setBusy(Phase.THINKING)
        }

        // Подряд идущие фразы — одна непрерывная озвучка: голос склеивает их в куски ≤ 300 символов
        // и готовит следующий кусок, пока звучит текущий (без пауз между фразами).
        val chunkListener = object : SpeechListener {
            override fun onChunkStart(chunk: SpeechChunk) {
                spoken += chunk.phrases
                _state.update { it.copy(answer = spoken.joinToString(" "), subtitle = chunk.text) }
                setBusy(null)
            }

            override fun onChunkDone(chunk: SpeechChunk, completed: Boolean) {
                touchSession()
                stepDone(chunk.phrases.size)
            }
        }

        val speaker = launch {
            var stream: Channel<String>? = null
            var streamLang: Lang? = null
            var streamJob: Job? = null
            suspend fun finishStream() {
                stream?.close()
                streamJob?.join()
                stream = null
                streamJob = null
            }
            for (step in steps) {
                when (step) {
                    is TurnStep.Say -> {
                        // Прервали (озвучка вернула false) или сменился язык — следующая фраза открывает новую озвучку.
                        if (stream == null || streamLang != step.lang || streamJob?.isActive != true) {
                            finishStream()
                            val channel = Channel<String>(Channel.UNLIMITED)
                            stream = channel
                            streamLang = step.lang
                            streamJob = launch { voice.speakStream(channel, step.lang, chunkListener) }
                        }
                        stream?.trySend(step.text)
                    }
                    is TurnStep.Run -> {
                        finishStream() // where_is — строго после уже пришедших фраз
                        step.block()
                        stepDone(1)
                    }
                }
            }
            finishStream()
        }

        fun enqueue(step: TurnStep) {
            pending.incrementAndGet()
            steps.trySend(step)
        }

        val scope: CoroutineScope = this
        val clockForTurn = turn.takeIf { question != null }
        fun markEvent(phrase: Boolean) {
            val t = clockForTurn ?: return
            val ms = clock() - t.origin
            if (t.chatFirstEventMs == null) t.chatFirstEventMs = ms
            if (phrase && t.firstPhraseMs == null) t.firstPhraseMs = ms
        }

        // Пока первая фраза агента не пришла — только лицо «думаю» (THINKING), никаких «хм…» голосом.

        val handler = object : TurnHandler {
            override fun onEmotion(name: String) {
                markEvent(phrase = false)
                Emotion.fromName(name)?.let(::setEmotion)
            }

            override fun onPhrase(text: String) {
                markEvent(phrase = true)
                PhraseSplitter.normalize(listOf(text)).forEach { enqueue(TurnStep.Say(it, lang)) }
            }

            override fun onAction(action: ChatAction) {
                markEvent(phrase = false)
                Timber.i("agent action %s place=%s", action.type, action.placeId)
                when (action.type) {
                    ChatAction.WHERE_IS -> enqueue(TurnStep.Run { agentWhereIs(action, lang, say) })
                    ChatAction.GUIDE_TO -> guide = action
                    ChatAction.LOOK -> {
                        if (question.isNullOrBlank() || look != null) return
                        setEmotion(MoodRules.looking)
                        look = scope.async { lookRequest(question) }
                    }
                    else -> Timber.w("unknown agent action: %s", action.type)
                }
            }
        }

        val request = ChatRequest(
            sessionId = sessionId,
            question = question.orEmpty(),
            lang = lang,
            placeId = quickPlaceId(),
            event = event,
        )
        val outcome = try {
            turnRunner.run(request, handler, firstPhraseMillis)
        } finally {
            streaming = false
            steps.close()
        }
        if (outcome is TurnOutcome.Fallback) {
            speaker.cancel()
            look?.cancel()
        } else {
            speaker.join()
        }
        look?.takeIf { outcome !is TurnOutcome.Fallback }?.let { agentLookResult(it, lang, say) }
        if (showThinking) setBusy(null)
        when (outcome) {
            is TurnOutcome.Completed -> if (outcome.done.ignored) {
                // v1.2: сервер счёл реплику шумом — молчим, лицо без реакции, запасного пути нет.
                clockForTurn?.requestId = outcome.done.requestId
                NoiseStats.utteranceFiltered("server_ignored")
                emotionJob?.cancel()
                _state.update {
                    it.copy(question = null, partialQuestion = null, answer = null, subtitle = null, emotion = Emotion.NEUTRAL)
                }
                val prev = history.removeLastOrNull()
                if (prev != null && _state.value.screen == Screen.ANSWER) {
                    _state.update {
                        it.copy(
                            screen = prev.screen, faqPage = prev.faqPage, question = prev.question,
                            answer = prev.answer, guideTarget = prev.guideTarget,
                        )
                    }
                }
            } else {
                clockForTurn?.requestId = outcome.done.requestId
                _state.update { it.copy(offline = false, citations = outcome.done.citations) }
                val doneLang = outcome.done.lang
                if (!langChosenExplicitly && doneLang != null && doneLang != _state.value.lang) {
                    _state.update { it.copy(lang = doneLang) }
                    voice.setLanguage(doneLang)
                    loadQuickQuestions()
                }
                if (!quickFromServer) loadQuickQuestions()
            }
            is TurnOutcome.Broken -> Timber.w("agent turn broken after reply: %s", outcome.reason)
            is TurnOutcome.Fallback -> Unit
        }
        if (outcome !is TurnOutcome.Fallback && !(outcome is TurnOutcome.Completed && outcome.done.ignored)) {
            relaxEmotion()
            // Согласие посетителя агент уже получил; едем после последней фразы ответа.
            guide?.let { agentGuide(it, lang, announce = spoken.isEmpty(), say) }
        }
        outcome
    }

    /**
     * `action: where_is` — робот сам считает направление по своей карте, показывает и озвучивает, под ответом —
     * кнопка «Проводить». Места нет в кеше робота — молчим (агент о нём уже говорит). В историю не пишем: ход агента.
     */
    private suspend fun agentWhereIs(action: ChatAction, lang: Lang, say: suspend (String, Lang) -> Unit) {
        val wf = wayFinder ?: return
        val place = wf.placeFor(action.placeId, null)
        if (place == null) {
            Timber.w("agent where_is: unknown place %s", action.placeId)
            return
        }
        val answer = wf.describe(place, lang)
        _state.update { it.copy(guideTarget = answer.guideTarget) }
        PhraseSplitter.split(answer.text).forEach { say(it, lang) }
    }

    /** `action: guide_to` после фраз ответа (фразы в пути — как у кнопки «Проводить»). */
    private suspend fun agentGuide(action: ChatAction, lang: Lang, announce: Boolean, say: suspend (String, Lang) -> Unit) {
        val wf = wayFinder ?: return
        val place = wf.placeFor(action.placeId, null)
        if (place == null) {
            Timber.w("agent guide_to: unknown place %s", action.placeId)
            NavJournal.log("agent guide_to place=${action.placeId}: точки нет в кеше мест робота — не едем")
            cue(FaceCue.CONFUSED)
            say(WayPhrases.unknownPlace(lang), lang)
            return
        }
        if (navJob?.isActive == true && guidingPlaceId == place.id) {
            NavJournal.log("agent guide_to place=${place.id} → игнор: уже едем к этой точке")
            return
        }
        startGuide(place, announce = announce, source = "agent guide_to")
    }

    /** Ответ `/v1/look` для `action: look`: описание кадра или готовая фраза ошибки. */
    private suspend fun agentLookResult(look: Deferred<ApiResult<Answer>?>, lang: Lang, say: suspend (String, Lang) -> Unit) {
        if (!look.isCompleted) setBusy(Phase.LOOKING)
        val result = look.await()
        setBusy(null)
        when (result) {
            null -> {
                cue(FaceCue.ERROR)
                say(phrases.get(PhraseKey.CAMERA_UNAVAILABLE, lang), lang)
            }
            is ApiResult.Success -> {
                val answerLang = result.value.lang ?: lang
                PhraseSplitter.normalize(result.value.phrases.ifEmpty { listOf(result.value.text) }).forEach { say(it, answerLang) }
            }
            is ApiResult.Failure -> {
                Timber.w("agent look: %s", result.error)
                showEmotion(MoodRules.trouble)
                troubleCue(result.error)
                if (result.error.isConnectivity()) lastNoConnectionPhrase = clock()
                say(phrases.get(phraseFor(result.error), lang), lang)
            }
        }
    }

    /** Снимок; на время снимка слежение головой выключено (камера и зрение робота не вместе). */
    private suspend fun captureFrame(quality: Int = CameraGateway.DEFAULT_JPEG_QUALITY): ByteArray? {
        head?.setCameraBusy(true)
        return try {
            camera.captureJpeg(quality)
        } finally {
            head?.setCameraBusy(false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Живой режим: WS /v1/live. Звук ответа — только с сервера; робот показывает лицо, субтитры, направление,
    // выполняет действия. Сбой → agent (/v1/chat + live STT + потоковый TTS), повтор live через [liveRetryMillis].
    // ---------------------------------------------------------------------------------------------

    /**
     * Открыть живую сессию, если выбран live и робот не спит. [greet] — агент здоровается (`visitor_arrived`).
     * @return true — live открыт (или уже был открыт); false — работаем по-старому (agent).
     */
    private fun ensureLive(greet: Boolean): Boolean {
        val factory = liveFactory ?: return false
        if (chatMode() != ChatMode.LIVE || _state.value.mode == ScreenMode.SLEEP || !inForeground()) return false
        live?.takeIf { it.active }?.let { session ->
            if (greet) {
                liveGreetPending = true
                session.visitorArrived()
            }
            return true
        }
        if (clock() < liveDownUntil) {
            // Повтор мог быть отменён сном — запланировать заново на остаток паузы.
            if (liveRetryJob?.isActive != true) scheduleLiveRetry(liveDownUntil - clock())
            return false
        }
        val session = factory.create(viewModelScope, sessionId, _state.value.lang) ?: return false
        Timber.i("live: opening session")
        live = session
        liveGreetPending = greet
        updateListeningPaused()
        liveJob = viewModelScope.launch { session.signals.collect { onLive(session, it) } }
        session.start(_state.value.lang, placeHint, greet)
        return true
    }

    /** Закрыть живую сессию штатно (сон, ушёл посетитель, сменили режим). */
    private fun closeLive() {
        liveRetryJob?.cancel()
        liveRetryJob = null
        val session = live ?: return
        live = null
        liveJob?.cancel()
        liveJob = null
        stopLiveDirections()
        session.close()
        resetLiveTurn()
        updateListeningPaused()
    }

    /**
     * Live готов: фразы «где X» от текущей позиции — серверу сразу, потом раз в [liveDirectionsPeriodMillis], если
     * робот сдвинулся ≥ 0,5 м / повернулся ≥ 20° (и после каждой поездки — в [startGuide]). Нет карты — не шлём.
     * ПРОВЕРИТЬ НА РОБОТЕ: позиция (getPosition) и точки карты (getPlaceList) — на эмуляторе заглушки.
     */
    private fun startLiveDirections(session: LiveSession) {
        stopLiveDirections()
        val wf = wayFinder ?: return
        val nav = robot ?: return
        val reporter = LiveDirectionsReporter(
            pose = { nav.getPosition() },
            build = { pose -> wf.directionsFrom(pose) },
            send = { directions -> session.sendDirections(directions) },
            clock = clock,
        )
        liveDirections = reporter
        liveDirectionsJob = viewModelScope.launch {
            reporter.report(DirectionsReason.START)
            liveDirectionsPeriodMillis?.let { reporter.run(it) }
        }
    }

    private fun stopLiveDirections() {
        liveDirectionsJob?.cancel()
        liveDirectionsJob = null
        liveDirections = null
    }

    private fun resetLiveTurn() {
        liveLookJob?.cancel()
        liveLookJob = null
        livePendingQuestion = null
        liveGreetPending = false
        liveGuides.clear()
        liveSpoken.clear()
        liveTurn = null
        liveActiveTurn = null
        localGuideConfirm = null
    }

    /**
     * Обычное прослушивание (live STT / `/v1/stt`) на паузе: робот спит без человека рядом или микрофон
     * у живого режима.
     */
    private fun updateListeningPaused() {
        val asleep = _state.value.mode == ScreenMode.SLEEP
        voice.setListeningPaused(!inForeground() || (asleep && presence != null && !visitorPresent) || live != null)
    }

    private fun onLive(session: LiveSession, signal: LiveSignal) {
        if (session !== live) return
        when (signal) {
            is LiveSignal.Ready -> {
                Timber.i("live: ready (aec=%s)", signal.aec)
                if (_state.value.offline) _state.update { it.copy(offline = false) }
                startLiveDirections(session)
            }
            LiveSignal.Listening -> if (!busy) _state.update { it.copy(phase = Phase.LISTENING) }
            LiveSignal.UserSpeaking -> {
                restartIdleTimer()
                if (!busy) _state.update { it.copy(phase = Phase.LISTENING) }
            }
            is LiveSignal.UserPartial -> {
                restartIdleTimer()
                if (signal.text.isNotBlank()) _state.update { it.copy(partialQuestion = signal.text) }
            }
            is LiveSignal.UserFinal -> onLiveUserFinal(signal.text, signal.lang)
            LiveSignal.Ignored -> onLiveIgnored()
            LiveSignal.Thinking -> {
                markLiveEvent(phrase = false)
                setBusy(Phase.THINKING)
            }
            is LiveSignal.TurnStart -> {
                liveTurnStarts++
                liveActiveTurn = signal.turnId
                livePendingQuestion = null
                liveGreetPending = false
                liveSpoken.clear()
                markLiveEvent(phrase = false)
                liveTurn?.requestId = signal.turnId
            }
            is LiveSignal.Emotion -> {
                markLiveEvent(phrase = false)
                Emotion.fromName(signal.name)?.let(::setEmotion)
            }
            is LiveSignal.Phrase -> {
                markLiveEvent(phrase = true)
                liveSpoken += signal.text
                _state.update { it.copy(answer = liveSpoken.joinToString(" "), subtitle = signal.text, partialQuestion = null) }
            }
            is LiveSignal.AudioStarted -> {
                liveTurn?.let { t -> if (t.firstAudioMs == null) t.firstAudioMs = clock() - t.origin }
                setBusy(Phase.SPEAKING)
            }
            is LiveSignal.Action -> onLiveAction(signal)
            is LiveSignal.TurnEnd -> {
                if (signal.citations.isNotEmpty()) _state.update { it.copy(citations = signal.citations) }
                val doneLang = signal.lang
                if (!langChosenExplicitly && doneLang != null && doneLang != _state.value.lang) {
                    _state.update { it.copy(lang = doneLang) }
                    voice.setLanguage(doneLang)
                    loadQuickQuestions()
                }
                liveTurn?.let { t -> t.lang = doneLang ?: t.lang }
            }
            is LiveSignal.Interrupted -> {
                Timber.i("live: interrupted (%s, local=%s)", signal.turnId, signal.local)
                if (signal.turnId == null || signal.turnId == liveActiveTurn) liveActiveTurn = null
                liveGuides.remove(signal.turnId)?.let {
                    NavJournal.log("live guide_to place=${it.placeId} turn=${signal.turnId}: ход оборван — не едем")
                }
                _state.update { it.copy(subtitle = null) }
                setBusy(null)
                finishLiveTurn(interrupted = true)
            }
            is LiveSignal.PlaybackDone -> {
                liveLastDone = signal.turnId
                if (signal.turnId == liveActiveTurn) liveActiveTurn = null
                touchLiveSession()
                setBusy(null)
                _state.update { it.copy(subtitle = null) }
                relaxEmotion()
                finishLiveTurn(interrupted = false)
                // Согласие агент уже получил; едем после того, как ответ доиграл (своей фразы старта нет).
                liveGuides.remove(signal.turnId)?.let { action ->
                    NavJournal.log("live guide_to place=${action.placeId} turn=${signal.turnId}: ход доигран → еду")
                    startLiveGuide(action)
                }
                restartIdleTimer()
            }
            is LiveSignal.Failed -> onLiveFailed(session, signal)
        }
    }

    private fun onLiveUserFinal(text: String, lang: Lang?) {
        val question = text.trim()
        restartIdleTimer()
        if (question.isEmpty()) {
            _state.update { it.copy(partialQuestion = null) }
            return
        }
        // «Стоп» во время поездки — останавливаемся (звук сервер оборвёт сам, это перебивание).
        if (UtteranceFilter.isStopCommand(question) && navJob?.isActive == true) {
            _state.update { it.copy(partialQuestion = null) }
            stop()
            return
        }
        touchLiveSession()
        if (_state.value.mode == ScreenMode.MENU && awakeMode() == ScreenMode.FACE) _state.update { it.copy(mode = ScreenMode.FACE) }
        // На экране предложение «Проводить?» (where_is), посетитель сказал «да / давай / проводи / иә / yes» —
        // едем сразу, не дожидаясь сервера. Проверка — ДО сброса guideTarget ниже.
        val offered = _state.value.guideTarget
        if (offered != null && navJob?.isActive != true && clock() - guideOfferedAt <= GUIDE_OFFER_WINDOW_MILLIS &&
            UtteranceFilter.isGuideConfirmation(question)
        ) {
            Timber.i("live: guide confirmed by voice locally")
            NavJournal.log("live: посетитель согласился («да») → еду к ${offered.id} сразу, сервер не ждём")
            localGuideConfirm = offered.id to clock()
            _state.update { it.copy(question = question, partialQuestion = null, wayHint = null) }
            // Голосом старт не говорим: на это «да» ответит сервер (live); текст старта — на экране.
            startGuide(offered, announce = true, speakStart = false, source = "live: «да» посетителя")
            return
        }
        remember()
        _state.update {
            it.copy(
                screen = Screen.ANSWER, faqOpenId = null, question = question, partialQuestion = null,
                answer = null, subtitle = null, guideTarget = null, wayHint = null, citations = emptyList(),
            )
        }
        if (!langChosenExplicitly && lang != null && lang != _state.value.lang) {
            _state.update { it.copy(lang = lang) }
            voice.setLanguage(lang)
            loadQuickQuestions()
        }
        MoodRules.forUtterance(question)?.let { showEmotion(it) }
        // Прошлый ход ещё не отчитан (перебили без `interrupted`) — отчитываем как прерванный.
        if (liveTurn != null) finishLiveTurn(interrupted = true)
        // Отсчёт хода — от `user_final`: это момент, когда сервер решил, что посетитель закончил.
        liveTurn = TurnClock(origin = clock(), sttFinalMs = null, lang = lang ?: _state.value.lang).apply {
            mode = TurnMetrics.MODE_LIVE
        }
        livePendingQuestion = question
    }

    /** Сервер счёл реплику шумом: лицо без реакции, экран как был. */
    private fun onLiveIgnored() {
        NoiseStats.utteranceFiltered("server_ignored")
        val pending = livePendingQuestion
        livePendingQuestion = null
        liveTurn = null
        _state.update { it.copy(partialQuestion = null) }
        if (_state.value.phase == Phase.THINKING) setBusy(null)
        if (pending == null) return
        emotionJob?.cancel()
        _state.update { it.copy(question = null, answer = null, subtitle = null, emotion = Emotion.NEUTRAL) }
        val prev = history.removeLastOrNull()
        if (prev != null && _state.value.screen == Screen.ANSWER) {
            _state.update {
                it.copy(screen = prev.screen, faqPage = prev.faqPage, question = prev.question, answer = prev.answer, guideTarget = prev.guideTarget)
            }
        }
    }

    private fun markLiveEvent(phrase: Boolean) {
        val t = liveTurn ?: return
        val ms = clock() - t.origin
        if (t.chatFirstEventMs == null) t.chatFirstEventMs = ms
        if (phrase && t.firstPhraseMs == null) t.firstPhraseMs = ms
    }

    /** Ход live закончен (доиграл / перебили / сбой): метрики, если это был ответ на реплику. */
    private fun finishLiveTurn(interrupted: Boolean, fallback: Boolean = false) {
        val t = liveTurn ?: return
        liveTurn = null
        if (t.requestId == null && t.chatFirstEventMs == null && !fallback) return // ответа ещё не было
        t.interrupted = interrupted
        t.fallback = fallback
        sendTurnMetrics(t)
    }

    /** Разговор идёт: сессия не стареет (смена session_id переоткрывает live — см. [touchSession]). */
    private fun touchLiveSession() {
        lastInteraction = clock()
    }

    private fun onLiveAction(signal: LiveSignal.Action) {
        markLiveEvent(phrase = false)
        val action = ChatAction(signal.type, signal.placeId)
        Timber.i("live action %s place=%s turn=%s", action.type, action.placeId, signal.turnId)
        when (action.type) {
            ChatAction.WHERE_IS -> viewModelScope.launch { liveWhereIs(action) }
            ChatAction.GUIDE_TO -> onLiveGuideAction(action, signal.turnId)
            ChatAction.LOOK -> liveLook()
            else -> Timber.w("unknown live action: %s", action.type)
        }
    }

    /** `where_is` в live: направление по карте робота — только на экране (говорит сервер), кнопка «Проводить». */
    private suspend fun liveWhereIs(action: ChatAction) {
        val wf = wayFinder ?: return
        val lang = _state.value.lang
        val place = wf.placeFor(action.placeId, null)
        if (place == null) {
            Timber.w("live where_is: unknown place %s", action.placeId)
            return
        }
        val answer = wf.describe(place, lang)
        if (answer.guideTarget != null) guideOfferedAt = clock()
        _state.update { it.copy(wayHint = answer.text, guideTarget = answer.guideTarget) }
    }

    /**
     * `guide_to` в live (контракт: сервер шлёт его ПОСЛЕ `playback_done` своего хода, с `turn_id`):
     * - ход уже доигран (`turn_id` == последний доигранный) — едем сразу;
     * - это текущий, ещё звучащий ход (старый порядок событий) — ждём его `playback_done`;
     * - без `turn_id` / неизвестный ход: звук не играет — едем сразу, играет — ждём конца текущего хода;
     * - повторный `guide_to` к точке, куда уже едем (или куда посетитель только что согласился ехать «да»), — игнор.
     * Каждое решение — в [NavJournal] (экран диагностики).
     */
    private fun onLiveGuideAction(action: ChatAction, turnId: String?) {
        val label = "live guide_to place=${action.placeId} turn=${turnId ?: "—"}"
        duplicateGuideReason(action.placeId)?.let { reason ->
            NavJournal.log("$label → игнор: $reason")
            return
        }
        val playing = live?.playing == true
        when {
            turnId != null && turnId == liveLastDone -> {
                NavJournal.log("$label → еду сразу: ход уже доигран")
                startLiveGuide(action)
            }
            turnId != null && turnId == liveActiveTurn -> {
                NavJournal.log("$label → жду playback_done этого хода (ещё звучит)")
                waitLiveGuide(turnId, action)
            }
            playing -> {
                NavJournal.log("$label → жду playback_done текущего хода ${liveActiveTurn ?: "—"} (звук играет)")
                waitLiveGuide(liveActiveTurn, action)
            }
            else -> {
                NavJournal.log("$label → еду сразу: ${if (turnId == null) "без turn_id" else "ход не текущий"}, звук не играет")
                startLiveGuide(action)
            }
        }
    }

    /** Ждём `playback_done` хода [turnId]; не пришёл за [LIVE_GUIDE_WAIT_MAX_MILLIS] и звук молчит — едем. */
    private fun waitLiveGuide(turnId: String?, action: ChatAction) {
        liveGuides[turnId] = action
        viewModelScope.launch {
            delay(LIVE_GUIDE_WAIT_MAX_MILLIS)
            if (liveGuides[turnId] !== action) return@launch
            if (live?.playing == true) return@launch // ещё говорит — дождёмся playback_done
            liveGuides.remove(turnId)
            NavJournal.log("live guide_to place=${action.placeId} turn=${turnId ?: "—"}: playback_done не пришёл за ${LIVE_GUIDE_WAIT_MAX_MILLIS / 1000} с → еду")
            startLiveGuide(action)
        }
    }

    /** Почему `guide_to` к [placeId] — дубликат (null — не дубликат). */
    private fun duplicateGuideReason(placeId: String?): String? {
        if (placeId == null) return null
        if (navJob?.isActive == true && guidingPlaceId == placeId) return "уже едем к этой точке"
        val local = localGuideConfirm
        if (local != null && local.first == placeId && clock() - local.second <= LOCAL_CONFIRM_DUPLICATE_MILLIS) {
            localGuideConfirm = null // ответ сервера на то же «да» — один
            return "посетитель уже сказал «да», поездку начали сами"
        }
        return null
    }

    private fun startLiveGuide(action: ChatAction) {
        viewModelScope.launch {
            val wf = wayFinder ?: run {
                NavJournal.log("live guide_to place=${action.placeId}: нет карты (WayFinder) — не едем")
                return@launch
            }
            val place = wf.placeFor(action.placeId, null)
            if (place == null) {
                Timber.w("live guide_to: unknown place %s", action.placeId)
                NavJournal.log("live guide_to place=${action.placeId}: точки нет в кеше мест робота — не едем")
                return@launch
            }
            // id точки сервера мог отличаться от id места (алиас) — проверяем дубликат ещё раз по месту.
            if (place.id != action.placeId) duplicateGuideReason(place.id)?.let { reason ->
                NavJournal.log("live guide_to place=${place.id} → игнор: $reason")
                return@launch
            }
            startGuide(place, announce = false, source = "live guide_to")
        }
    }

    /**
     * `look` в live: кадр → `POST /v1/look` (та же session_id). Сервер продолжает ход сам (контракт); текст описания
     * сразу на экране. Если за [LIVE_LOOK_SPEAK_FALLBACK_MILLIS] после ответа сервер не начал новый ход —
     * описание озвучивает робот сам (обычный TTS; микрофон live на это время глушится). Ошибка камеры или `/look` —
     * готовая фраза робота сразу (серверу продолжать нечем).
     */
    private fun liveLook() {
        if (liveLookJob?.isActive == true) return
        val lang = _state.value.lang
        val question = _state.value.question ?: LOOK_DEFAULT_QUESTION.getValue(lang)
        val session = live
        setEmotion(MoodRules.looking)
        liveLookJob = viewModelScope.launch {
            val result = lookRequest(question)
            val startsBefore = liveTurnStarts
            val text = when (result) {
                null -> {
                    cue(FaceCue.ERROR)
                    phrases.get(PhraseKey.CAMERA_UNAVAILABLE, lang)
                }
                is ApiResult.Success -> result.value.text
                is ApiResult.Failure -> {
                    Timber.w("live look: %s", result.error)
                    showEmotion(MoodRules.trouble)
                    troubleCue(result.error)
                    phrases.get(phraseFor(result.error), lang)
                }
            }
            if (result is ApiResult.Success) {
                _state.update { it.copy(answer = text, subtitle = null) }
                delay(LIVE_LOOK_SPEAK_FALLBACK_MILLIS)
                if (liveTurnStarts != startsBefore || (live === session && session?.playing == true)) return@launch
                Timber.w("live look: server did not voice the description, speaking locally")
            }
            say(text, offline = false)
        }
    }

    /** Live упал: запасной режим без потери разговора (та же session_id), повтор live через [liveRetryMillis]. */
    private fun onLiveFailed(session: LiveSession, signal: LiveSignal.Failed) {
        if (session !== live) return
        Timber.w("live → agent fallback: %s", signal.error.kind)
        val pending = livePendingQuestion
        val greetPending = liveGreetPending
        val wasSpeaking = _state.value.phase == Phase.SPEAKING
        live = null
        liveJob?.cancel()
        liveJob = null
        stopLiveDirections()
        liveDownUntil = clock() + liveRetryMillis
        finishLiveTurn(interrupted = wasSpeaking, fallback = true)
        resetLiveTurn()
        setBusy(null)
        _state.update { it.copy(subtitle = null) }
        updateListeningPaused()
        scheduleLiveRetry()
        when {
            // Посетитель уже сказал, а ответа ещё не было — отвечает agent.
            pending != null -> submitInternal(pending, faqId = null, lang = _state.value.lang)
            greetPending && requestJob?.isActive != true -> greet()
        }
    }

    private fun scheduleLiveRetry(after: Long = liveRetryMillis) {
        liveRetryJob?.cancel()
        liveRetryJob = viewModelScope.launch {
            delay(after)
            while (live == null && chatMode() == ChatMode.LIVE && _state.value.mode != ScreenMode.SLEEP && inForeground()) {
                // Не посреди ответа agent и не во время поездки: дождаться паузы в разговоре.
                if (!busyNow()) {
                    Timber.i("live: retry")
                    ensureLive(greet = false)
                    break
                }
                delay(LIVE_RETRY_BUSY_RECHECK_MILLIS)
            }
        }
    }

    /** Открыт ли живой режим (диагностика, тесты). */
    val liveOpen: Boolean get() = live != null

    // ---------------------------------------------------------------------------------------------
    // Эмоции лица.
    // ---------------------------------------------------------------------------------------------

    private fun setEmotion(emotion: Emotion) {
        emotionJob?.cancel()
        if (_state.value.emotion == emotion) return
        _state.update { it.copy(emotion = emotion) }
        head?.onEmotion(emotion)
    }

    /** Эмоция события на роботе: держится [EMOTION_LINGER_MILLIS], потом спокойное лицо. */
    private fun showEmotion(emotion: Emotion) {
        setEmotion(emotion)
        relaxEmotion()
    }

    private fun relaxEmotion() {
        emotionJob?.cancel()
        emotionJob = viewModelScope.launch {
            delay(EMOTION_LINGER_MILLIS)
            _state.update { it.copy(emotion = Emotion.NEUTRAL) }
        }
    }

    /** Локальный ответ («где X», «проводи», «где я») — в историю на сервере; ошибка только в лог. */
    private fun postLocal(kind: String, question: String, answer: String, placeId: String?, found: Boolean = true) {
        val sid = sessionId
        val message = LocalMessage(kind, question, answer, _state.value.lang, placeId, found)
        viewModelScope.launch {
            try {
                val result = backend.postLocalMessage(sid, message)
                if (result is ApiResult.Failure) Timber.w("history post: %s", result.error.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "history post failed")
            }
        }
    }

    private suspend fun handleAsk(question: String) {
        turn?.mode = TurnMetrics.MODE_ASK
        setBusy(Phase.THINKING)
        val context = askContext()
        var result = backend.ask(question, context)
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.ask(question, context) }
        }
        deliver(result)
    }

    private suspend fun handleLook(question: String) {
        turn?.mode = TurnMetrics.MODE_ASK
        setBusy(Phase.LOOKING)
        val result = lookRequest(question) { setBusy(Phase.THINKING) }
        if (result == null) {
            setBusy(null)
            cue(FaceCue.ERROR)
            say(phrases.get(PhraseKey.CAMERA_UNAVAILABLE, _state.value.lang), offline = false)
            return
        }
        deliver(result)
    }

    /**
     * Снимок → `POST /v1/look` (413 — пересжать и повторить один раз, 429 — подождать и повторить).
     * null — камера не дала кадр. [onCaptured] — кадр снят, идёт запрос.
     */
    private suspend fun lookRequest(question: String, onCaptured: () -> Unit = {}): ApiResult<Answer>? {
        val jpeg = captureFrame() ?: return null
        onCaptured()
        val context = askContext()
        var result = backend.look(jpeg, question, context)
        if (result is ApiResult.Failure && result.error.kind == ErrorKind.PAYLOAD_TOO_LARGE) {
            // Контракт: пересжать кадр и повторить один раз.
            val smaller = captureFrame(quality = RECOMPRESS_QUALITY)
            if (smaller != null) result = backend.look(smaller, question, context)
        }
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.look(jpeg, question, context) }
        }
        return result
    }

    private suspend fun retryAfterRateLimit(
        first: ApiResult<Answer>,
        retry: suspend () -> ApiResult<Answer>,
    ): ApiResult<Answer> {
        val seconds = (first as ApiResult.Failure).error.retryAfterSeconds ?: 1
        // Ждём повтор молча: лицо «думаю» (без «секунду…» голосом).
        setBusy(Phase.THINKING)
        delay(seconds.coerceIn(1, MAX_RETRY_AFTER_SECONDS) * 1000)
        return retry()
    }

    private suspend fun deliver(result: ApiResult<Answer>) {
        setBusy(null)
        when (result) {
            is ApiResult.Success -> {
                val answer = result.value
                turn?.requestId = answer.requestId
                if (!langChosenExplicitly && answer.lang != null && answer.lang != _state.value.lang) {
                    _state.update { it.copy(lang = answer.lang) }
                    voice.setLanguage(answer.lang)
                    loadQuickQuestions()
                }
                _state.update { it.copy(answer = answer.text, subtitle = null, offline = false, citations = answer.citations) }
                if (!quickFromServer) loadQuickQuestions()
                speakPhrases(PhraseSplitter.normalize(answer.phrases), answer.lang ?: _state.value.lang)
            }
            is ApiResult.Failure -> {
                Timber.w("backend error: %s", result.error)
                showEmotion(MoodRules.trouble)
                troubleCue(result.error)
                if (result.error.isConnectivity()) lastNoConnectionPhrase = clock()
                say(phrases.get(phraseFor(result.error), _state.value.lang), offline = result.error.isConnectivity())
            }
        }
    }

    private suspend fun say(text: String, offline: Boolean) {
        _state.update { it.copy(answer = text, subtitle = null, offline = offline) }
        speakPhrases(PhraseSplitter.split(text), _state.value.lang)
    }

    private suspend fun speakPhrases(list: List<String>, lang: Lang) {
        if (list.isEmpty()) return
        voice.speak(list, lang)
        touchSession()
    }

    private fun loadQuickQuestions() {
        val lang = _state.value.lang
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            when (val r = backend.quickQuestions(lang)) {
                is ApiResult.Success -> {
                    quickFromServer = true
                    _state.update { it.copy(quickQuestions = r.value) }
                    quickCache?.save(lang, r.value)
                }
                is ApiResult.Failure -> {
                    Timber.w("quick questions: %s", r.error)
                    quickFromServer = false
                    _state.update { it.copy(quickQuestions = offlineQuickQuestions(lang)) }
                }
            }
        }
    }

    /** Без сети: «Где X?» / «Проводите: X» из кеша мест (работают офлайн) + последние кнопки с сервера. */
    private suspend fun offlineQuickQuestions(lang: Lang): List<QuickQuestion> {
        val local = wayFinder?.let { wf ->
            runCatching { OfflineQuickButtons.build(wf.quickPlaces(), lang) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Timber.e(it, "offline quick buttons") }
                .getOrDefault(emptyList())
        }.orEmpty()
        val cached = quickCache?.load(lang).orEmpty()
        return (local + cached).distinctBy { it.text.trim().lowercase() }.take(MAX_QUICK_BUTTONS)
            .ifEmpty { OFFLINE_QUICK_QUESTIONS[lang].orEmpty() }
    }

    private suspend fun askContext() = AskContext(
        sessionId = sessionId,
        lang = if (langChosenExplicitly) _state.value.lang else null,
        placeId = nearestPlaceId(),
    )

    /** Ближайшая точка карты (контекст «здесь»), если позиция известна; не задерживаем запрос дольше секунды. */
    private suspend fun nearestPlaceId(): String? {
        val wf = wayFinder ?: return null
        return withTimeoutOrNull(PLACE_LOOKUP_TIMEOUT_MILLIS) {
            try {
                wf.nearestPlaceId()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "nearest place")
                null
            }
        }
    }

    /**
     * Точка карты для запроса к агенту без ожидания: свежая, если робот ответил за [PLACE_FAST_MILLIS],
     * иначе последняя известная (уточняется фоном — с первого промежуточного текста / конца речи).
     */
    private suspend fun quickPlaceId(): String? {
        val wf = wayFinder ?: return null
        if (placeJob?.isActive != true) {
            val fresh = withTimeoutOrNull(PLACE_FAST_MILLIS) {
                try {
                    wf.nearestPlaceId()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "nearest place")
                    null
                }
            }
            if (fresh != null) {
                placeHint = fresh
                return fresh
            }
        }
        refreshPlaceHint()
        return placeHint
    }

    private fun refreshPlaceHint() {
        if (wayFinder == null || placeJob?.isActive == true) return
        placeJob = viewModelScope.launch { nearestPlaceId()?.let { placeHint = it } }
    }

    private fun touchSession() {
        val now = clock()
        if (now - lastInteraction > sessionIdleMillis) {
            sessionId = newSessionId()
            langChosenExplicitly = false
            // Новый разговор — новая живая сессия (session_id задаётся при подключении).
            if (live != null) {
                lastInteraction = now
                closeLive()
                ensureLive(greet = false)
            }
        }
        lastInteraction = now
    }

    private fun setBusy(phase: Phase?) {
        busy = phase != null
        if (phase != null) _state.update { it.copy(phase = phase) } else refreshPhase()
    }

    private fun refreshPhase() {
        if (busy) return
        if (tripActive) {
            _state.update { it.copy(phase = Phase.NAVIGATING) }
            return
        }
        val phase = when (voice.activity.value) {
            VoiceActivity.SPEAKING -> Phase.SPEAKING
            VoiceActivity.LISTENING -> Phase.LISTENING
            VoiceActivity.IDLE -> Phase.IDLE
        }
        _state.update { it.copy(phase = phase) }
    }

    companion object {
        const val SESSION_IDLE_MILLIS = 90_000L

        /** Поездка без итога столько — сбой (страховка поверх таймаута шлюза [RobotGateway.TRIP_TIMEOUT_MS]). */
        const val MAX_TRIP_MILLIS = RobotGateway.TRIP_TIMEOUT_MS + 15_000L
        private const val TRIP_TIMEOUT_CODE = RobotGateway.CODE_TRIP_TIMEOUT

        /** Live: «да» на предложение проводить засчитываем, если предложение показано не раньше чем столько назад. */
        const val GUIDE_OFFER_WINDOW_MILLIS = 60_000L

        /** Live: после локального «да» ответный `guide_to` сервера к той же точке столько считается дубликатом. */
        const val LOCAL_CONFIRM_DUPLICATE_MILLIS = 30_000L

        /** Live: `guide_to` ждёт `playback_done` своего хода не дольше (потерялся — едем, если звук не играет). */
        const val LIVE_GUIDE_WAIT_MAX_MILLIS = 30_000L

        /** Итог поездки → реакция лица. */
        internal fun tripCue(r: NavigationResult): FaceCue = when (r) {
            NavigationResult.Arrived, NavigationResult.AlreadyThere -> FaceCue.ARRIVED
            NavigationResult.Cancelled -> FaceCue.TRIP_CANCELLED
            NavigationResult.UnknownPlace -> FaceCue.CONFUSED
            NavigationResult.NotLocalized, is NavigationResult.Failed -> FaceCue.TRIP_FAILED
        }

        internal fun describe(r: NavigationResult): String = when (r) {
            NavigationResult.Arrived -> "приехали (Arrived)"
            NavigationResult.AlreadyThere -> "уже на месте (AlreadyThere)"
            NavigationResult.Cancelled -> "остановлено (Cancelled)"
            NavigationResult.NotLocalized -> "робот не локализован (NotLocalized)"
            NavigationResult.UnknownPlace -> "точки нет на карте (UnknownPlace)"
            is NavigationResult.Failed -> "ошибка код=${r.code} ${r.message.orEmpty()}"
        }

        /** Live упал → через столько снова пробуем live. */
        const val LIVE_RETRY_MILLIS = 30_000L
        private const val LIVE_RETRY_BUSY_RECHECK_MILLIS = 2_000L

        /** `look` в live: ждём, что сервер озвучит описание, столько; потом говорим сами. */
        const val LIVE_LOOK_SPEAK_FALLBACK_MILLIS = 4_000L

        /** Меню без касаний столько — назад к лицу (agent). */
        const val MENU_IDLE_MILLIS = 30_000L

        /** Эмоция держится после ответа / события, потом лицо спокойное. */
        const val EMOTION_LINGER_MILLIS = 6_000L

        private const val MAX_HISTORY = 20
        const val NO_CONNECTION_REPEAT_MILLIS = 60_000L
        private const val PLACE_LOOKUP_TIMEOUT_MILLIS = 1_000L
        private const val PLACE_FAST_MILLIS = 150L

        /** «Думаю» по концу речи держим не дольше, если финальный текст так и не пришёл. */
        const val EARLY_THINKING_MAX_MILLIS = 8_000L

        /** Вопрос робота («Проводить вас?») ждёт ответа «да / нет» столько после последней фразы ответа. */
        const val ROBOT_QUESTION_WINDOW_MILLIS = 30_000L

        /** Конец речи по VAD относится к финальному тексту, если тот пришёл не позже. */
        private const val UTTERANCE_END_FRESH_MILLIS = 15_000L
        private const val MAX_QUICK_BUTTONS = 8

        /** В этих фазах посторонние реплики фильтруются ([UtteranceFilter]). */
        private val BUSY_PHASES = setOf(Phase.THINKING, Phase.LOOKING, Phase.SPEAKING, Phase.NAVIGATING)
        private const val MAX_RETRY_AFTER_SECONDS = 5L
        private const val RECOMPRESS_QUALITY = 50

        private fun newSessionId() = UUID.randomUUID().toString()

        private val LOOK_DEFAULT_QUESTION = mapOf(
            Lang.RU to "Что это?",
            Lang.KK to "Бұл не?",
            Lang.EN to "What is this?",
        )

        /** Если сервер недоступен — кнопки всё равно есть (офлайн-фолбэк). */
        val OFFLINE_QUICK_QUESTIONS: Map<Lang, List<QuickQuestion>> = mapOf(
            Lang.RU to listOf("Что такое Astana Hub?", "Как попасть на мероприятие?", "Что это?"),
            Lang.KK to listOf("Astana Hub деген не?", "Іс-шараға қалай қатысуға болады?", "Бұл не?"),
            Lang.EN to listOf("What is Astana Hub?", "How do I attend an event?", "What is this?"),
        ).mapValues { (_, l) -> l.mapIndexed { i, t -> QuickQuestion("offline_$i", t) } }

        /** Таблица «Что делает робот» из раздела «Ошибки» контракта. 429 сюда попадает уже после повтора. */
        internal fun phraseFor(error: BackendError): PhraseKey = when (error.kind) {
            ErrorKind.BAD_REQUEST -> PhraseKey.NOT_HEARD
            else -> PhraseKey.NO_CONNECTION
        }

        private fun BackendError.isConnectivity() = kind != ErrorKind.BAD_REQUEST

        private fun ApiResult<*>.isRateLimited() =
            this is ApiResult.Failure && error.kind == ErrorKind.RATE_LIMITED
    }
}
