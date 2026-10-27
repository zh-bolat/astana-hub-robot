package kz.astanahub.robot

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.live.LiveDirectionsReporter
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.security.DiagAccess
import kz.astanahub.robot.ui.screen.PinPadActions
import kz.astanahub.robot.ui.screen.PinPadState
import kz.astanahub.robot.ui.screen.PinPurpose
import kz.astanahub.robot.ui.screen.PinTexts
import kz.astanahub.robot.security.ExitMode
import kz.astanahub.robot.security.PinBuffer
import kz.astanahub.robot.security.DiagPinGuard
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.MainViewModel
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.Screen
import kz.astanahub.robot.ui.ScreenMode
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.ui.face.FaceController
import kz.astanahub.robot.ui.face.FaceCue
import kz.astanahub.robot.ui.face.IslandContent
import kz.astanahub.robot.ui.face.IslandGeometry
import kz.astanahub.robot.ui.face.IslandModel
import kz.astanahub.robot.ui.face.IslandTexts
import kz.astanahub.robot.ui.face.TripOutcome
import kz.astanahub.robot.ui.face.FaceDebug
import kz.astanahub.robot.ui.face.FaceInputMapper
import kz.astanahub.robot.ui.screen.ChatLog
import kz.astanahub.robot.ui.screen.RobotScreens
import kz.astanahub.robot.ui.screen.RobotScreensHost
import kz.astanahub.robot.ui.screen.ScreenTexts
import kz.astanahub.robot.ui.screen.ScreenTwoActions
import kz.astanahub.robot.ui.screen.ScreenTwoMapper
import kz.astanahub.robot.ui.screen.ScreenTwoState
import kz.astanahub.robot.ui.screen.Tile
import kz.astanahub.robot.ui.screen.TileAction
import kz.astanahub.robot.ui.screen.Tiles

/**
 * Главный экран-киоск (docs/ui-redesign/DESIGN.md): экран 1 — живое лицо, экран 2 — чат, плитки, язык и управление
 * (Compose, [RobotScreens]). Вся логика разговора, навигации и режимов — в [MainViewModel]; здесь только перевод
 * его состояния в состояние экранов, действия кнопок и жестов, разрешение на микрофон, подтверждение выхода.
 */
class MainActivity : AppCompatActivity() {

    private val container by lazy { (application as AstanaHubApp).container }

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    backend = container.backend,
                    voice = container.voice,
                    camera = container.camera,
                    phrases = container.text,
                    wayFinder = container.wayFinder,
                    robot = container.robot,
                    screensaverMillis = BuildConfig.SCREENSAVER_SECONDS.takeIf { it > 0 }?.let { it * 1000L },
                    quickCache = container.quickQuestionCache,
                    chatMode = { container.chatSettings.mode.value },
                    presence = container.presence,
                    head = container.head,
                    vadSilenceMillis = { container.voiceSettings.config.value.vadEndSilenceMillis },
                    liveFactory = container.liveFactory,
                    foreground = container.appForeground,
                    liveDirectionsPeriodMillis = LiveDirectionsReporter.PERIOD_MILLIS,
                    visitorVoice = container.audioLevels.voice,
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Экран 1: живое лицо; экран 2: функции; чат собирается из ходов MainViewModel. */
    private lateinit var face: FaceController
    private val two = ScreenTwoState()
    private val chatLog = ChatLog()
    private var faceDebug: FaceDebug? = null

    /** Что уже собрано (подписи и плитки пересобираем только при смене языка / списка вопросов). */
    private var textsFor: Lang? = null
    private var islandTextsFor: Lang? = null

