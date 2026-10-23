package kz.astanahub.robot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.Answer
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.AskContext
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ChatAction
import kz.astanahub.robot.backend.ChatActionType
import kz.astanahub.robot.backend.LocalMessage
import kz.astanahub.robot.backend.map
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.backend.QuickQuestionCache
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.IntentRouter
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.PhraseSplitter
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.core.UtteranceFilter
import kz.astanahub.robot.places.OfflineQuickButtons
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.places.WayPhrases
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.voice.Transcript
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.core.LogSafe
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
    /** Заставка после бездействия. */
    val screensaver: Boolean = false,
    val screen: Screen = Screen.HOME,
    /** Страница частых вопросов (по [FAQ_PAGE_SIZE] на странице). */
    val faqPage: Int = 0,
    /** Раскрытый вопрос в списке частых вопросов (id), null — все свёрнуты. */
    val faqOpenId: String? = null,
) {
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
    /** «Где X» / «Проводи» — локально по карте; null — всё уходит агенту (/v1/chat). */
    private val wayFinder: WayFinder? = null,
    private val robot: RobotGateway? = null,
    /** Через сколько мс бездействия показать заставку и сбросить сессию; null — выключено (тесты). */
    private val screensaverMillis: Long? = null,
    /** Кеш кнопок частых вопросов на диске (показываем без сети). */
    private val quickCache: QuickQuestionCache? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var requestJob: Job? = null
    private var quickJob: Job? = null
    private var navJob: Job? = null
    private var idleJob: Job? = null
    private var busy = false

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
        }
    private var lastInteraction = clock()

    /** Когда последний раз говорили «нет связи» — не чаще раза в [NO_CONNECTION_REPEAT_MILLIS]. */
    private var lastNoConnectionPhrase = Long.MIN_VALUE / 2

    /** Кнопки частых вопросов пришли с сервера (а не офлайн-набор). */
    private var quickFromServer = false

    init {
        voice.setSessionId(sessionId)
        viewModelScope.launch {
            voice.transcripts.collect(::onTranscript)
        }
        viewModelScope.launch {
            voice.activity.collect { refreshPhase(); restartIdleTimer() }
        }
        viewModelScope.launch {
            voice.connection.collect { up -> if (up) onConnectionRestored() else onConnectionLost() }
        }
        voice.setLanguage(_state.value.lang) // подсказка языка серверному STT — с первой фразы
        loadQuickQuestions()
        wayFinder?.let { wf -> viewModelScope.launch { wf.refreshPlaces() } }
        restartIdleTimer()
    }

    /** Робот занят: думает, смотрит, говорит или ведёт — посторонние реплики фильтруем. */
    private fun busyForVoice(): Boolean =
        _state.value.phase in BUSY_PHASES || navJob?.isActive == true

    /**
     * Фраза посетителя. Пока робот занят, принимаем только «стоп» или новую содержательную фразу
     * (≥ 3 слов, не «угу/ага/да»); остальное — шум/реплики в сторону, игнорируем (в лог — без текста).
     */
    private fun onTranscript(t: Transcript) {
        val busyNow = busyForVoice()
        if (!t.isFinal) {
            if (busyNow) return
            wake()
            _state.update { it.copy(partialQuestion = t.text.ifBlank { null }) }
            return
        }
        val text = t.text.trim()
        if (text.isEmpty()) {
            if (_state.value.partialQuestion != null) _state.update { it.copy(partialQuestion = null) }
            return
        }
        if (UtteranceFilter.isStopCommand(text)) {
            Timber.i("voice command: stop (phase=%s)", _state.value.phase)
            _state.update { it.copy(partialQuestion = null) }
            stop()
            return
        }
        if (busyNow && !UtteranceFilter.isSubstantial(text)) {
            Timber.i("utterance ignored in %s (len=%d)", _state.value.phase, text.length)
            return
        }
        submit(text)
    }

    /** /v1/stt не прошёл из-за связи: бейдж + «нет связи» голосом (не чаще раза в минуту и не перебивая). */
    private fun onConnectionLost() {
        _state.update { it.copy(offline = true, partialQuestion = null) }
        val now = clock()
        if (now - lastNoConnectionPhrase < NO_CONNECTION_REPEAT_MILLIS) return
        if (busyForVoice() || requestJob?.isActive == true) return
        lastNoConnectionPhrase = now
        val lang = _state.value.lang
        requestJob = viewModelScope.launch { say(phrases.get(PhraseKey.NO_CONNECTION, lang), offline = true) }
    }

    private fun onConnectionRestored() {
        Timber.i("connection restored")
        _state.update { it.copy(offline = false) }
        if (!quickFromServer) loadQuickQuestions()
    }

    /** Сменили адрес сервера (экран диагностики) — перечитать кнопки и места. */
    fun refreshFromServer() {
        loadQuickQuestions()
        wayFinder?.let { wf -> viewModelScope.launch { wf.refreshPlaces() } }
    }

    /** Касание экрана / голос: убрать заставку и перезапустить таймер бездействия. */
    fun wake() {
        if (_state.value.screensaver) _state.update { it.copy(screensaver = false) }
        restartIdleTimer()
    }

    private fun restartIdleTimer() {
        val timeout = screensaverMillis ?: return
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(timeout)
            val s = _state.value
            if (s.phase == Phase.IDLE && navJob?.isActive != true && requestJob?.isActive != true) {
                goIdle()
            } else {
                restartIdleTimer()
            }
        }
    }

    /** Заставка: сброс сессии и экрана к начальному виду (шаг 7.4). */
    private fun goIdle() {
        Timber.i("idle → screensaver, session reset")
        sessionId = newSessionId()
        langChosenExplicitly = false
        val langChanged = _state.value.lang != Lang.RU
        _state.update {
            it.copy(
                lang = Lang.RU, question = null, partialQuestion = null, answer = null,
                guideTarget = null, offline = false, screensaver = true,
                screen = Screen.HOME, faqPage = 0, faqOpenId = null,
            )
        }
        history.clear()
        if (langChanged) {
            voice.setLanguage(Lang.RU)
            loadQuickQuestions()
        }
        wayFinder?.let { wf -> viewModelScope.launch { wf.refreshPlaces() } }
    }

    fun selectLanguage(lang: Lang) {
        langChosenExplicitly = true
        if (lang == _state.value.lang) return
        _state.update { it.copy(lang = lang) }
        voice.setLanguage(lang)
        loadQuickQuestions()
    }

    /** Вопрос текстом (поле ввода на эмуляторе, плитка вопроса) или финальный ASR: ответ — на экране ответа. */
    fun submit(text: String) = submitInternal(text, faqId = null)

    private fun submitInternal(text: String, faqId: String?) {
        val question = text.trim()
        if (question.isEmpty()) return
        // Повторное нажатие той же кнопки, пока ответ ещё готовится, — игнорируем (на роботе жали 6 раз подряд).
        val current = _state.value
        if (current.processing && requestJob?.isActive == true && current.question == question) {
            Timber.d("duplicate question ignored: %s", LogSafe.redact(question))
            return
        }
        touchSession()
        wake()
        voice.stopSpeaking()
        requestJob?.cancel()
        if (faqId == null) {
            // Вопрос из любого места → экран ответа; прежний экран запоминаем для «Назад».
            remember()
            _state.update { it.copy(screen = Screen.ANSWER, faqOpenId = null) }
        } else {
            _state.update { it.copy(screen = Screen.FAQ, faqOpenId = faqId) }
        }
        requestJob = viewModelScope.launch {
            _state.update { it.copy(question = question, partialQuestion = null, answer = null, guideTarget = null) }
            val routed = IntentRouter.route(question)
            Timber.d("intent %s subject=%s", routed.intent, LogSafe.redact(routed.subject))
            when (routed.intent) {
                IntentRouter.Intent.LOOK -> handleLook(question)
                IntentRouter.Intent.WHERE_IS -> handleWhereIs(question, routed.subject)
                IntentRouter.Intent.GUIDE_TO -> handleGuide(question, routed.subject)
                IntentRouter.Intent.WHERE_AM_I -> handleWhereAmI(question)
                IntentRouter.Intent.ASK -> handleChat(question)
            }
        }
    }

    /** «Где X?» — локально; место не узнали → вопрос агенту. */
    private suspend fun handleWhereIs(question: String, subject: String) {
        val wf = wayFinder ?: return handleChat(question)
        setBusy(Phase.THINKING)
        val answer = wf.whereIs(subject.ifBlank { question }, _state.value.lang)
            ?: return handleChat(question)
        setBusy(null)
        _state.update { it.copy(answer = answer.text, guideTarget = answer.guideTarget) }
        recordLocal("where_is", question, answer.text, answer.place?.id)
        speakPhrases(PhraseSplitter.split(answer.text), _state.value.lang)
    }

    private suspend fun handleWhereAmI(question: String) {
        val wf = wayFinder ?: return handleChat(question)
        setBusy(Phase.THINKING)
        val answer = wf.whereAmI(_state.value.lang)
        setBusy(null)
        recordLocal("where_am_i", question, answer.text, answer.place?.id)
        say(answer.text, offline = _state.value.offline)
    }

    /**
     * «Проводи до X» — место узнали по карте → сразу ведём. Не узнали («проводи туда») → агент: он помнит
     * разговор. Агент недоступен → честно говорим, что место не знаем.
     */
    private suspend fun handleGuide(question: String, subject: String) {
        val wf = wayFinder ?: return handleChat(question)
        setBusy(Phase.THINKING)
        val place = wf.find(subject.ifBlank { question })
        setBusy(null)
        if (place == null) {
            handleChat(question) { say(WayPhrases.unknownPlace(_state.value.lang), offline = _state.value.offline) }
            return
        }
        val lang = _state.value.lang
        recordLocal("guide_to", question, WayPhrases.guideStart(WayFinder.displayName(place, lang), lang), place.id)
        startGuide(place)
    }

    /** Кнопка «Проводить» под ответом. */
    fun guideToOffered() {
        val place = _state.value.guideTarget ?: return
        wake()
        startGuide(place)
    }

    /**
     * Ведём к [place]. Если уже едем — сначала гасим прежнюю поездку и ждём от робота подтверждения остановки
     * (или [RobotGateway.STOP_CONFIRM_TIMEOUT_MS]), только потом startNavigation.
     * Все фразы поездки (старт, препятствие, прибытие) — внутри navJob и отменяются вместе с ним.
     */
    private fun startGuide(place: Place, announce: Boolean = true) {
        val nav = robot ?: return
        val lang = _state.value.lang
        val name = WayFinder.displayName(place, lang)
        voice.stopSpeaking()
        val previous = navJob
        navJob = viewModelScope.launch {
            if (previous?.isActive == true) {
                previous.cancelAndJoin()
                val confirmed = nav.stopNavigationAndWait(RobotGateway.STOP_CONFIRM_TIMEOUT_MS)
                Timber.i("previous navigation stopped (confirmed=%s)", confirmed)
            }
            _state.update {
                it.copy(guideTarget = null, guidingTo = name, answer = if (announce) WayPhrases.guideStart(name, lang) else it.answer)
            }
            setBusy(Phase.NAVIGATING)
            val trip = this
            // Фраза старта звучит параллельно с началом движения; агент («Провожаю вас…») её уже сказал.
            val startSpeech = launch { if (announce) voice.speak(listOf(WayPhrases.guideStart(name, lang)), lang) }
            var obstacleSpeech: Job? = null
            val result = try {
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
            } finally {
                _state.update { it.copy(guidingTo = null) }
                setBusy(null)
            }
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

    /** Кнопка «Что это?». */
    fun lookAround() = submit(LOOK_DEFAULT_QUESTION.getValue(_state.value.lang))

    fun stop() {
        wake()
        requestJob?.cancel()
        voice.stopSpeaking()
        if (navJob?.isActive == true) {
            navJob?.cancel()
            robot?.stopNavigation()
            _state.update { it.copy(guidingTo = null) }
            viewModelScope.launch { say(WayPhrases.guideCancelled(_state.value.lang), offline = _state.value.offline) }
        }
        setBusy(null)
    }

    /**
     * Вопрос агенту (/v1/chat): ответ звучит, затем робот выполняет действия из ответа.
     * [onFailure] — своя реакция на ошибку вместо фразы «нет связи».
     */
    private suspend fun handleChat(question: String, onFailure: (suspend () -> Unit)? = null) {
        setBusy(Phase.THINKING)
        val context = askContext()
        var result = backend.chat(question, context)
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.chat(question, context) }
        }
        if (result is ApiResult.Failure && onFailure != null) {
            Timber.w("chat error: %s", result.error)
            setBusy(null)
            onFailure()
            return
        }
        deliver(result.map { it.answer })
        val actions = (result as? ApiResult.Success)?.value?.actions.orEmpty()
        for (action in actions) perform(action, question)
    }

    /** Действие из ответа агента. Место, которого нет у робота, пропускаем (агент о нём уже сказал). */
    private suspend fun perform(action: ChatAction, question: String) {
        Timber.i("chat action %s place=%s", action.type, action.placeId)
        when (action.type) {
            ChatActionType.LOOK -> handleLook(question)
            ChatActionType.WHERE_IS -> {
                val place = placeFor(action) ?: return
                if (wayFinder?.canGuide(place) == true) _state.update { it.copy(guideTarget = place) }
            }
            ChatActionType.GUIDE_TO -> {
                val place = placeFor(action)
                if (place == null) {
                    say(WayPhrases.unknownPlace(_state.value.lang), offline = _state.value.offline)
                    return
                }
                startGuide(place, announce = false)
            }
        }
    }

    private suspend fun placeFor(action: ChatAction): Place? {
        val id = action.placeId ?: return null
        return wayFinder?.placeById(id).also { if (it == null) Timber.w("chat action: unknown place %s", id) }
    }

    /** Локальный ответ — в историю и память агента; фоном, ответ посетителю не ждёт, ошибка — только в лог. */
    private fun recordLocal(kind: String, question: String, answer: String, placeId: String?) {
        val message = LocalMessage(sessionId, kind, question, answer, _state.value.lang, placeId)
        viewModelScope.launch {
            try {
                val r = backend.recordLocal(message)
                if (r is ApiResult.Failure) Timber.w("local message not recorded: %s", r.error.kind)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "local message")
            }
        }
    }

    private suspend fun handleLook(question: String) {
        setBusy(Phase.LOOKING)
        val jpeg = camera.captureJpeg()
        if (jpeg == null) {
            setBusy(null)
            say(phrases.get(PhraseKey.CAMERA_UNAVAILABLE, _state.value.lang), offline = false)
            return
        }
        setBusy(Phase.THINKING)
        val context = askContext()
        var result = backend.look(jpeg, question, context)
        if (result is ApiResult.Failure && result.error.kind == ErrorKind.PAYLOAD_TOO_LARGE) {
            // Контракт: пересжать кадр и повторить один раз.
            val smaller = camera.captureJpeg(quality = RECOMPRESS_QUALITY)
            if (smaller != null) result = backend.look(smaller, question, context)
        }
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.look(jpeg, question, context) }
        }
        deliver(result)
    }

    private suspend fun <T> retryAfterRateLimit(
        first: ApiResult<T>,
        retry: suspend () -> ApiResult<T>,
    ): ApiResult<T> {
        val seconds = (first as ApiResult.Failure).error.retryAfterSeconds ?: 1
        voice.speak(listOf(phrases.get(PhraseKey.ONE_MOMENT, _state.value.lang)), _state.value.lang)
        delay(seconds.coerceIn(1, MAX_RETRY_AFTER_SECONDS) * 1000)
        return retry()
    }

    private suspend fun deliver(result: ApiResult<Answer>) {
        setBusy(null)
        when (result) {
            is ApiResult.Success -> {
                val answer = result.value
                if (!langChosenExplicitly && answer.lang != null && answer.lang != _state.value.lang) {
                    _state.update { it.copy(lang = answer.lang) }
                    voice.setLanguage(answer.lang)
                    loadQuickQuestions()
                }
                _state.update { it.copy(answer = answer.text, offline = false) }
                if (!quickFromServer) loadQuickQuestions()
                speakPhrases(PhraseSplitter.normalize(answer.phrases), answer.lang ?: _state.value.lang)
            }
            is ApiResult.Failure -> {
                Timber.w("backend error: %s", result.error)
                if (result.error.isConnectivity()) lastNoConnectionPhrase = clock()
                say(phrases.get(phraseFor(result.error), _state.value.lang), offline = result.error.isConnectivity())
            }
        }
    }

    private suspend fun say(text: String, offline: Boolean) {
        _state.update { it.copy(answer = text, offline = offline) }
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

    private fun touchSession() {
        val now = clock()
        if (now - lastInteraction > sessionIdleMillis) {
            sessionId = newSessionId()
            langChosenExplicitly = false
        }
        lastInteraction = now
    }

    private fun setBusy(phase: Phase?) {
        busy = phase != null
        if (phase != null) _state.update { it.copy(phase = phase) } else refreshPhase()
    }

    private fun refreshPhase() {
        if (busy) return
        if (navJob?.isActive == true) {
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
        private const val MAX_HISTORY = 20
        const val NO_CONNECTION_REPEAT_MILLIS = 60_000L
        private const val PLACE_LOOKUP_TIMEOUT_MILLIS = 1_000L
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
