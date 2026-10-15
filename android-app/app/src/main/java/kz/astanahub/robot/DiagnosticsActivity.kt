package kz.astanahub.robot

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.backend.SwitchableBackendApi
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.databinding.ActivityDiagnosticsBinding
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.voice.ServerSpeakResult
import kz.astanahub.robot.voice.VoiceSource
import com.google.android.material.button.MaterialButtonToggleGroup
import timber.log.Timber

/**
 * Скрытый экран диагностики голоса (долгое нажатие на «Astana Hub» на главном экране).
 * Для спайков 2.3–2.5 на роботе: встроенный vs серверный TTS, лог встроенного ASR и серверного STT
 * с языком и временем, уровень микрофона, переключение режима голоса без пересборки.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val container by lazy { (application as AstanaHubApp).container }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var ttsJob: Job? = null
    private var monitorJob: Job? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val router = container.voice
        val server = container.serverVoice

        // Адрес сервера
        binding.serverUrl.setText(container.backend.url.value)
        binding.serverStatus.text = "Сейчас: ${container.backend.url.value.ifEmpty { "не задан" }}"
        binding.serverCheck.setOnClickListener { checkServer() }
        binding.serverSave.setOnClickListener {
            val url = SwitchableBackendApi.normalizeUserUrl(binding.serverUrl.text?.toString().orEmpty())
            container.backendSettings.save(url)
            container.backend.switchTo(url)
            binding.serverUrl.setText(url)
            binding.serverStatus.text = "Сохранено: ${url.ifEmpty { "не задан" }}"
            log("Адрес сервера: ${url.ifEmpty { "не задан" }}")
            lifecycleScope.launch { container.places.refresh() }
            checkServer()
        }
        binding.serverReset.setOnClickListener {
            container.backendSettings.reset()
            val url = container.backendSettings.current()
            container.backend.switchTo(url)
            binding.serverUrl.setText(container.backend.url.value)
            log("Адрес сервера сброшен: ${container.backend.url.value.ifEmpty { "не задан" }}")
            checkServer()
        }

        // Голос: ASR и TTS по языкам
        val settings = container.voiceSettings
        bindSource(binding.asrGroup, R.id.asrGroupBuiltin, R.id.asrGroupServer, settings.config.value.asr) {
            settings.setAsr(it); log("ASR: ${it.code}")
        }
        bindSource(binding.ttsRuGroup, R.id.ttsRuGroupBuiltin, R.id.ttsRuGroupServer, settings.config.value.ttsFor(Lang.RU)) {
            settings.setTts(Lang.RU, it); log("TTS ru: ${it.code}")
        }
        bindSource(binding.ttsKkGroup, R.id.ttsKkGroupBuiltin, R.id.ttsKkGroupServer, settings.config.value.ttsFor(Lang.KK)) {
            settings.setTts(Lang.KK, it); log("TTS kk: ${it.code}")
        }
        bindSource(binding.ttsEnGroup, R.id.ttsEnGroupBuiltin, R.id.ttsEnGroupServer, settings.config.value.ttsFor(Lang.EN)) {
            settings.setTts(Lang.EN, it); log("TTS en: ${it.code}")
        }

        // Подсказка языка для серверного STT
        val hintIds = mapOf<Lang?, Int>(null to R.id.hintAuto, Lang.RU to R.id.hintRu, Lang.KK to R.id.hintKk, Lang.EN to R.id.hintEn)
        binding.hintGroup.check(hintIds.getValue(server.sttLangHint))
        binding.hintGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            server.sttLangHint = hintIds.entries.first { it.value == id }.key
            log("STT язык: ${server.sttLangHint?.code ?: "авто"}")
        }

        // TTS
        binding.builtinRu.setOnClickListener { speakBuiltin(Lang.RU, R.string.diag_test_ru) }
        binding.builtinKk.setOnClickListener { speakBuiltin(Lang.KK, R.string.diag_test_kk) }
        binding.builtinEn.setOnClickListener { speakBuiltin(Lang.EN, R.string.diag_test_en) }
        binding.serverRu.setOnClickListener { speakServer(Lang.RU, R.string.diag_test_ru) }
        binding.serverKk.setOnClickListener { speakServer(Lang.KK, R.string.diag_test_kk) }
        binding.serverEn.setOnClickListener { speakServer(Lang.EN, R.string.diag_test_en) }
        binding.stopTts.setOnClickListener {
            ttsJob?.cancel()
            router.stopSpeaking()
            log("TTS остановлен")
        }

        // Микрофон
        binding.recordButton.setOnClickListener {
            if (!server.hasMicPermission()) { log("Нет разрешения RECORD_AUDIO — дайте его на главном экране"); return@setOnClickListener }
            stopMonitor()
            log("Серверный STT: говорите…")
            server.listenOnce()
        }
        binding.monitorSwitch.setOnCheckedChangeListener { _, on -> if (on) startMonitor() else stopMonitor() }
        binding.builtinMicSwitch.setOnCheckedChangeListener { _, on ->
            container.builtinMic.setBuiltinListening(on)
            log("Встроенный ASR: ${if (on) "включён" else "выключен (mute)"}")
        }

        // Android TTS (запасной голос)
        binding.androidRu.setOnClickListener { speakAndroid(Lang.RU, R.string.diag_test_ru) }
        binding.androidKk.setOnClickListener { speakAndroid(Lang.KK, R.string.diag_test_kk) }
        binding.androidEn.setOnClickListener { speakAndroid(Lang.EN, R.string.diag_test_en) }
        binding.androidInfo.setOnClickListener { logAndroidTtsInfo() }

        // Масштаб интерфейса
        val percent = UiScale.percent(this)
        binding.scaleSlider.value = percent.toFloat()
        binding.scaleLabel.text = getString(R.string.diag_scale, percent)
        binding.scaleSlider.addOnChangeListener { _, value, fromUser ->
            binding.scaleLabel.text = getString(R.string.diag_scale, value.toInt())
            if (fromUser) {
                UiScale.setPercent(this, value.toInt())
                log("Масштаб интерфейса: ${value.toInt()}%")
                recreate()
            }
        }
        binding.screenInfo.text = screenInfo()

        // Робот: RobotApi и карта
        binding.dumpPlaces.setOnClickListener {
            lifecycleScope.launch {
                val list = container.robot.getPlaces()
                log("getPlaceList: ${list.size} точек")
                list.forEach { log("  «${it.name}» x=${"%.2f".format(it.x)} y=${"%.2f".format(it.y)} status=${it.status}") }
            }
        }
        binding.dumpPose.setOnClickListener {
            lifecycleScope.launch {
                val pose = container.robot.getPosition()
                log(if (pose == null) "getPosition: нет (не локализован / RobotApi не подключён)"
                    else "getPosition: x=${"%.2f".format(pose.x)} y=${"%.2f".format(pose.y)} theta=${"%.2f".format(pose.theta)} рад")
            }
        }
        binding.refreshPlaces.setOnClickListener {
            lifecycleScope.launch {
                val ok = container.places.refresh()
                val all = container.places.places()
                log("/v1/places: ${if (ok) "OK" else "ошибка (кеш)"}; мест для поиска: ${all.size} — ${all.joinToString { it.mapName }}")
            }
        }

        binding.clearButton.setOnClickListener { binding.logText.text = "" }
        binding.closeButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    router.builtinAsr.transcripts.collect { t ->
                        if (t.isFinal) log("Встроенный ASR: «${t.text}»")
                    }
                }
                launch {
                    server.sttEvents.collect { e ->
                        log("Микрофон: ${container.micRecorder.lastConfig}")
                        if (e.error != null) {
                            log("Серверный STT ошибка: ${e.error.kind} ${e.error.message.orEmpty()} (${e.latencyMillis} мс)")
                        } else {
                            log(
                                "Серверный STT [${e.lang?.code ?: "?"}, conf=${e.confidence ?: "-"}]: «${e.text}» " +
                                    "аудио ${e.audioMillis} мс, ответ ${e.latencyMillis} мс",
                            )
                        }
                    }
                }
                launch {
                    container.robot.connected.collect { c ->
                        binding.robotStatus.text = "RobotApi (${BuildConfig.PLATFORM}): " + if (c) "подключён" else "не подключён"
                    }
                }
                launch { server.micLevel.collect { binding.levelBar.setProgressCompat((it * 300).toInt().coerceAtMost(100), false) } }
            }
        }
        log("Голос: ${container.voiceSettings.config.value.describe()}, flavor: ${getString(R.string.flavor_name)}, backend: ${container.backend.url.value.ifEmpty { "не задан" }}")
    }

    override fun onStart() {
        super.onStart()
        container.voice.setDiagnosticsOpen(true)
        // Встроенный ASR по умолчанию включён, чтобы видеть onASRResult.
        binding.builtinMicSwitch.isChecked = true
        container.builtinMic.setBuiltinListening(true)
    }

    override fun onStop() {
        stopMonitor()
        ttsJob?.cancel()
        container.voice.stopSpeaking()
        container.androidTts.stopSpeaking()
        container.serverVoice.stopListening()
        container.voice.setDiagnosticsOpen(false) // режим заново применит нужное состояние микрофона
        super.onStop()
    }

    /** GET /v1/health по адресу из поля (ещё не сохранённому). */
    private fun checkServer() {
        val url = SwitchableBackendApi.normalizeUserUrl(binding.serverUrl.text?.toString().orEmpty())
        if (url.isEmpty()) {
            binding.serverStatus.text = "Адрес пустой или некорректный"
            return
        }
        binding.serverStatus.text = "Проверяю $url …"
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val r = RetrofitBackendApi(url, BuildConfig.ROBOT_TOKEN).health()
            val ms = SystemClock.elapsedRealtime() - started
            val text = when (r) {
                is ApiResult.Success -> "OK: ${r.value} — $ms мс ($url)"
                is ApiResult.Failure -> "Ошибка: ${r.error.kind} ${r.error.httpStatus ?: ""} ${r.error.message.orEmpty()} — $ms мс"
            }
            binding.serverStatus.text = text
            log("health $url → $text")
        }
    }

    private fun bindSource(
        group: MaterialButtonToggleGroup,
        builtinId: Int,
        serverId: Int,
        current: VoiceSource,
        onChange: (VoiceSource) -> Unit,
    ) {
        group.check(if (current == VoiceSource.BUILTIN) builtinId else serverId)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) onChange(if (id == builtinId) VoiceSource.BUILTIN else VoiceSource.SERVER)
        }
    }

    private fun speakBuiltin(lang: Lang, @StringRes text: Int) {
        ttsJob?.cancel()
        ttsJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            log("Встроенный TTS ${lang.code}…")
            val ok = container.voice.builtinTts.speak(listOf(getString(text)), lang)
            log("Встроенный TTS ${lang.code}: ${if (ok) "OK" else "прерван/ошибка"}, ${SystemClock.elapsedRealtime() - started} мс")
        }
    }

    private fun speakServer(lang: Lang, @StringRes text: Int) {
        ttsJob?.cancel()
        ttsJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            log("Серверный TTS ${lang.code}…")
            val result = container.serverVoice.speakDetailed(listOf(getString(text)), lang)
            val ms = SystemClock.elapsedRealtime() - started
            when (result) {
                ServerSpeakResult.Done -> log("Серверный TTS ${lang.code}: OK, $ms мс (с загрузкой)")
                ServerSpeakResult.Interrupted -> log("Серверный TTS ${lang.code}: прерван, $ms мс")
                is ServerSpeakResult.Unavailable ->
                    log("Серверный TTS ${lang.code}: ошибка ${result.error.kind} ${result.error.message.orEmpty()}, $ms мс")
            }
        }
    }

    private fun speakAndroid(lang: Lang, @StringRes text: Int) {
        ttsJob?.cancel()
        ttsJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            log("Android TTS ${lang.code}…")
            val ok = container.androidTts.speak(listOf(getString(text)), lang)
            log("Android TTS ${lang.code}: ${if (ok) "OK" else "ошибка/прерван"}, ${SystemClock.elapsedRealtime() - started} мс")
        }
    }

    private fun logAndroidTtsInfo() {
        lifecycleScope.launch {
            val tts = container.androidTts
            if (!tts.isReady()) { log("Android TTS: движок не инициализировался"); return@launch }
            log("Android TTS движки: ${tts.engines().joinToString("; ").ifEmpty { "нет" }}")
            tts.languageStatus().forEach { log("  ${it.locale.toLanguageTag()}: ${it.label}") }
        }
    }

    private fun screenInfo(): String {
        val sys = android.content.res.Resources.getSystem()
        val sm = sys.displayMetrics
        val sc = sys.configuration
        val real = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(it)
        }
        val app = resources.displayMetrics
        val ac = resources.configuration
        return buildString {
            appendLine("real px: ${real.widthPixels}×${real.heightPixels}, xdpi/ydpi ${"%.0f".format(real.xdpi)}/${"%.0f".format(real.ydpi)}")
            appendLine("system: densityDpi=${sm.densityDpi} density=${sm.density} ${sc.screenWidthDp}×${sc.screenHeightDp} dp, sw=${sc.smallestScreenWidthDp}dp, fontScale=${sc.fontScale}")
            appendLine("app:    densityDpi=${app.densityDpi} density=${app.density} ${ac.screenWidthDp}×${ac.screenHeightDp} dp, sw=${ac.smallestScreenWidthDp}dp, fontScale=${ac.fontScale}, масштаб ${UiScale.percent(this@DiagnosticsActivity)}%")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), build ${android.os.Build.DISPLAY}")
            append("app: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} ${getString(R.string.flavor_name)}, голос ${container.voiceSettings.config.value.describe()}")
        }
    }

    private fun startMonitor() {
        if (!container.serverVoice.hasMicPermission()) {
            log("Нет разрешения RECORD_AUDIO")
            binding.monitorSwitch.isChecked = false
            return
        }
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            val ok = container.micRecorder.monitorLevel { level ->
                runOnUiThread { binding.levelBar.setProgressCompat((level * 300).toInt().coerceAtMost(100), false) }
            }
            if (!ok) log("Микрофон недоступен (AudioRecord не открылся — занят AgentOS?)")
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        if (binding.monitorSwitch.isChecked) binding.monitorSwitch.isChecked = false
    }

    private fun log(line: String) {
        val text = "${timeFormat.format(Date())}  $line\n"
        Timber.i("DIAG %s", line)
        runOnUiThread {
            binding.logText.append(text)
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
