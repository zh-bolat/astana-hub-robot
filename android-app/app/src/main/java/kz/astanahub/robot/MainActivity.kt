package kz.astanahub.robot

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.databinding.ActivityMainBinding
import kz.astanahub.robot.databinding.ItemFaqRowBinding
import kz.astanahub.robot.databinding.ItemTileBinding
import kz.astanahub.robot.live.LiveDirectionsReporter
import kz.astanahub.robot.places.WayFinder
import kz.astanahub.robot.security.DiagAccess
import kz.astanahub.robot.ui.DiagPinDialog
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.MainViewModel
import kz.astanahub.robot.ui.MoodEmoji
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.Screen
import kz.astanahub.robot.ui.ScreenMode
import kz.astanahub.robot.ui.SubtitlePacer
import kz.astanahub.robot.ui.TileIcons
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.ui.face.FaceController
import kz.astanahub.robot.ui.face.FaceDebug
import kz.astanahub.robot.ui.face.FaceScreen
import kz.astanahub.robot.ui.setOnHoldListener

/**
 * Главный экран-киоск, вариант А (прототип: docs/design/robot-mvp.html).
 * Вся логика разговора и навигации по экранам — в [MainViewModel]; здесь только отрисовка состояния,
 * субтитры (темп — оценка, см. [SubtitlePacer]) и подтверждение выхода.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Экран 1: живое лицо (Compose). Прежний экран (меню) — под ним, до экрана 2 в M3. */
    private lateinit var face: FaceController
    private var faceDebug: FaceDebug? = null

    /** Фон окна и корня — убираем, пока лицо закрывает экран целиком (меньше перерисовки). */
    private var windowBackground: android.graphics.drawable.Drawable? = null
    private var rootBackground: android.graphics.drawable.Drawable? = null

    /** Что уже нарисовано (перерисовываем плитки и строки вопросов только при изменении). */
    private var renderedTiles: Any? = null
    private var renderedFaceChips: Any? = null
    private var renderedFaq: Any? = null

    /** Плитка, которую нажал посетитель (подсвечена, пока не ушли с приветствия). */
    private var selectedTile: String? = null

    /** «Показать полностью» в ответе и в раскрытом вопросе — состояние экрана, сбрасывается с новым ответом. */
    private var answerExpanded = false
    private var answerExpandedFor: String? = null
    private var faqExpanded = false
    private var faqExpandedFor: String? = null

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterKioskMode()
        setUpFaceScreen()

        binding.buildText.text = getString(
            R.string.build_label, getString(R.string.flavor_name), BuildConfig.BUILD_TYPE, BuildConfig.VERSION_NAME,
        )

        // Текстовый ввод — основной способ на эмуляторе; на роботе — голос, поле скрыто.
        binding.inputRow.isVisible = !BuildConfig.IS_ROBOT
        binding.sendButton.setOnClickListener { sendTyped() }
        binding.inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }

        // Верхняя полоса.
        binding.backButton.setOnClickListener { viewModel.back() }
        binding.langRu.setOnClickListener { viewModel.selectLanguage(Lang.RU) }
        binding.langKk.setOnClickListener { viewModel.selectLanguage(Lang.KK) }
        binding.langEn.setOnClickListener { viewModel.selectLanguage(Lang.EN) }
        binding.exitButton.setOnClickListener { askExit() }
        // Скрытый жест: удержание логотипа ≥ 3 с → PIN → диагностика. Без PIN на роботе жест отключён.
        if (container.diagnostics.access != DiagAccess.DISABLED) {
            binding.brandLogo.setOnHoldListener(DIAG_HOLD_MILLIS) { openDiagnostics() }
        }

        // Нижняя полоса.
        binding.homeButton.setOnClickListener { viewModel.goHome() }
        binding.hintButton.setOnClickListener { listenOnce() }
        binding.stopButton.setOnClickListener {
            viewModel.stop()
            showSubtitles(false)
        }
        binding.guideButton.setOnClickListener { viewModel.guideToOffered() }
        binding.faqMoreButton.setOnClickListener { viewModel.faqNextPage() }
        binding.lookButton.setOnClickListener { viewModel.lookAround() }
        binding.showMoreButton.setOnClickListener {
            answerExpanded = !answerExpanded
            render(viewModel.state.value)
        }

        // Лицо на весь экран (agent): меню — кнопкой или жестом вверх.
        binding.faceInputRow.isVisible = !BuildConfig.IS_ROBOT
        binding.faceSendButton.setOnClickListener { sendTyped(binding.faceInputText) }
        binding.faceInputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(binding.faceInputText); true } else false
        }
        binding.faceMenuButton.setOnClickListener { viewModel.openMenu() }
        binding.faceButton.setOnClickListener { viewModel.showFace() }
        binding.faceStopButton.setOnClickListener {
            viewModel.stop()
            showSubtitles(false)
        }
        binding.faceMicButton.setOnClickListener { listenOnce() }
        binding.faceLangRu.setOnClickListener { viewModel.selectLanguage(Lang.RU) }
        binding.faceLangKk.setOnClickListener { viewModel.selectLanguage(Lang.KK) }
        binding.faceLangEn.setOnClickListener { viewModel.selectLanguage(Lang.EN) }
        if (container.diagnostics.access != DiagAccess.DISABLED) {
            binding.faceLogo.setOnHoldListener(DIAG_HOLD_MILLIS) { openDiagnostics() }
        }
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dy = (e1?.y ?: return false) - e2.y
                if (dy > SWIPE_MIN_PX * resources.displayMetrics.density && velocityY < 0) {
                    viewModel.openMenu()
                    return true
                }
                return false
            }
        })
        binding.bigFace.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            swipe.onTouchEvent(ev)
        }
        binding.bigFace.setOnClickListener { binding.bigFace.wink() }
        binding.saverFace.sleeping = true

        // Слои поверх экрана.
        binding.guideStopButton.setOnClickListener { viewModel.stop() }
        binding.screensaver.setOnClickListener { viewModel.wake() }
        binding.exitOverlay.setOnClickListener { stayIn() } // касание мимо карточки = «Остаться»
        binding.exitNoButton.setOnClickListener { stayIn() }
        binding.exitYesButton.setOnClickListener { exitApp() }

        // Системная «Назад»: закрыть подтверждение выхода или вернуться на экран назад. На приветствии — ничего (киоск).
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.exitOverlay.isVisible -> stayIn()
                viewModel.state.value.canGoBack -> viewModel.back()
                viewModel.state.value.mode == ScreenMode.MENU -> viewModel.showFace()
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
     * Экран 1 — лицо (docs/ui-redesign/DESIGN.md). Виден в режимах FACE и SLEEP и во время поездки; в MENU уходит
     * влево с прозрачностью, под ним — прежний экран. Отладка (debug + emulator): adb broadcast [FaceDebug.ACTION].
     */
    private fun setUpFaceScreen() {
        face = FaceController(container.audioLevels, reducedMotion = !ValueAnimator.areAnimatorsEnabled())
        // Шрифт острова грузится заранее и не на главном потоке: первая поездка не теряет кадр на чтении .ttf.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (font in FACE_FONTS) runCatching { ResourcesCompat.getFont(this@MainActivity, font) }
        }
        windowBackground = window.decorView.background
        rootBackground = binding.root.background
        binding.faceScreen.setContent {
            FaceScreen(
                engine = face.engine,
                renderer = face.renderer,
                state = face.screen,
                onSwipeToMenu = { viewModel.openMenu() },
                onStop = { viewModel.stop() },
                onShown = { setLegacyCovered(true) },
                onHidden = { binding.faceScreen.isVisible = false },
            )
        }
        if (BuildConfig.DEBUG && !BuildConfig.IS_ROBOT) {
            faceDebug = FaceDebug(face, viewModel, container.audioLevels, container.presenceSimulator).also { it.register(this) }
        }
    }

    /** Лицо закрыло экран: прежний экран и фоны не рисуем (одна заливка вместо трёх). */
    private fun setLegacyCovered(covered: Boolean) {
        binding.menuRoot.visibility = if (covered) View.INVISIBLE else View.VISIBLE
        binding.root.background = if (covered) null else rootBackground
        window.setBackgroundDrawable(if (covered) null else windowBackground)
    }

    private fun renderFaceScreen(state: MainUiState) {
        face.onState(state)
        val visible = state.mode != ScreenMode.MENU || state.guidingTo != null
        val screen = face.screen
        if (visible != screen.visible) {
            if (visible) {
                binding.faceScreen.isVisible = true
            } else {
                setLegacyCovered(false)
            }
            screen.visible = visible
        }
        screen.travelTitle = state.guidingTo?.let { container.text.get(R.string.guide_title, state.lang, it) }
        screen.stopLabel = str(R.string.action_stop, state.lang)
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

    private fun sendTyped(input: android.widget.EditText = binding.inputText) {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        viewModel.submit(text)
        input.text = null
    }

    /** «Просто скажите вопрос»: одна фраза через серверный STT (на эмуляторе — микрофон хоста). */
    private fun listenOnce() {
        if (hasMicPermission()) {
            container.voice.stopSpeaking()
            container.voice.pushToTalk()
        } else {
            // Посетителям системный диалог не показываем: бейдж «микрофон выключен» уже на экране.
            timber.log.Timber.w("mic button: RECORD_AUDIO not granted")
            renderMicBadge()
        }
    }

    private fun str(id: Int, lang: Lang): String = container.text.get(id, lang)

    // ---------------------------------------------------------------------------------------------
    // Отрисовка
    // ---------------------------------------------------------------------------------------------

    private fun render(state: MainUiState) {
        val lang = state.lang

        // Верхняя полоса.
        binding.backButton.isVisible = state.canGoBack
        binding.backButton.text = str(R.string.action_back, lang)
        binding.langRu.isSelected = lang == Lang.RU
        binding.langKk.isSelected = lang == Lang.KK
        binding.langEn.isSelected = lang == Lang.EN
        binding.exitButton.contentDescription = str(R.string.exit_label, lang)
        binding.offlineBar.isVisible = state.offline
        binding.offlineText.text = str(R.string.offline_badge, lang)
        binding.micOffBadge.text = str(R.string.mic_off_badge, lang)

        // Слева: лицо, статус, что слышу, субтитры.
        binding.face.phase = state.phase
        binding.statusText.text = str(statusRes(state.phase), lang)
        binding.heardText.text = state.partialQuestion?.let { "«$it»" } ?: state.wayHint?.let { "\u27A4 $it" }.orEmpty()
        renderSubtitles(state)

        // Справа: один из трёх экранов. Ушли с приветствия — подсветка нажатой плитки снимается.
        if (state.screen != Screen.HOME && selectedTile != null) {
            selectedTile = null
            renderedTiles = null
        }
        binding.homePanel.isVisible = state.screen == Screen.HOME
        binding.answerPanel.isVisible = state.screen == Screen.ANSWER
        binding.faqPanel.isVisible = state.screen == Screen.FAQ
        when (state.screen) {
            Screen.HOME -> renderHome(state)
            Screen.ANSWER -> renderAnswer(state)
            Screen.FAQ -> renderFaq(state)
        }
        binding.inputLayout.hint = str(R.string.input_hint, lang)
        binding.sendButton.text = str(R.string.action_send, lang)
        binding.sendButton.isEnabled = !state.processing

        renderBottom(state)

        // Слои.
        renderFaceScreen(state)
        val guiding = state.guidingTo
        // Поездку и сон показывает экран лица (остров «Стоп», спящее лицо); прежние слои — только если его нет.
        binding.guideOverlay.isVisible = guiding != null && !face.screen.visible
        if (guiding != null) {
            binding.guideTitle.text = container.text.get(R.string.guide_title, lang, guiding)
            binding.guideSub.text = str(R.string.guide_follow, lang)
            binding.guideStopButton.text = str(R.string.action_stop, lang)
        }
        binding.screensaver.isVisible = state.screensaver && !face.screen.visible
        if (state.screensaver && binding.exitOverlay.isVisible) stayIn(wake = false)
        renderExitTexts(lang)
        renderFace(state)
    }

    /** Лицо на весь экран (FACE): статус, что слышу, кнопки частых вопросов или «Стоп». */
    private fun renderFace(state: MainUiState) {
        val lang = state.lang
        val faceMode = state.mode == ScreenMode.FACE
        // Прежний слой лица заменён экраном 1 (Compose); его кнопки — на прежнем экране меню до M3.
        binding.faceLayer.isVisible = false
        binding.face.emotion = state.emotion
        binding.faceButton.isVisible = state.mode == ScreenMode.MENU && container.chatSettings.mode.value.usesAgent
        binding.faceButton.text = str(R.string.action_face, lang)
        if (!faceMode) return

        binding.bigFace.phase = state.phase
        binding.bigFace.emotion = state.emotion
        binding.faceStatus.text = str(if (state.phase == Phase.IDLE) R.string.face_hint else statusRes(state.phase), lang)
        binding.faceLangRu.isSelected = lang == Lang.RU
        binding.faceLangKk.isSelected = lang == Lang.KK
        binding.faceLangEn.isSelected = lang == Lang.EN
        binding.faceOffline.isVisible = state.offline
        binding.faceOffline.text = str(R.string.offline_badge, lang)
        binding.faceHeard.text = state.partialQuestion?.let { "«$it»" }.orEmpty()
        // Живой режим, where_is: направление по карте (озвучивает сервер, робот показывает).
        binding.faceWay.isVisible = state.wayHint != null
        binding.faceWay.text = state.wayHint?.let { "\u27A4 $it" }.orEmpty()
        binding.faceMenuButton.text = str(R.string.action_menu, lang)
        binding.faceInputLayout.hint = str(R.string.input_hint, lang)
        binding.faceSendButton.text = str(R.string.action_send, lang)
        binding.faceSendButton.isEnabled = !state.processing

        val busy = state.processing || state.phase == Phase.SPEAKING || state.phase == Phase.NAVIGATING
        binding.faceStopButton.isVisible = busy
        binding.faceStopButton.text = str(R.string.action_stop, lang)
        binding.faceChips.isVisible = !busy
        val key = listOf(lang, state.quickQuestions)
        if (renderedFaceChips != key) {
            renderedFaceChips = key
            val chips = binding.faceChips
            chips.removeAllViews()
            state.quickQuestions.take(FACE_CHIPS).forEachIndexed { i, q ->
                val chip = TextView(this, null, 0, R.style.Hub_Chip).apply {
                    text = q.text
                    maxWidth = resources.getDimensionPixelSize(R.dimen.left_column_width)
                    setOnClickListener { viewModel.submitButton(q.text) }
                }
                chips.addView(chip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, resources.getDimensionPixelSize(R.dimen.chip_height),
                ).apply { if (i > 0) marginStart = resources.getDimensionPixelSize(R.dimen.tile_gap) })
            }
        }
    }

    private fun statusRes(phase: Phase): Int = when (phase) {
        Phase.IDLE -> R.string.status_idle
        Phase.LISTENING -> R.string.status_listening
        Phase.THINKING -> R.string.status_thinking
        Phase.LOOKING -> R.string.status_looking
        Phase.SPEAKING -> R.string.status_speaking
        Phase.NAVIGATING -> R.string.status_navigating
    }

    private fun renderBottom(state: MainUiState) {
        val lang = state.lang
        val busy = state.processing || state.phase == Phase.SPEAKING
        val target = state.guideTarget
        val showGuide = !busy && state.screen == Screen.ANSWER && target != null && state.guidingTo == null
        val showMore = !busy && state.screen == Screen.FAQ && state.faqPageCount > 1

        binding.homeButton.isVisible = state.screen != Screen.HOME
        binding.homeButton.text = str(R.string.action_home, lang)

        binding.stopButton.isVisible = busy
        binding.stopButton.text = str(R.string.action_stop, lang)
        binding.guideButton.isVisible = showGuide
        if (showGuide && target != null) {
            binding.guideButton.text = container.text.get(R.string.action_guide, lang, WayFinder.displayName(target, lang))
        }
        binding.faqMoreButton.isVisible = showMore
        binding.faqMoreButton.text = container.text.get(R.string.faq_more, lang, state.faqPage + 1, state.faqPageCount)
        binding.hintButton.isVisible = !busy && !showGuide && !showMore
        binding.hintButton.text = str(R.string.hint_voice, lang)

        // «Что это?» на приветствии — плитка, внизу дублировать не нужно.
        binding.lookButton.isVisible = state.screen != Screen.HOME
        binding.lookButton.text = str(R.string.action_look, lang)
        setEnabled(binding.lookButton, !state.processing)
    }

    /** Приветствие: до 4 частых вопросов плитками + «Частые вопросы» (весь список) + «Что это?». */
    private fun renderHome(state: MainUiState) {
        val lang = state.lang
        binding.greetTitle.text = str(R.string.greet_title, lang)
        binding.greetSub.text = str(R.string.greet_sub, lang)
        val enabled = !state.processing
        val key = listOf(lang, state.quickQuestions, enabled, selectedTile)
        if (renderedTiles == key) return
        renderedTiles = key

        data class Tile(val icon: Int, val label: String, val onClick: () -> Unit)
        val tiles = state.quickQuestions.take(HOME_QUESTION_TILES).map { q ->
            Tile(TileIcons.kindFor(q.text).res, q.text) { viewModel.submitButton(q.text) }
        } + Tile(R.drawable.ic_tile_help, str(R.string.tile_faq, lang)) { viewModel.openFaq() } +
            Tile(R.drawable.ic_tile_camera, str(R.string.action_look, lang)) { viewModel.lookAround() }

        val rows = listOf(binding.tilesRow1, binding.tilesRow2)
        rows.forEach { it.removeAllViews() }
        val inflater = LayoutInflater.from(this)
        tiles.chunked(TILES_PER_ROW).take(rows.size).forEachIndexed { r, rowTiles ->
            val row = rows[r]
            rowTiles.forEachIndexed { i, tile ->
                val b = ItemTileBinding.inflate(inflater, row, false)
                b.tileIcon.setImageResource(tile.icon)
                b.tileLabel.text = tile.label
                b.root.isSelected = tile.label == selectedTile
                setEnabled(b.root, enabled)
                b.root.setOnClickListener {
                    // Подсвечиваем только нажатую плитку и блокируем все сразу, не дожидаясь нового состояния (двойной тап).
                    rows.forEach { rr ->
                        for (c in 0 until rr.childCount) rr.getChildAt(c).apply { isEnabled = false; isSelected = false }
                    }
                    b.root.isSelected = true
                    selectedTile = tile.label
                    renderedTiles = null
                    tile.onClick()
                }
                (b.root.layoutParams as LinearLayout.LayoutParams).marginEnd =
                    if (i < TILES_PER_ROW - 1) resources.getDimensionPixelSize(R.dimen.tile_gap) else 0
                row.addView(b.root)
            }
            // Неполный ряд: пустые места, чтобы плитки не растягивались.
            repeat(TILES_PER_ROW - rowTiles.size) { k ->
                row.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                        if (rowTiles.size + k < TILES_PER_ROW - 1) marginEnd = resources.getDimensionPixelSize(R.dimen.tile_gap)
                    }
                })
            }
        }
    }

    private fun renderAnswer(state: MainUiState) {
        val lang = state.lang
        binding.questionText.text = state.question?.let { "«$it»" }.orEmpty()
        binding.questionText.isVisible = state.question != null
        val answer = state.answer
        if (answer != answerExpandedFor) {
            answerExpandedFor = answer
            answerExpanded = false
        }
        binding.answerText.text = answer ?: str(statusRes(state.phase), lang)
        binding.answerText.setTextColor(color(if (answer == null) R.color.hub_muted2 else R.color.hub_ink))
        binding.answerText.maxLines = if (answerExpanded) Int.MAX_VALUE else ANSWER_LINES
        binding.showMoreButton.text = str(if (answerExpanded) R.string.action_show_less else R.string.action_show_more, lang)
        binding.answerText.post { binding.showMoreButton.isVisible = answer != null && (answerExpanded || isEllipsized(binding.answerText)) }
        if (!answerExpanded) binding.answerScroll.scrollTo(0, 0)
    }

    /** Частые вопросы: аккордеон, раскрыт не больше одного вопроса. */
    private fun renderFaq(state: MainUiState) {
        val lang = state.lang
        binding.faqTitle.text = str(R.string.faq_title, lang)
        val openItem = state.faqPageItems.firstOrNull { it.id == state.faqOpenId }
        val answer = state.answer.takeIf { openItem != null && state.question == openItem.text.trim() }
        if (answer != faqExpandedFor) {
            faqExpandedFor = answer
            faqExpanded = false
        }
        val key = listOf(lang, state.faqPageItems, state.faqOpenId, answer, state.phase, state.guideTarget, faqExpanded)
        if (renderedFaq == key) return
        renderedFaq = key

        val list = binding.faqList
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        state.faqPageItems.forEach { item ->
            val row = ItemFaqRowBinding.inflate(inflater, list, false)
            val open = item.id == state.faqOpenId
            row.rowQuestion.text = item.text
            row.rowHeader.setOnClickListener { viewModel.faqToggle(item) }
            row.rowHeader.contentDescription = item.text
            if (open) {
                row.root.setBackgroundResource(R.drawable.bg_row_open)
                row.root.elevation = resources.getDimension(R.dimen.elev_card)
                row.rowQuestion.typeface = ResourcesCompat.getFont(this, R.font.nunito_bold)
                row.rowChevron.rotation = 180f
                row.rowChevron.imageTintList = ColorStateList.valueOf(color(R.color.hub_primary))
                row.rowBody.isVisible = true
                row.rowAnswer.text = answer ?: str(statusRes(if (state.processing) state.phase else Phase.THINKING), lang)
                row.rowAnswer.setTextColor(color(if (answer == null) R.color.hub_muted2 else R.color.hub_ink))
                row.rowAnswer.maxLines = if (faqExpanded) FAQ_EXPANDED_LINES else FAQ_LINES
                val target = state.guideTarget
                row.rowGuide.isVisible = answer != null && target != null && state.guidingTo == null
                row.rowGuide.text = str(R.string.action_guide_short, lang)
                row.rowGuide.setOnClickListener { viewModel.guideToOffered() }
                row.rowMore.text = str(if (faqExpanded) R.string.action_show_less else R.string.action_show_more, lang)
                row.rowMore.setOnClickListener {
                    faqExpanded = !faqExpanded
                    render(viewModel.state.value)
                }
                row.rowAnswer.post {
                    row.rowMore.isVisible = answer != null && (faqExpanded || isEllipsized(row.rowAnswer))
                    row.rowActions.isVisible = row.rowGuide.isVisible || row.rowMore.isVisible
                }
            }
            list.addView(row.root)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Субтитры: облачко под лицом, пока робот говорит. Произнесённые слова тёмные, следующие — бледные.
    // Договорил — в конце эмодзи настроения, облачко остаётся max(8 с, 60 мс/символ) или до следующего действия.
    // ---------------------------------------------------------------------------------------------

    private val pacer = SubtitlePacer()
    private var subtitlePlan: List<SubtitlePacer.Word> = emptyList()
    private var subtitleFor: String? = null
    private var subtitleStarted = 0L
    private var subtitleLingering = false
    private var subtitleScreen: Screen? = null

    /** Субтитры показаны (облачко под лицом в меню или крупные — в режиме лица). */
    private var subtitleShown = false
    private var subtitleFaceMode = false

    private fun subtitleView(): TextView = if (subtitleFaceMode) binding.faceSubtitle else binding.subtitleText

    private val subtitleTick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - subtitleStarted
            val frame = pacer.frameAt(subtitlePlan, elapsed) ?: return
            subtitleView().text = subtitleSpan(frame)
            if (elapsed < pacer.durationMillis(subtitlePlan)) handler.postDelayed(this, SUBTITLE_TICK_MS)
        }
    }

    private val subtitleHide = Runnable { showSubtitles(false) }

    private fun renderSubtitles(state: MainUiState) {
        val text = state.subtitle ?: state.answer
        val faceMode = state.mode == ScreenMode.FACE
        if (subtitleShown && faceMode != subtitleFaceMode) showSubtitles(false)
        if (state.phase == Phase.SPEAKING && !text.isNullOrBlank()) {
            if (text != subtitleFor || !subtitleShown || subtitleLingering) {
                subtitleFaceMode = faceMode
                handler.removeCallbacks(subtitleHide)
                subtitleFor = text
                subtitleLingering = false
                subtitleScreen = state.screen
                subtitlePlan = pacer.plan(text)
                subtitleStarted = SystemClock.uptimeMillis()
                handler.removeCallbacks(subtitleTick)
                showSubtitles(true)
                subtitleTick.run()
            }
        } else if (subtitleShown) {
            // Следующее действие (слушает, думает, ведёт, другой экран) — облачко больше не нужно.
            val interrupted = state.phase != Phase.IDLE || state.screen != subtitleScreen
            if (interrupted) showSubtitles(false) else if (!subtitleLingering) finishSubtitles()
        }
    }

    /** Договорил: вся последняя фраза тёмная, в конце — эмодзи настроения; иногда робот подмигивает. */
    private fun finishSubtitles() {
        handler.removeCallbacks(subtitleTick)
        subtitleLingering = true
        pacer.frameAt(subtitlePlan, Long.MAX_VALUE)?.let {
            subtitleView().text = SpannableStringBuilder(subtitleSpan(it)).append(' ').append(MoodEmoji.forText(subtitleFor))
        }
        if (Math.random() < SUBTITLE_WINK_CHANCE) (if (subtitleFaceMode) binding.bigFace else binding.face).wink()
        val linger = maxOf(SUBTITLE_LINGER_MIN_MS, SUBTITLE_LINGER_PER_CHAR_MS * subtitleFor.orEmpty().length)
        handler.postDelayed(subtitleHide, linger)
    }

    private fun showSubtitles(show: Boolean) {
        if (!show) {
            subtitleFor = null
            subtitleLingering = false
            handler.removeCallbacks(subtitleTick)
            handler.removeCallbacks(subtitleHide)
        }
        subtitleShown = show
        binding.subtitleBox.isVisible = show && !subtitleFaceMode
        binding.faceSubtitle.isVisible = show && subtitleFaceMode
        // Пока есть облачко, подпись статуса и «что слышу» не нужны: место под лицом одно.
        binding.statusText.isVisible = !(show && !subtitleFaceMode)
        binding.heardText.isVisible = !(show && !subtitleFaceMode)
    }

    private fun subtitleSpan(frame: SubtitlePacer.Frame): CharSequence {
        val sb = SpannableStringBuilder()
        val spoken = color(R.color.hub_ink)
        val upcoming = color(R.color.hub_sub_upcoming)
        frame.words.forEachIndexed { i, w ->
            if (i > 0) sb.append(' ')
            val start = sb.length
            sb.append(w)
            sb.setSpan(ForegroundColorSpan(if (i < frame.spoken) spoken else upcoming), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ---------------------------------------------------------------------------------------------
    // Выход: крестик → подтверждение. Никто не ответил за 15 с — окно закрывается, робот работает дальше.
    // ---------------------------------------------------------------------------------------------

    private val exitTimeout = Runnable { stayIn() }

    private fun askExit() {
        viewModel.stop()
        renderExitTexts(viewModel.state.value.lang)
        binding.exitOverlay.isVisible = true
        handler.removeCallbacks(exitTimeout)
        handler.postDelayed(exitTimeout, EXIT_CONFIRM_TIMEOUT_MS)
    }

    private fun renderExitTexts(lang: Lang) {
        binding.exitQuestion.text = str(R.string.exit_question, lang)
        binding.exitSub.text = str(R.string.exit_sub, lang)
        binding.exitNoButton.text = str(R.string.exit_no, lang)
        binding.exitYesButton.text = str(R.string.exit_yes, lang)
    }

    private fun stayIn(wake: Boolean = true) {
        handler.removeCallbacks(exitTimeout)
        binding.exitOverlay.isVisible = false
        if (wake) viewModel.wake()
    }

    private fun exitApp() {
        handler.removeCallbacks(exitTimeout)
        timber.log.Timber.i("exit requested from the screen")
        viewModel.stop()
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------------------------------------

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun isEllipsized(view: TextView): Boolean {
        val layout = view.layout ?: return false
        val lines = layout.lineCount
        return lines > 0 && (layout.getEllipsisCount(lines - 1) > 0 || lines > view.maxLines)
    }

    /** Любое касание экрана — активность посетителя (таймер заставки). */
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
        if (!::binding.isInitialized) return
        binding.micOffBadge.visibility = if (hasMicPermission()) View.GONE else View.VISIBLE
    }

    private fun enterKioskMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        /** Начертания Inter экрана 1 (остров поездки). */
        val FACE_FONTS = intArrayOf(R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold)
        const val DIAG_HOLD_MILLIS = 3_000L
        const val PREFS_UI = "main_ui"
        const val KEY_MIC_ASKED = "mic_permission_asked"
        const val HOME_QUESTION_TILES = 4
        const val FACE_CHIPS = 3
        const val SWIPE_MIN_PX = 120
        const val TILES_PER_ROW = 3
        const val ANSWER_LINES = 3
        const val FAQ_LINES = 2
        const val FAQ_EXPANDED_LINES = 8
        const val SUBTITLE_TICK_MS = 60L
        const val SUBTITLE_LINGER_MIN_MS = 8_000L
        const val SUBTITLE_LINGER_PER_CHAR_MS = 60L
        const val SUBTITLE_WINK_CHANCE = 0.5
        const val EXIT_CONFIRM_TIMEOUT_MS = 15_000L
        const val DISABLED_ALPHA = 0.55f
    }
}
