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
import kz.astanahub.robot.ui.UiScale

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
                )
            }
        }
    }

    private var renderedQuick: Pair<Lang, List<QuickQuestion>>? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        timber.log.Timber.i("RECORD_AUDIO granted=%s", granted)
    }

    private var appliedScale = 0

    override fun attachBaseContext(newBase: android.content.Context) {
        appliedScale = UiScale.percent(newBase)
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    private var backendUrlSeen: String? = null

    override fun onResume() {
        super.onResume()
        val url = container.backend.url.value
        if (backendUrlSeen != null && backendUrlSeen != url) viewModel.refreshFromServer()
        backendUrlSeen = url
        // Масштаб меняют на экране диагностики — применяем без перезапуска приложения.
        if (UiScale.percent(this) != appliedScale) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            if (ensureMicPermission()) {
                container.voice.stopSpeaking()
                container.voice.pushToTalk()
            }
        }
        // Скрытый жест: долгое нажатие на логотип → диагностика голоса и выбор режима.
        binding.brandText.setOnLongClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java)); true
        }
        ensureMicPermission()
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
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) viewModel.wake()
        return super.dispatchTouchEvent(ev)
    }

    private fun ensureMicPermission(): Boolean {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        return granted
    }

    private fun enterKioskMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