    /** Остров экрана лица и его таймер (where_is ~9 с, итог поездки ~2 с). */
    private val island = IslandModel()
    private var firstRender = true
    private val islandTick = Runnable {
        if (island.tick(SystemClock.uptimeMillis())) applyIsland() else scheduleIsland()
    }
    private var tilesFor: Any? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        timber.log.Timber.i("RECORD_AUDIO granted=%s", granted)
        renderMicBadge()
    }

    private var appliedScale = 0

    override fun attachBaseContext(newBase: android.content.Context) {
        appliedScale = UiScale.percent(newBase)
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    private var backendUrlSeen: String? = null

    override fun onResume() {
        super.onResume()
        if (techPauseRedirected) return
        renderMicBadge() // разрешение могли выдать в настройках
        val url = container.backend.url.value
        if (backendUrlSeen != null && backendUrlSeen != url) viewModel.refreshFromServer()
        backendUrlSeen = url
        viewModel.onSettingsChanged() // режим разговора могли сменить в диагностике
        // Масштаб меняют на экране диагностики — применяем без перезапуска приложения.
        if (UiScale.percent(this) != appliedScale) recreate()
    }

    private var techPauseRedirected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as AstanaHubApp).techPause) {
            // Серия падений: контейнер не создан, показываем «техническую паузу».
            techPauseRedirected = true
            startActivity(Intent(this, TechPauseActivity::class.java))
            finish()
            return
        }
        enterKioskMode()
        setUpScreens()

        // Системная «Назад»: закрыть подтверждение выхода; на экране 2 — из частых вопросов назад, иначе к лицу.
        // На лице — ничего (киоск).
        onBackPressedDispatcher.addCallback(this) {
            when {
                two.exitConfirm -> stayIn()
                face.screen.menuOpen && viewModel.state.value.screen == Screen.FAQ -> viewModel.back()
                face.screen.menuOpen -> viewModel.showFace()
            }
        }
        requestMicPermissionOnce()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.faceCues.collect { cue ->
                    face.onCue(cue)
                    // Итог поездки — коротко на острове.
                    val outcome = when (cue) {
                        FaceCue.ARRIVED -> TripOutcome.ARRIVED
                        FaceCue.TRIP_FAILED -> TripOutcome.FAILED
                        FaceCue.TRIP_CANCELLED -> TripOutcome.STOPPED
                        else -> null
                    }
                    if (outcome != null && island.onOutcome(outcome, SystemClock.uptimeMillis())) applyIsland()
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        faceDebug?.unregister(this)
        super.onDestroy()
    }

    /**
     * Экраны 1 и 2 — одно Compose-дерево (переход ведёт палец, лицо сжимается в мини-лицо в шапке экрана 2).
     * Отладка (debug + emulator): adb broadcast [FaceDebug.ACTION].
     */
    private fun setUpScreens() {
        val reduced = !ValueAnimator.areAnimatorsEnabled()
        face = FaceController(container.audioLevels, reducedMotion = reduced)
        // «?» на лице — глиф Nunito ExtraBold, как в прототипе.
        face.renderer.typeface = runCatching { ResourcesCompat.getFont(this, R.font.nunito_extrabold) }.getOrNull()
        two.reducedMotion = reduced
        // Костюм робота-компаньона (только вид) — выбор в диагностике, применяется сразу.
        lifecycleScope.launch { container.costume.costume.collect { two.costume = it } }
        two.textInput = !BuildConfig.IS_ROBOT
        two.diagnosticsHold = container.diagnostics.access != DiagAccess.DISABLED
        two.buildLabel = getString(
            R.string.build_label, getString(R.string.flavor_name), BuildConfig.BUILD_TYPE, BuildConfig.VERSION_NAME,
        )
        // Шрифт грузится заранее и не на главном потоке: первый показ текста не теряет кадр на чтении .ttf.
        lifecycleScope.launch(Dispatchers.IO) {
            for (font in FONTS) runCatching { ResourcesCompat.getFont(this@MainActivity, font) }
        }
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RobotScreens(
                    face = face,
                    two = two,
                    actions = actions,
                    host = host,
                    pin = pin,
                    pinActions = pinActions,
                )
            }
        }
        setContentView(view)
        // Экраны сами закрашивают всё чёрным: фон окна не рисуем (одна заливка вместо двух).
        window.setBackgroundDrawable(null)
        if (BuildConfig.DEBUG && !BuildConfig.IS_ROBOT) {
            faceDebug = FaceDebug(face, viewModel, container.audioLevels, container.presenceSimulator).also { it.register(this) }
        }
    }

    private val host = object : RobotScreensHost {
        override fun onFaceTap() = this@MainActivity.onFaceTap()

        override fun onFaceHold() = openDiagnostics()

        override fun onTravelStop() = viewModel.stop()

        override fun onIslandGuide() = viewModel.guideToOffered()

        override fun onSwitch(open: Boolean): Boolean {
            if (open) {
                viewModel.openMenu()
                // Посетитель сам ушёл на экран 2 — итог поездки на острове его не держит.
                if (island.clear()) applyIsland()
            } else {
                viewModel.showFace()
            }
            // Состояние ViewModel обновлено синхронно — сразу отдаём, куда доехать (без кадра «туда-обратно»).
            refreshMenuOpen()
            return face.screen.menuOpen
        }
    }

    private val actions = object : ScreenTwoActions {
        override fun onTile(tile: Tile) {
            when (val a = tile.action) {
                is TileAction.Ask -> viewModel.submitButton(a.text)
                TileAction.Faq -> viewModel.openFaq()
                TileAction.Look -> viewModel.lookAround()
            }
        }

        override fun onLanguage(lang: Lang) = viewModel.selectLanguage(lang)

        override fun onAskVoice() = listenOnce()

        override fun onStop() {
            speechCutAt = SystemClock.uptimeMillis()
            viewModel.stop()
        }

        override fun onSend(text: String) {
            if (text.isNotBlank()) viewModel.submit(text)
        }

        override fun onGuide() = viewModel.guideToOffered()

        override fun onFaqToggle(item: QuickQuestion) = viewModel.faqToggle(item)

        override fun onFaqMore() = viewModel.faqNextPage()

        override fun onFaqBack() = viewModel.back()

        override fun onExit() = askExit()

        override fun onExitConfirm() = exitApp()

        override fun onReopen() = reopen()

        override fun onExitCancel() = stayIn()

        override fun onDiagnostics() = openDiagnostics()

        override fun onClose() {
            viewModel.showFace()
        }

        // --- кнопки прототипа ---

        override fun onBack() {
            // Наш «Назад» (история экранов ViewModel); разговор без экрана ответа (live) — к приветствию.
            if (viewModel.state.value.screen != Screen.HOME) viewModel.back() else showHomeView()
        }

        override fun onHome() {
            viewModel.goHome()
            showHomeView()
        }

        override fun onLook() = viewModel.lookAround()

        override fun onPlace(index: Int) {
            placeChipTargets.getOrNull(index)?.let(viewModel::askWhere)
        }

        override fun onCostume(c: kz.astanahub.robot.ui.screen.Costume) = setCostume(c)

        override fun onCostumeSwipe(step: Int) = setCostume(container.costume.costume.value.next(step))
    }

    /**
     * Тап по лицу: спит — проснуться; говорит — замолчать и слушать (tap-to-interrupt); говорит в поездке — замолчать,
     * поездка продолжается; молчит — рефлекс (моргнуть / слегка вздрогнуть).
     */
    private fun onFaceTap() {
        val tap = FaceInputMapper.tap(viewModel.state.value, viewModel.robotSpeaking)
        timber.log.Timber.d("face tap: %s", tap)
        when (tap) {
            FaceInputMapper.Tap.WAKE -> viewModel.wake()
            FaceInputMapper.Tap.STOP -> {
                speechCutAt = SystemClock.uptimeMillis()
                viewModel.stop()
            }
            FaceInputMapper.Tap.INTERRUPT_SPEECH -> {
                speechCutAt = SystemClock.uptimeMillis()
                viewModel.interruptSpeech()
            }
            FaceInputMapper.Tap.REFLEX -> timber.log.Timber.d("face tap: reflex %s", face.engine.reflex())
        }
    }

    private fun openDiagnostics() {
        val gate = container.diagnostics
        when (gate.access) {
            DiagAccess.DISABLED -> Unit
            DiagAccess.OPEN -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            DiagAccess.PIN -> showPin(PinPurpose.DIAGNOSTICS)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Пароль сотрудника — панель прототипа (цифры и латиница, регистр не важен). Два назначения:
    // диагностика (наша админка: удержание логотипа / лица) и выход (крестик, функция mynbay). Проверка —
    // DiagPinGuard, счётчик попыток общий (пароль нельзя подобрать через окно выхода), минута без ввода — закрыть.
    // ---------------------------------------------------------------------------------------------

    private val pin = PinPadState()
    private val pinBuffer = PinBuffer()
    private val pinTimeout = Runnable { closePin() }

    private fun showPin(purpose: PinPurpose) {
        if (pin.visible) return
        pin.purpose = purpose
        val lang = viewModel.state.value.lang
        pin.texts = when (purpose) {
            PinPurpose.DIAGNOSTICS -> PinTexts(
                title = "Диагностика",
                sub = "Только для сотрудников. Введите пароль.",
                ok = "Открыть",
                cancel = "Отмена",
            )
            PinPurpose.EXIT -> PinTexts(
                title = str(R.string.exit_question, lang),
                sub = str(R.string.exit_pin_sub, lang),
                ok = str(R.string.exit_yes, lang),
                cancel = str(R.string.exit_no, lang),
            )
        }
        pinBuffer.clear()
        pin.length = 0
        pin.message = ""
        renderPinLock()
        pin.visible = true
        if (purpose == PinPurpose.EXIT) viewModel.stop()
        handler.removeCallbacks(pinTimeout)
        handler.postDelayed(pinTimeout, PIN_TIMEOUT_MS)
    }

    private fun renderPinLock(): Boolean {
        val lock = container.diagnostics.guard.lockRemainingMillis()
        pin.locked = lock > 0
        if (pin.locked) {
            val minutes = (lock + 59_999) / 60_000
            pin.message = if (pin.purpose == PinPurpose.EXIT) {
                str(R.string.exit_pin_locked, viewModel.state.value.lang).format(minutes)
            } else {
                "Слишком много попыток. Попробуйте через $minutes мин."
            }
        }
        return pin.locked
    }

    private fun closePin() {
        handler.removeCallbacks(pinTimeout)
        val wasExit = pin.visible && pin.purpose == PinPurpose.EXIT
        pin.visible = false
        pinBuffer.clear()
        pin.length = 0
        if (wasExit) viewModel.wake()
    }

    private val pinActions = object : PinPadActions {
        override fun onPinKey(c: Char) {
            if (pin.locked || !pinBuffer.append(c)) return
            pin.length = pinBuffer.length
            pin.message = ""
            handler.removeCallbacks(pinTimeout)
            handler.postDelayed(pinTimeout, PIN_TIMEOUT_MS)
        }

        override fun onPinErase() {
            pinBuffer.backspace()
            pin.length = pinBuffer.length
        }

        override fun onPinSubmit() {
            if (renderPinLock() || !pinBuffer.isComplete) return
            val entered = pinBuffer.value
            pinBuffer.clear()
            pin.length = 0
            val gate = container.diagnostics
            when (val r = gate.guard.check(entered)) {
                DiagPinGuard.Result.Ok -> {
                    val purpose = pin.purpose
                    handler.removeCallbacks(pinTimeout)
                    pin.visible = false
                    when (purpose) {
                        PinPurpose.DIAGNOSTICS -> {
                            gate.session.unlock()
                            startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                        }
                        PinPurpose.EXIT -> exitApp()
                    }
                }
                is DiagPinGuard.Result.Wrong -> pin.message = if (pin.purpose == PinPurpose.EXIT) {
                    str(R.string.exit_pin_wrong, viewModel.state.value.lang).format(r.attemptsLeft)
                } else {
                    "Неверный пароль. Осталось попыток: ${r.attemptsLeft}"
                }
                is DiagPinGuard.Result.Locked -> renderPinLock()
            }
        }

        override fun onPinCancel() = closePin()
    }

    /** Справа — приветствие и плитки, пока после «В начало» в разговоре нет нового (прототип): id последней реплики
     *  на момент «В начало» (id в чате только растут — и после сброса чата, и когда старые реплики уходят за лимит). */
    private var homeLastId = 0L
    private var placeChipTargets: List<kz.astanahub.robot.backend.Place> = emptyList()

    private fun showHomeView() {
        homeLastId = maxOf(homeLastId, chatLog.items.lastOrNull()?.id ?: 0L)
        renderScreenTwo(viewModel.state.value)
    }

    /** Снимок «Что это?» на экране: декодируем уменьшенным в фоне; карточка — первый ответ робота после снимка. */
    private var photoSrc: kz.astanahub.robot.ui.LookPhoto? = null
    private var photoFrom = 0L

    private fun bindLookPhoto(p: kz.astanahub.robot.ui.LookPhoto?) {
        if (p !== photoSrc) {
            photoSrc = p
            photoFrom = chatLog.items.lastOrNull()?.id ?: 0L
            two.photo = null
            two.photoItemId = -1L
            if (p != null) {
                lifecycleScope.launch {
                    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeThumb(p.jpeg) }
                    if (photoSrc === p) two.photo = bmp
                }
            }
        }
        if (p != null && two.photoItemId < 0) {
            chatLog.items.firstOrNull { it.id > photoFrom && it.role == kz.astanahub.robot.ui.screen.ChatRole.ROBOT }
                ?.let { two.photoItemId = it.id }
        }
    }

    private fun decodeThumb(jpeg: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= LOOK_THUMB_WIDTH) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.asImageBitmap()
    }.onFailure { timber.log.Timber.w(it, "look photo decode") }.getOrNull()

    /**
     * Смена костюма (точки, свайп по роботу; прототип `setSkin(k, true)`): робот «собирается» — и при повторном выборе
     * того же костюма, лицо 1,8 с: обычный — «^ ^», казахский — смущение, айтишник — «смотрит», космонавт — звёзды.
     */
    private fun setCostume(c: kz.astanahub.robot.ui.screen.Costume) {
        container.costume.set(c)
        two.assemble++
        face.onCue(
            when (c) {
                kz.astanahub.robot.ui.screen.Costume.CLASSIC -> FaceCue.COSTUME_HAPPY
                kz.astanahub.robot.ui.screen.Costume.KAZAKH -> FaceCue.COSTUME_BLUSH
                kz.astanahub.robot.ui.screen.Costume.BUSINESS -> FaceCue.COSTUME_WIDE
                kz.astanahub.robot.ui.screen.Costume.GALAXY -> FaceCue.COSTUME_SPARKLE
            },
        )
    }

    // --- после речи (прототип `say` → `fin`) ---

    private var wasSpeaking = false
    private var wasOffline = false
    private var lastPhraseShown: String? = null

    /** Когда посетитель / оператор оборвал речь (Стоп, тап по лицу): такой конец речи — не «договорил». */
    private var speechCutAt = Long.MIN_VALUE / 2
    private val clearLinger = Runnable { two.linger = null }

    /**
     * Договорил (не оборвали): к последнему куску субтитров — эмодзи настроения, пузырь висит ещё max(8 с, 60 мс ×
     * длина), лицо 2,2 с показывает настроение (🤔 — «не понял», 👋 — смущение, 🎉 — звёзды, 📶 — «смотрит», прочее —
     * «^ ^»), экран головы кивает.
     */
    private fun afterSpeech(state: MainUiState) {
        val said = state.answer?.takeIf { it.isNotBlank() } ?: lastPhraseShown ?: return
        val emoji = kz.astanahub.robot.ui.MoodEmoji.forText(said)
        face.onCue(kz.astanahub.robot.ui.face.FaceDirector.moodFor(emoji))
        two.nod++
        val piece = lastPhraseShown?.let { kz.astanahub.robot.ui.screen.SubtitleWindow.lastPiece(it) }
        if (!piece.isNullOrBlank()) {
            two.linger = kz.astanahub.robot.ui.screen.Linger(piece, emoji)
            handler.removeCallbacks(clearLinger)
            handler.postDelayed(clearLinger, maxOf(LINGER_MIN_MS, LINGER_MS_PER_CHAR * said.length))
        }
    }

    private fun trackSpeech(state: MainUiState) {
        val speaking = state.phase == Phase.SPEAKING
        if (speaking) {
            two.phrase?.let { lastPhraseShown = it }
            if (two.linger != null) {
                handler.removeCallbacks(clearLinger)
                two.linger = null
            }
        } else if (wasSpeaking) {
            val natural = (state.phase == Phase.IDLE || state.phase == Phase.LISTENING) &&
                SystemClock.uptimeMillis() - speechCutAt > SPEECH_CUT_GRACE_MS
            if (natural) afterSpeech(state)
            lastPhraseShown = null
        }
        wasSpeaking = speaking
        // Новый вопрос или сон — пузырь с прошлым ответом убираем.
        if (two.linger != null && (state.processing || !state.partialQuestion.isNullOrBlank() || state.mode == ScreenMode.SLEEP)) {
            handler.removeCallbacks(clearLinger)
            two.linger = null
        }
        // Пропала связь — «кружится голова» 2,5 с (прототип: `dizzy`).
        if (state.offline && !wasOffline) face.onCue(FaceCue.OFFLINE)
        wasOffline = state.offline
    }

    /** «Спросить голосом»: одна фраза через серверный STT (на эмуляторе — микрофон хоста). */
    private fun listenOnce() {
        if (hasMicPermission()) {
            container.voice.stopSpeaking()
            container.voice.pushToTalk()
        } else {
            // Посетителям системный диалог не показываем: плашка «микрофон выключен» уже на экране.
            timber.log.Timber.w("mic button: RECORD_AUDIO not granted")
            renderMicBadge()
        }
    }

    private fun str(id: Int, lang: Lang): String = container.text.get(id, lang)

    // ---------------------------------------------------------------------------------------------
    // Отрисовка: MainUiState → состояние экранов (Compose перерисовывает только изменившееся)
    // ---------------------------------------------------------------------------------------------

    private fun render(state: MainUiState) {
        face.onState(state)
        val screen = face.screen
        screen.traveling = state.guidingTo != null
        screen.islandGuide = state.guideTarget != null && state.guidingTo == null
        screen.asleep = state.mode == ScreenMode.SLEEP
        if (islandTextsFor != state.lang) {
            islandTextsFor = state.lang
            screen.islandTexts = islandTexts(state.lang)
        }
        // Остров: поездка / итог / «где» (спит — острова нет). Ответ «где» из состояния до пересоздания экрана
        // (смена масштаба в диагностике) — уже показан, заново не всплывает.
        val now = SystemClock.uptimeMillis()
        if (firstRender) {
            firstRender = false
            island.markSeen(state.whereIs)
        }
        val changed = if (state.mode == ScreenMode.SLEEP) island.clear() else island.update(state.guidingTo, state.whereIs, now)
        if (changed) applyIsland() else refreshMenuOpen()
        // Удержание по лицу — вход в админку (тот же PIN); без PIN на роботе и в поездке жест выключен.
        screen.holdEnabled = ScreenTwoMapper.faceHoldEnabled(state, container.diagnostics.access != DiagAccess.DISABLED)
        // Уснул — диалог выхода закрывается (робот работает дальше).
        if (state.screensaver && two.exitConfirm) stayIn(wake = false)
        renderScreenTwo(state)
        trackSpeech(state)
    }

    private fun renderScreenTwo(state: MainUiState) {
        val lang = state.lang
        if (textsFor != lang) {
            textsFor = lang
            two.texts = texts(lang)
        }
        two.lang = lang
        two.status = str(statusRes(state.phase), lang)
        if (chatLog.update(state)) two.chat = chatLog.items
        two.partial = ScreenTwoMapper.partial(state)
        two.speakingId = ScreenTwoMapper.speakingId(state, chatLog.items)
        // Локальные ответы («где X», нет связи) звучат целиком без потока фраз — субтитры по всему ответу
        // (прототип: субтитры у любой речи робота).
        two.phrase = state.subtitle ?: state.answer?.takeIf { state.phase == Phase.SPEAKING && it.isNotBlank() }
        two.thinking = state.processing
        two.busy = ScreenTwoMapper.busy(state)
        two.tilesEnabled = !state.processing
        val tilesKey = listOf(lang, state.quickQuestions)
        if (tilesFor != tilesKey) {
            tilesFor = tilesKey
            two.tiles = Tiles.home(state.quickQuestions, str(R.string.tile_faq, lang), str(R.string.action_look, lang))
        }
        two.faq = ScreenTwoMapper.faq(
            state,
            status = { str(statusRes(it), lang) },
            more = { page, count -> container.text.get(R.string.faq_more, lang, page, count) },
        )
        two.guideLabel = ScreenTwoMapper.guideOffer(state)?.let {
            container.text.get(R.string.action_guide, lang, WayFinder.displayName(it, lang))
        }
        two.wayHint = state.wayHint
        two.offline = state.offline
        // Прототип: робот-компаньон слушает / думает / говорит; справа — приветствие, ответ или частые вопросы.
        two.pose = when (state.phase) {
            Phase.LISTENING -> if (state.partialQuestion.isNullOrBlank()) kz.astanahub.robot.ui.screen.CompanionPose.IDLE else kz.astanahub.robot.ui.screen.CompanionPose.LISTEN
            Phase.THINKING, Phase.LOOKING -> kz.astanahub.robot.ui.screen.CompanionPose.THINK
            Phase.SPEAKING -> kz.astanahub.robot.ui.screen.CompanionPose.SPEAK
            else -> kz.astanahub.robot.ui.screen.CompanionPose.IDLE
        }
        val home = state.screen == Screen.HOME && (chatLog.items.lastOrNull()?.id ?: 0L) <= homeLastId &&
            state.partialQuestion.isNullOrBlank() && !state.processing
        two.homeView = home
        two.chatFrom = homeLastId
        bindLookPhoto(state.lookPhoto)
        two.canGoBack = !home || state.screen != Screen.HOME
        // Чипы мест под ответом «где» (кроме места из ответа).
        placeChipTargets = if (!home && (state.whereIs != null || state.guideTarget != null)) {
            state.mapPlaces.filter { it.id != state.guideTarget?.id }.take(MAX_PLACE_CHIPS)
        } else {
            emptyList()
        }
        two.placeChips = placeChipTargets.map { WayFinder.displayName(it, lang) }
        two.faceAvailable = container.chatSettings.mode.value.usesAgent
        two.exitVisible = exitMode() != ExitMode.HIDDEN
    }

    /** Экран 2 открыт: режим MENU, не едем и остров не досказывает поездку. */
    private fun refreshMenuOpen() {
        val c = island.content
        val menuOpen = ScreenTwoMapper.menuOpen(
            viewModel.state.value,
            islandHoldsFace = c is IslandContent.Trip || c is IslandContent.Outcome,
        )
        face.screen.menuOpen = menuOpen
        // Глаза смотрят в сторону места, пока остров where_is на экране лица (на экране 2 острова нет).
        val inputs = face.engine.inputs
        if (c is IslandContent.WhereIs && !menuOpen) {
            inputs.lookX = IslandGeometry.gazeX(c.hint.visitorAngleRad)
            inputs.lookY = IslandGeometry.gazeY(c.hint.visitorAngleRad)
            inputs.looking = true
        } else {
            inputs.looking = false
        }
    }

    /** Остров → экран лица: содержимое, взгляд в сторону места (where_is), следующий тик таймера. */
    private fun applyIsland() {
        face.screen.island = island.content
        refreshMenuOpen()
        scheduleIsland()
    }

    private fun scheduleIsland() {
        handler.removeCallbacks(islandTick)
        val at = island.nextChangeAt() ?: return
        handler.postAtTime(islandTick, at)
    }

    private fun islandTexts(lang: Lang) = IslandTexts(
        tripTitle = str(R.string.guide_title, lang),
        stopTrip = str(R.string.action_stop, lang), // прототип: «Стоп» и на слое поездки
        guide = str(R.string.action_guide_short, lang),
        arrived = str(R.string.island_arrived, lang),
        failed = str(R.string.island_failed, lang),
        stopped = str(R.string.island_stopped, lang),
        meters = str(R.string.island_meters, lang),
        near = str(R.string.island_near, lang),
        follow = str(R.string.guide_follow, lang),
    )

    private fun texts(lang: Lang) = ScreenTexts(
        title = getString(R.string.title),
        greetTitle = str(R.string.greet_title, lang),
        greetSub = str(R.string.greet_sub, lang),
        tilesTitle = str(R.string.tiles_title, lang),
        askVoice = str(R.string.action_ask_voice, lang),
        stop = str(R.string.action_stop, lang),
        inputHint = str(R.string.input_hint, lang),
        faqTitle = str(R.string.faq_title, lang),
        showMore = str(R.string.action_show_more, lang),
        showLess = str(R.string.action_show_less, lang),
        guideShort = str(R.string.action_guide_short, lang),
        offline = str(R.string.offline_badge, lang),
        offlineBar = str(R.string.offline_bar, lang),
        micOff = str(R.string.mic_off_badge, lang),
        exitQuestion = str(R.string.exit_question, lang),
        exitSub = str(R.string.exit_sub, lang),
        exitYes = str(R.string.exit_yes, lang),
        exitNo = str(R.string.exit_no, lang),
        sourceOne = str(R.string.chat_source, lang),
        sourceMany = str(R.string.chat_sources, lang),
        back = str(R.string.action_back, lang),
        closed = str(R.string.app_closed, lang),
        closedSub = str(R.string.app_closed_sub, lang),
        reopen = str(R.string.app_reopen, lang),
        home = str(R.string.action_home, lang),
        menu = str(R.string.action_menu, lang),
        look = str(R.string.action_look, lang),
        costumes = listOf(
            str(R.string.costume_default, lang),
            str(R.string.costume_kazakh, lang),
            str(R.string.costume_business, lang),
            str(R.string.costume_galaxy, lang),
        ),
    )

    private fun statusRes(phase: Phase): Int = when (phase) {
        Phase.IDLE -> R.string.status_idle
        Phase.LISTENING -> R.string.status_listening
        Phase.THINKING -> R.string.status_thinking
        Phase.LOOKING -> R.string.status_looking
        Phase.SPEAKING -> R.string.status_speaking
        Phase.NAVIGATING -> R.string.status_navigating
    }

    // ---------------------------------------------------------------------------------------------
    // Выход (крестик). Как у mynbay: на роботе с паролем — только сотрудник (панель пароля); без пароля на роботе
    // крестика нет; на эмуляторе без пароля — прежнее подтверждение (наше, 15 с без ответа — остаёмся).
    // Перед закрытием — слой прототипа «Приложение закрыто» с «Открыть снова» (1,5 с — передумать), затем
    // приложение закрывается (на роботе — экран Android).
    // ---------------------------------------------------------------------------------------------

    private val exitTimeout = Runnable { stayIn() }
    private val closeNow = Runnable { finishAndRemoveTask() }

    private fun exitMode(): ExitMode = ExitMode.of(container.diagnostics.access)

    private fun askExit() {
        when (exitMode()) {
            ExitMode.HIDDEN -> return
            ExitMode.PIN -> showPin(PinPurpose.EXIT)
            ExitMode.CONFIRM -> {
                viewModel.stop()
                two.exitConfirm = true
                handler.removeCallbacks(exitTimeout)
                handler.postDelayed(exitTimeout, EXIT_CONFIRM_TIMEOUT_MS)
            }
        }
    }

    private fun stayIn(wake: Boolean = true) {
        handler.removeCallbacks(exitTimeout)
        two.exitConfirm = false
        if (wake) viewModel.wake()
    }

    private fun exitApp() {
        handler.removeCallbacks(exitTimeout)
        timber.log.Timber.i("exit requested from the screen")
        two.exitConfirm = false
        viewModel.stop()
        two.closed = true
        handler.removeCallbacks(closeNow)
        handler.postDelayed(closeNow, CLOSE_DELAY_MS)
    }

    /** «Открыть снова» на слое «Приложение закрыто»: передумали — работаем дальше. */
    private fun reopen() {
        handler.removeCallbacks(closeNow)
        two.closed = false
        viewModel.wake()
    }

    // ---------------------------------------------------------------------------------------------

    /** Любое касание экрана — активность посетителя (таймер сна). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!techPauseRedirected && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) viewModel.wake()
        return super.dispatchTouchEvent(ev)
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Разрешение на микрофон спрашиваем один раз — при первом запуске (оператор), не перед посетителями. */
    private fun requestMicPermissionOnce() {
        if (hasMicPermission()) return
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIC_ASKED, false)) return
        prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun renderMicBadge() {
        two.micOff = !hasMicPermission()
    }

    private fun enterKioskMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        /** Начертания Inter экранов 1 и 2. */
        const val PIN_TIMEOUT_MS = 60_000L
        const val MAX_PLACE_CHIPS = 5

        /** Пузырь с последней фразой и эмодзи после речи: max(8 с, 60 мс × длина) — прототип `fin`. */
        const val LINGER_MIN_MS = 8_000L
        const val LINGER_MS_PER_CHAR = 60L

        /** Речь кончилась вскоре после «Стоп» / тапа — её оборвали, настроения и кивка нет. */
        const val SPEECH_CUT_GRACE_MS = 400L
        const val LOOK_THUMB_WIDTH = 480
        const val CLOSE_DELAY_MS = 1_500L
        val FONTS = intArrayOf(R.font.nunito_regular, R.font.nunito_semibold, R.font.nunito_bold, R.font.nunito_extrabold)
        const val PREFS_UI = "main_ui"
        const val KEY_MIC_ASKED = "mic_permission_asked"
        const val EXIT_CONFIRM_TIMEOUT_MS = 15_000L
    }
}
