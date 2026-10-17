package kz.astanahub.robot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.databinding.ActivityMainBinding
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.MainViewModel
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.security.DiagAccess
import kz.astanahub.robot.ui.DiagPinDialog
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.ui.setOnHoldListener

/** Главный экран-киоск (шаг 1.6). Вся логика — в [MainViewModel]. */
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
                )
            }
        }
    }

    private var renderedQuick: Pair<Lang, List<QuickQuestion>>? = null

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

        binding.buildText.text = getString(
            R.string.build_label, getString(R.string.flavor_name), BuildConfig.BUILD_TYPE, BuildConfig.VERSION_NAME,
        )

        // Текстовый ввод — основной способ на эмуляторе; на роботе — голос, поле скрыто.
        val showTextInput = !BuildConfig.IS_ROBOT
        binding.inputLayout.visibility = if (showTextInput) View.VISIBLE else View.GONE
        binding.sendButton.visibility = if (showTextInput) View.VISIBLE else View.GONE

        binding.sendButton.setOnClickListener { sendTyped() }
        binding.inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        binding.lookButton.setOnClickListener { viewModel.lookAround() }
        // Микрофон: одна фраза через серверный STT (на эмуляторе — микрофон хоста).
        binding.micButton.setOnClickListener {
            if (hasMicPermission()) {
                container.voice.stopSpeaking()
                container.voice.pushToTalk()
            } else {
                // Посетителям системный диалог не показываем: бейдж «микрофон выключен» уже на экране.
                timber.log.Timber.w("mic button: RECORD_AUDIO not granted")
                renderMicBadge()
            }
        }
        // Скрытый жест: удержание логотипа ≥ 3 с → PIN → диагностика. Без PIN на роботе жест отключён.
        if (container.diagnostics.access != DiagAccess.DISABLED) {
            binding.brandText.setOnHoldListener(DIAG_HOLD_MILLIS) { openDiagnostics() }
        }
        // Киоск: «Назад» на главном экране ничего не делает.
        onBackPressedDispatcher.addCallback(this) { }
        requestMicPermissionOnce()
        binding.stopButton.setOnClickListener { viewModel.stop() }
        binding.guideButton.setOnClickListener { viewModel.guideToOffered() }
        binding.screensaver.setOnClickListener { viewModel.wake() }

        binding.langGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val lang = when (checkedId) {
                R.id.langKk -> Lang.KK
                R.id.langEn -> Lang.EN
                else -> Lang.RU
            }
            if (lang != viewModel.state.value.lang) viewModel.selectLanguage(lang)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
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

    private fun sendTyped() {
        val text = binding.inputText.text?.toString().orEmpty()
        if (text.isBlank()) return
        viewModel.submit(text)
        binding.inputText.text = null
    }

    private fun render(state: MainUiState) {
        val lang = state.lang
        val t = container.text

        val langId = when (lang) { Lang.RU -> R.id.langRu; Lang.KK -> R.id.langKk; Lang.EN -> R.id.langEn }
        if (binding.langGroup.checkedButtonId != langId) binding.langGroup.check(langId)

        val (statusRes, colorRes) = when (state.phase) {
            Phase.IDLE -> R.string.status_idle to R.color.hub_idle
            Phase.LISTENING -> R.string.status_listening to R.color.hub_listening
            Phase.THINKING -> R.string.status_thinking to R.color.hub_thinking
            Phase.LOOKING -> R.string.status_looking to R.color.hub_thinking
            Phase.SPEAKING -> R.string.status_speaking to R.color.hub_speaking
            Phase.NAVIGATING -> R.string.status_navigating to R.color.hub_listening
        }
        binding.statusText.text = t.get(statusRes, lang)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        val question = state.partialQuestion ?: state.question
        binding.questionText.text = question?.let { "«$it»" }.orEmpty()
        binding.answerText.text = state.answer
            ?: if (state.processing) t.get(statusRes, lang) else t.get(R.string.hint_idle, lang)

        // Пока идёт запрос — кнопки вопросов неактивны (защита от многократного нажатия).
        val enabled = !state.processing
        for (i in 0 until binding.quickContainer.childCount) binding.quickContainer.getChildAt(i).isEnabled = enabled
        binding.sendButton.isEnabled = enabled
        binding.lookButton.isEnabled = enabled
        binding.offlineBadge.text = t.get(R.string.offline_badge, lang)
        binding.offlineBadge.visibility = if (state.offline) View.VISIBLE else View.GONE
        binding.micOffBadge.text = t.get(R.string.mic_off_badge, lang)

        binding.quickTitle.text = t.get(R.string.quick_questions_title, lang)
        binding.inputLayout.hint = t.get(R.string.input_hint, lang)
        binding.sendButton.text = t.get(R.string.action_send, lang)
        binding.lookButton.text = t.get(R.string.action_look, lang)
        binding.stopButton.text = t.get(R.string.action_stop, lang)
        val target = state.guideTarget
        binding.guideButton.visibility = if (target != null && state.guidingTo == null) View.VISIBLE else View.GONE
        if (target != null) {
            binding.guideButton.text = t.get(R.string.action_guide, lang, kz.astanahub.robot.places.WayFinder.displayName(target, lang))
        }
        binding.screensaver.visibility = if (state.screensaver) View.VISIBLE else View.GONE
        binding.micButton.text = t.get(R.string.action_mic, lang)

        renderQuickQuestions(lang, state.quickQuestions, enabled)
    }

    private fun renderQuickQuestions(lang: Lang, items: List<QuickQuestion>, enabled: Boolean) {
        if (renderedQuick == lang to items) return
        renderedQuick = lang to items
        val parent = binding.quickContainer
        parent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEach { q ->
            val button = inflater.inflate(R.layout.item_quick_question, parent, false) as Button
            button.text = q.text
            button.isEnabled = enabled
            button.setOnClickListener {
                // Блокируем сразу, не дожидаясь нового состояния (двойной тап).
                for (i in 0 until parent.childCount) parent.getChildAt(i).isEnabled = false
                viewModel.submit(q.text)
            }
            parent.addView(button)
        }
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
        const val DIAG_HOLD_MILLIS = 3_000L
        const val PREFS_UI = "main_ui"
        const val KEY_MIC_ASKED = "mic_permission_asked"
    }
}
