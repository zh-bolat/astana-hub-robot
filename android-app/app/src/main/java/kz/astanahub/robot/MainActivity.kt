package kz.astanahub.robot

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import kz.astanahub.robot.ui.DiagPinDialog
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.MainViewModel
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.Screen
import kz.astanahub.robot.ui.ScreenMode
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.ui.face.FaceController
import kz.astanahub.robot.ui.face.FaceDebug
import kz.astanahub.robot.ui.face.FaceInputMapper
import kz.astanahub.robot.ui.screen.ChatLog
import kz.astanahub.robot.ui.screen.ChatRole
import kz.astanahub.robot.ui.screen.FaqUi
import kz.astanahub.robot.ui.screen.RobotScreens
import kz.astanahub.robot.ui.screen.RobotScreensHost
import kz.astanahub.robot.ui.screen.ScreenTexts
import kz.astanahub.robot.ui.screen.ScreenTwoActions
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
                viewModel.faceCues.collect { face.onCue(it) }
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
        two.reducedMotion = reduced
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
                    // Удержание по лицу — вход в админку (тот же PIN); без PIN на роботе жест отключён, как и раньше.
                    holdEnabled = container.diagnostics.access != DiagAccess.DISABLED,
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

        override fun onSwitch(open: Boolean): Boolean {
            if (open) viewModel.openMenu() else viewModel.showFace()
            // Состояние ViewModel обновлено синхронно — сразу отдаём, куда доехать (без кадра «туда-обратно»).
            val menuOpen = menuOpen(viewModel.state.value)
            face.screen.menuOpen = menuOpen
            return menuOpen
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

        override fun onStop() = viewModel.stop()

        override fun onSend(text: String) {
            if (text.isNotBlank()) viewModel.submit(text)
        }

        override fun onGuide() = viewModel.guideToOffered()

        override fun onFaqToggle(item: QuickQuestion) = viewModel.faqToggle(item)

        override fun onFaqMore() = viewModel.faqNextPage()

        override fun onFaqBack() = viewModel.back()

        override fun onExit() = askExit()

        override fun onExitConfirm() = exitApp()

        override fun onExitCancel() = stayIn()

        override fun onDiagnostics() = openDiagnostics()

        override fun onClose() {
            viewModel.showFace()
        }
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
            FaceInputMapper.Tap.STOP -> viewModel.stop()
            FaceInputMapper.Tap.INTERRUPT_SPEECH -> viewModel.interruptSpeech()
            FaceInputMapper.Tap.REFLEX -> timber.log.Timber.d("face tap: reflex %s", face.engine.reflex())
        }
    }

    private fun openDiagnostics() {
        val gate = container.diagnostics
        when (gate.access) {
            DiagAccess.DISABLED -> Unit
            DiagAccess.OPEN -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            DiagAccess.PIN -> DiagPinDialog.show(this, gate.guard) {
                gate.session.unlock()
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
        }
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

    /** Экран 2 открыт: режим MENU и не едем (в поездке лицо обязано быть на экране). */
    private fun menuOpen(state: MainUiState): Boolean = state.mode == ScreenMode.MENU && state.guidingTo == null

    private fun render(state: MainUiState) {
        face.onState(state)
        val screen = face.screen
        screen.menuOpen = menuOpen(state)
        screen.travelTitle = state.guidingTo?.let { container.text.get(R.string.guide_title, state.lang, it) }
        screen.stopLabel = str(R.string.action_stop, state.lang)
        screen.asleep = state.mode == ScreenMode.SLEEP
        // Уснул — диалог выхода закрывается (робот работает дальше).
        if (state.screensaver && two.exitConfirm) stayIn(wake = false)
        renderScreenTwo(state)
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
        two.partial = state.partialQuestion?.takeIf { it.isNotBlank() }
        val speaking = state.phase == Phase.SPEAKING
        two.speakingId = if (speaking) chatLog.items.lastOrNull { it.role == ChatRole.ROBOT }?.id else null
        two.phrase = state.subtitle
        two.thinking = state.processing
        two.busy = state.processing || speaking
        two.tilesEnabled = !state.processing
        val tilesKey = listOf(lang, state.quickQuestions)
        if (tilesFor != tilesKey) {
            tilesFor = tilesKey
            two.tiles = Tiles.home(state.quickQuestions, str(R.string.tile_faq, lang), str(R.string.action_look, lang))
        }
        two.faq = if (state.screen == Screen.FAQ) faq(state) else null
        val target = state.guideTarget
        two.guideLabel = if (!two.busy && target != null && state.guidingTo == null && state.screen != Screen.FAQ) {
            container.text.get(R.string.action_guide, lang, WayFinder.displayName(target, lang))
        } else {
            null
        }
        two.wayHint = state.wayHint
        two.offline = state.offline
    }

    /** Частые вопросы: аккордеон, раскрыт не больше одного вопроса (как на прежнем экране). */
    private fun faq(state: MainUiState): FaqUi {
        val lang = state.lang
        val openItem = state.faqPageItems.firstOrNull { it.id == state.faqOpenId }
        val answer = state.answer.takeIf { openItem != null && state.question == openItem.text.trim() }
        return FaqUi(
            items = state.faqPageItems,
            openId = state.faqOpenId,
            answer = answer,
            pending = str(statusRes(if (state.processing) state.phase else Phase.THINKING), lang),
            guide = answer != null && state.guideTarget != null && state.guidingTo == null,
            more = if (state.faqPageCount > 1) container.text.get(R.string.faq_more, lang, state.faqPage + 1, state.faqPageCount) else null,
        )
    }

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
        micOff = str(R.string.mic_off_badge, lang),
        exitQuestion = str(R.string.exit_question, lang),
        exitSub = str(R.string.exit_sub, lang),
        exitYes = str(R.string.exit_yes, lang),
        exitNo = str(R.string.exit_no, lang),
        sourceOne = str(R.string.chat_source, lang),
        sourceMany = str(R.string.chat_sources, lang),
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
    // Выход: крестик → подтверждение. Никто не ответил за 15 с — окно закрывается, робот работает дальше.
    // ---------------------------------------------------------------------------------------------

    private val exitTimeout = Runnable { stayIn() }

    private fun askExit() {
        viewModel.stop()
        two.exitConfirm = true
        handler.removeCallbacks(exitTimeout)
        handler.postDelayed(exitTimeout, EXIT_CONFIRM_TIMEOUT_MS)
    }

    private fun stayIn(wake: Boolean = true) {
        handler.removeCallbacks(exitTimeout)
        two.exitConfirm = false
        if (wake) viewModel.wake()
    }

    private fun exitApp() {
        handler.removeCallbacks(exitTimeout)
        timber.log.Timber.i("exit requested from the screen")
        viewModel.stop()
        finishAndRemoveTask()
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
        val FONTS = intArrayOf(R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold, R.font.inter_bold)
        const val PREFS_UI = "main_ui"
        const val KEY_MIC_ASKED = "mic_permission_asked"
        const val EXIT_CONFIRM_TIMEOUT_MS = 15_000L
    }
}
