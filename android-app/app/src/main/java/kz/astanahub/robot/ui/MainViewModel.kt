package kz.astanahub.robot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.core.IntentRouter
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.PhraseSplitter
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.places.WayPhrases
import kz.astanahub.robot.robot.NavEvent
import kz.astanahub.robot.robot.NavigationResult
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.voice.VoiceActivity
import kz.astanahub.robot.voice.VoiceGateway
import kz.astanahub.robot.core.LogSafe
import timber.log.Timber

/** Индикатор на экране. */
enum class Phase { IDLE, LISTENING, THINKING, LOOKING, SPEAKING, NAVIGATING }

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
) {
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
    /** «Где X» / «Проводи» — локально по карте; null — всё уходит в /ask. */
    private val wayFinder: WayFinder? = null,
    private val robot: RobotGateway? = null,
    /** Через сколько мс бездействия показать заставку и сбросить сессию; null — выключено (тесты). */
    private val screensaverMillis: Long? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var requestJob: Job? = null
    private var quickJob: Job? = null
    private var navJob: Job? = null
    private var idleJob: Job? = null
    private var busy = false

    /** Язык выбран кнопкой — тогда передаём его серверу; иначе сервер определяет сам. */
    private var langChosenExplicitly = false

    private var sessionId = newSessionId()
    private var lastInteraction = clock()

    init {
        viewModelScope.launch {
            voice.transcripts.collect { t ->
                if (t.isFinal) {
                    if (t.text.isNotBlank()) submit(t.text)
                } else {
                    wake()
                    _state.update { it.copy(partialQuestion = t.text.ifBlank { null }) }
                }
            }
        }
        viewModelScope.launch {
            voice.activity.collect { refreshPhase(); restartIdleTimer() }
        }
        loadQuickQuestions()
        wayFinder?.let { wf -> viewModelScope.launch { wf.refreshPlaces() } }
        restartIdleTimer()
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
            )
        }
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

    /** Вопрос текстом (поле ввода на эмуляторе, кнопка частого вопроса) или финальный ASR. */
    fun submit(text: String) {
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
        requestJob = viewModelScope.launch {
            _state.update { it.copy(question = question, partialQuestion = null, answer = null, guideTarget = null) }
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
    }

    /** «Где X?» — локально; место не узнали → вопрос к серверу. */
    private suspend fun handleWhereIs(question: String, subject: String) {
        val wf = wayFinder ?: return handleAsk(question)
        setBusy(Phase.THINKING)
        val answer = wf.whereIs(subject.ifBlank { question }, _state.value.lang)
            ?: return handleAsk(question)
        setBusy(null)
        _state.update { it.copy(answer = answer.text, guideTarget = answer.guideTarget) }
        speakPhrases(PhraseSplitter.split(answer.text), _state.value.lang)
    }

    private suspend fun handleWhereAmI(question: String) {
        val wf = wayFinder ?: return handleAsk(question)
        setBusy(Phase.THINKING)
        val answer = wf.whereAmI(_state.value.lang)
        setBusy(null)
        say(answer.text, offline = _state.value.offline)
    }

    /** «Проводи до X» — сразу ведём; место не узнали → честно говорим (без сервера это не решить). */
    private suspend fun handleGuide(question: String, subject: String) {
        val wf = wayFinder ?: return handleAsk(question)
        setBusy(Phase.THINKING)
        val place = wf.find(subject.ifBlank { question })
        setBusy(null)
        if (place == null) {
            say(WayPhrases.unknownPlace(_state.value.lang), offline = _state.value.offline)
            return
        }
        startGuide(place)
    }

    /** Кнопка «Проводить» под ответом. */
    fun guideToOffered() {
        val place = _state.value.guideTarget ?: return
        wake()
        startGuide(place)
    }

    private fun startGuide(place: Place) {
        val nav = robot ?: return
        val lang = _state.value.lang
        val name = WayFinder.displayName(place, lang)
        voice.stopSpeaking()
        navJob?.cancel()
        navJob = viewModelScope.launch {
            _state.update { it.copy(guideTarget = null, guidingTo = name, answer = WayPhrases.guideStart(name, lang)) }
            setBusy(Phase.NAVIGATING)
            // Фраза старта звучит параллельно с началом движения.
            val startSpeech = launch { voice.speak(listOf(WayPhrases.guideStart(name, lang)), lang) }
            val result = try {
                nav.navigateTo(place.mapName) { event ->
                    if (event == NavEvent.OBSTACLE) {
                        viewModelScope.launch { voice.speak(listOf(WayPhrases.guideObstacle(lang)), lang) }
                    }
                }
            } finally {
                _state.update { it.copy(guidingTo = null) }
                setBusy(null)
            }
            startSpeech.join()
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

    private suspend fun handleAsk(question: String) {
        setBusy(Phase.THINKING)
        var result = backend.ask(question, askContext())
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.ask(question, askContext()) }
        }
        deliver(result)
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
        var result = backend.look(jpeg, question, askContext())
        if (result is ApiResult.Failure && result.error.kind == ErrorKind.PAYLOAD_TOO_LARGE) {
            // Контракт: пересжать кадр и повторить один раз.
            val smaller = camera.captureJpeg(quality = RECOMPRESS_QUALITY)
            if (smaller != null) result = backend.look(smaller, question, askContext())
        }
        if (result.isRateLimited()) {
            result = retryAfterRateLimit(result) { backend.look(jpeg, question, askContext()) }
        }
        deliver(result)
    }

    private suspend fun retryAfterRateLimit(
        first: ApiResult<Answer>,
        retry: suspend () -> ApiResult<Answer>,
    ): ApiResult<Answer> {
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
                speakPhrases(PhraseSplitter.normalize(answer.phrases), answer.lang ?: _state.value.lang)
            }
            is ApiResult.Failure -> {
                Timber.w("backend error: %s", result.error)
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
                is ApiResult.Success -> _state.update { it.copy(quickQuestions = r.value) }
                is ApiResult.Failure -> {
                    Timber.w("quick questions: %s", r.error)
                    _state.update { it.copy(quickQuestions = OFFLINE_QUICK_QUESTIONS[lang].orEmpty()) }
                }
            }
        }
    }

    private fun askContext() = AskContext(
        sessionId = sessionId,
        lang = if (langChosenExplicitly) _state.value.lang else null,
        placeId = null, // TODO шаг 5.5: ближайшая точка карты
    )

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
