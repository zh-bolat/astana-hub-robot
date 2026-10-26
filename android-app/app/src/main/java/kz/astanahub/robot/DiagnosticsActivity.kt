package kz.astanahub.robot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kz.astanahub.robot.audio.MicRecorder
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.RetrofitBackendApi
import kz.astanahub.robot.backend.BackendUrlPolicy
import kz.astanahub.robot.chat.ChatMode
import kz.astanahub.robot.core.CrashReport
import kz.astanahub.robot.core.LogSafe
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.NoiseStats
import kz.astanahub.robot.audio.MicSensitivity
import kz.astanahub.robot.databinding.ActivityDiagnosticsBinding
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.NavJournal
import kz.astanahub.robot.ui.UiScale
import kz.astanahub.robot.voice.ServerSpeakResult
import kz.astanahub.robot.voice.VoiceSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import timber.log.Timber

/**
 * Скрытый экран диагностики голоса (удержание «Astana Hub» ≥ 3 с на главном экране + PIN).
 * Доступ проверяется при каждом открытии/возврате ([kz.astanahub.robot.security.DiagnosticsGate]);
 * разблокировка сбрасывается через 5 мин бездействия и при уходе с экрана.
 * Для спайков 2.3–2.5 на роботе: встроенный vs серверный TTS, лог встроенного ASR и серверного STT
 * с языком и временем, уровень микрофона, переключение режима голоса без пересборки.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val container by lazy { (application as AstanaHubApp).container }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var ttsJob: Job? = null
    private var monitorJob: Job? = null
    /** Поездка, запущенная кнопкой «Поехать» (отменяется «Стоп» и закрытием экрана — робот останавливается). */
    private var tripJob: Job? = null

    private val gate by lazy { container.diagnostics }
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (!gate.canEnter()) { finish(); return }
            idleHandler.postDelayed(this, IDLE_CHECK_MILLIS)
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!gate.canEnter()) {
            Timber.w("diagnostics: locked, closing")
            finish()
            return
        }
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val router = container.voice
        val server = container.serverVoice

        section("Адрес сервера") {
        // Адрес сервера
        binding.serverUrl.setText(container.backend.url.value)
        binding.serverStatus.text = "Сейчас: ${container.backend.url.value.ifEmpty { "не задан" }}"
        binding.serverCheck.setOnClickListener { checkServer() }
        binding.serverSave.setOnClickListener {
            when (val r = container.backendSettings.save(binding.serverUrl.text?.toString().orEmpty())) {
                is BackendUrlPolicy.Result.Invalid -> {
                    binding.serverStatus.text = "Не сохранено: ${r.message}"
                    log("Адрес сервера отклонён: ${r.message}")
                }
                is BackendUrlPolicy.Result.Valid -> {
                    container.backend.switchTo(r.url)
                    binding.serverUrl.setText(r.url)
                    binding.serverStatus.text = "Сохранено: ${r.url}"
                    log("Адрес сервера: ${r.url}")
                    lifecycleScope.launch { container.places.refresh() }
                    checkServer()
                }
            }
        }
        binding.serverReset.setOnClickListener {
            container.backendSettings.reset()
            val url = container.backendSettings.current()
            container.backend.switchTo(url)
            binding.serverUrl.setText(container.backend.url.value)
            log("Адрес сервера сброшен: ${container.backend.url.value.ifEmpty { "не задан" }}")
            checkServer()
        }
        }

        section("Голос") {
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

        // Только ElevenLabs: встроенный голос при сбое /v1/tts — лишь если включить явно.
        binding.builtinFallbackSwitch.isChecked = settings.config.value.builtinFallback
        binding.builtinFallbackSwitch.setOnCheckedChangeListener { _, on ->
            settings.setBuiltinFallback(on)
            log("Сбой /v1/tts → ${if (on) "встроенный голос" else "текст без звука + фраза из кеша"}")
        }
        binding.ttsStreamingSwitch.isChecked = settings.config.value.ttsStreaming
        binding.ttsStreamingSwitch.setOnCheckedChangeListener { _, on ->
            settings.setTtsStreaming(on)
            log("Потоковый /v1/tts: ${if (on) "вкл" else "выкл"}")
        }
        val vad = settings.config.value.vadEndSilenceMillis
        binding.vadSlider.value = (vad / 100 * 100).toFloat().coerceIn(binding.vadSlider.valueFrom, binding.vadSlider.valueTo)
        binding.vadLabel.text = getString(R.string.diag_vad, vad)
        binding.vadSlider.addOnChangeListener { _, value, fromUser ->
            binding.vadLabel.text = getString(R.string.diag_vad, value.toInt())
            if (fromUser) settings.setVadEndSilence(value.toInt())
        }
        // Чувствительность микрофона: порог громкости и минимальная длительность речи до старта live STT.
        val micButtons = mapOf(
            MicSensitivity.LOW to binding.micLow,
            MicSensitivity.NORMAL to binding.micNormal,
            MicSensitivity.HIGH to binding.micHigh,
        )
        fun showMic() {
            val m = settings.config.value.micSensitivity
            binding.micLabel.text = getString(R.string.diag_mic_sensitivity, m.code, m.minSpeechMillis, m.minSpeechRms.toInt()) +
                "\n" + NoiseStats.summary()
            micButtons.forEach { (level, button) -> button.isEnabled = level != m }
        }
        micButtons.forEach { (level, button) ->
            button.setOnClickListener {
                settings.setMicSensitivity(level)
                log("Чувствительность микрофона: ${level.code} (речь ≥ ${level.minSpeechMillis} мс)")
                showMic()
            }
        }
        showMic()
        binding.voiceCacheRefresh.setOnClickListener {
            container.phraseCacheWarmer.warmUp(force = true)
            log("Кеш голоса: синтез заново")
        }
        }

        section("Подсказка языка STT") {
        // Подсказка языка для серверного STT
        val hintIds = mapOf<Lang?, Int>(null to R.id.hintAuto, Lang.RU to R.id.hintRu, Lang.KK to R.id.hintKk, Lang.EN to R.id.hintEn)
        binding.hintGroup.check(hintIds.getValue(server.sttLangHint))
        binding.hintGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            server.sttLangHint = hintIds.entries.first { it.value == id }.key
            log("STT язык: ${server.sttLangHint?.code ?: "авто"}")
        }
        }

        section("TTS") {
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
        }

        section("Микрофон") {
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
        }

        section("Android TTS") {
        // Android TTS (запасной голос)
        binding.androidRu.setOnClickListener { speakAndroid(Lang.RU, R.string.diag_test_ru) }
        binding.androidKk.setOnClickListener { speakAndroid(Lang.KK, R.string.diag_test_kk) }
        binding.androidEn.setOnClickListener { speakAndroid(Lang.EN, R.string.diag_test_en) }
        binding.androidInfo.setOnClickListener { logAndroidTtsInfo() }
        }

        section("Масштаб") {
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
        }

        section("Робот") {
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
        }

        section("Костюм") { bindCostumes() }

        section("Голова") { HeadDiagPanel(binding, container, lifecycleScope, lifecycle).bind() }

        section("Точки карты и журнал навигации") {
        binding.loadMapPoints.setOnClickListener { loadMapPoints() }
        binding.navStop.setOnClickListener { stopTrip() }
        binding.navJournalClear.setOnClickListener { NavJournal.clear() }
        loadMapPoints()
        }

        section("Режим разговора") {
        // Режим разговора: agent (/v1/chat) или legacy; главный экран подхватывает при возврате.
        val chat = container.chatSettings
        binding.chatModeGroup.check(
            when (chat.mode.value) {
                ChatMode.AGENT -> R.id.chatModeAgent
                ChatMode.LEGACY -> R.id.chatModeLegacy
                ChatMode.LIVE -> R.id.chatModeLive
            },
        )
        binding.chatModeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.chatModeAgent -> ChatMode.AGENT
                R.id.chatModeLive -> ChatMode.LIVE
                else -> ChatMode.LEGACY
            }
            chat.set(mode)
            log("Разговор: ${mode.code}" + if (mode == ChatMode.LIVE) " (WS /v1/live; сбой → agent, повтор через 30 с)" else "")
            if (mode == ChatMode.LIVE) log("Live-микрофон: ${container.liveMicrophone.lastConfig}")
        }
        }

        section("Посетитель (эмулятор)") {
        // Эмулятор: подошёл / ушёл человек (на роботе — PersonApi, кнопок нет).
        val simulator = container.presenceSimulator
        binding.presenceLabel.visibility = if (simulator == null) View.GONE else View.VISIBLE
        binding.presenceRow.visibility = if (simulator == null) View.GONE else View.VISIBLE
        binding.personArrive.setOnClickListener {
            simulator?.arrive()
            log("Посетитель: подошёл (уйдёт сам через 90 с)")
        }
        binding.personLeave.setOnClickListener {
            simulator?.leave()
            log("Посетитель: ушёл (заснёт через ~15 с)")
        }
        }

        section("Места и кнопки") {
        binding.refreshPlaces.setOnClickListener {
            lifecycleScope.launch {
                container.places.invalidateMap() // перечитать и точки карты, не ждать TTL
                val ok = container.places.refresh()
                val all = container.places.places()
                log("/v1/places: ${if (ok) "OK" else "ошибка (кеш)"}; мест для поиска: ${all.size} — ${all.joinToString { it.mapName }}")
            }
        }

        binding.clearButton.setOnClickListener { binding.logText.text = "" }
        binding.closeButton.setOnClickListener { finish() }
        // «Назад» — на главный экран.
        onBackPressedDispatcher.addCallback(this) { finish() }
        }

        lifecycleScope.launch(sectionHandler("Статусы")) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    router.builtinAsr.transcripts.collect { t ->
                        if (t.isFinal) log("Встроенный ASR: «${t.text}»", pii = t.text)
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
                                pii = e.text,
                            )
                        }
                    }
                }
                launch {
                    container.robot.connected.collect { c ->
                        binding.robotStatus.text = "RobotApi (${BuildConfig.PLATFORM}): " + if (c) "подключён" else "не подключён"
                    }
                }
                launch {
                    container.phraseCacheWarmer.status.collect { st ->
                        binding.voiceCacheStatus.text = "Кеш голоса: ${st.cached}/${st.total}" +
                            (if (st.running) " — синтез…" else "") + (st.lastError?.let { " (ошибка: $it)" } ?: "")
                    }
                }
                launch { server.micLevel.collect { binding.levelBar.setProgressCompat((it * 300).toInt().coerceAtMost(100), false) } }
                launch {
                    NavJournal.entries.collect { entries ->
                        binding.navJournalText.text =
                            if (entries.isEmpty()) "— пока пусто: здесь шаги навигации и решения по guide_to —"
                            else entries.joinToString("\n") { it.format() }
                        binding.navJournalScroll.post { binding.navJournalScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
        (application as AstanaHubApp).crashGuard.lastCrash()?.let {
            log("Последнее падение приложения (${CrashReport.DIR}/${CrashReport.LAST}):\n" + CrashReport.head(it, 25))
        }
        log("Голос: ${container.voiceSettings.config.value.describe()}, flavor: ${getString(R.string.flavor_name)}, backend: ${container.backend.url.value.ifEmpty { "не задан" }}")
    }

    override fun onResume() {
        super.onResume()
        if (!gate.canEnter()) { finish(); return }
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.postDelayed(idleCheck, IDLE_CHECK_MILLIS)
    }

    override fun onPause() {
        idleHandler.removeCallbacks(idleCheck)
        super.onPause()
    }

    /** Любое касание продлевает разблокировку (сброс — через 5 мин бездействия). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        gate.session.touch()
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        container.voice.setDiagnosticsOpen(true)
        // Встроенный ASR по умолчанию включён, чтобы видеть onASRResult.
        binding.builtinMicSwitch.isChecked = true
        container.builtinMic.setBuiltinListening(true)
    }

    override fun onStop() {
        // Ушли с экрана (не пересоздание из-за масштаба) — снова нужен PIN.
        if (!isChangingConfigurations) gate.session.lock()
        if (!::binding.isInitialized) { super.onStop(); return }
        stopMonitor()
        ttsJob?.cancel()
        container.voice.stopSpeaking()
        if (container.androidTtsCreated) container.androidTts.stopSpeaking()
        container.serverVoice.stopListening()
        container.voice.setDiagnosticsOpen(false) // режим заново применит нужное состояние микрофона
        super.onStop()
    }

    /** Точки карты робота (getPlaceList) — строкой на каждую, с кнопкой «Поехать». */
    private fun loadMapPoints() {
        lifecycleScope.launch(sectionHandler("Точки карты")) {
            val points = container.robot.getPlaces()
            NavJournal.log("диагностика: getPlaceList — ${points.size} точек" + if (points.isEmpty()) " (RobotApi не подключён или карта пуста)" else "")
            val list = binding.mapPointsList
            list.removeAllViews()
            if (points.isEmpty()) {
                list.addView(TextView(this@DiagnosticsActivity).apply {
                    text = "Точек нет: RobotApi не подключён или на роботе нет карты"
                    textSize = 18f
                })
                return@launch
            }
            val pad = (6 * resources.displayMetrics.density).toInt()
            points.forEach { point -> list.addView(mapPointRow(point, pad)) }
        }
    }

    private fun mapPointRow(point: MapPlace, pad: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, pad, 0, pad)
        addView(
            TextView(this@DiagnosticsActivity).apply {
                val status = when (point.status) { 0 -> "доступна"; 1 -> "запретная зона"; 2 -> "вне карты"; else -> "status=${point.status}" }
                text = "«${point.name}»  x=${"%.2f".format(point.x)} y=${"%.2f".format(point.y)}  $status"
                textSize = 18f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            MaterialButton(this@DiagnosticsActivity).apply {
                text = getString(R.string.diag_nav_go)
                textSize = 20f
                minHeight = (64 * resources.displayMetrics.density).toInt()
                setOnClickListener { goTo(point) }
            },
        )
    }

    /** «Поехать»: startNavigation к точке напрямую через RobotGateway (все шаги — в журнале навигации). */
    private fun goTo(point: MapPlace) {
        val previous = tripJob
        tripJob = lifecycleScope.launch(sectionHandler("Поехать")) {
            if (previous?.isActive == true) {
                previous.cancelAndJoin()
                container.robot.stopNavigationAndWait()
            }
            NavJournal.log("диагностика: «Поехать» → «${point.name}»")
            binding.navTripStatus.text = "Еду к «${point.name}»…"
            log("Навигация → «${point.name}»")
            // Слежение головой занимает шасси — на время поездки выключаем, как в обычной поездке.
            container.head?.setNavigating(true)
            val result = try {
                container.robot.navigateTo(point.name) { event -> NavJournal.log("диагностика: событие $event") }
            } finally {
                container.head?.setNavigating(false)
            }
            binding.navTripStatus.text = "«${point.name}»: $result"
            log("Навигация «${point.name}»: $result")
        }
    }

    private fun stopTrip() {
        NavJournal.log("диагностика: «Стоп»")
        tripJob?.cancel()
        container.robot.stopNavigation()
        binding.navTripStatus.text = "Стоп отправлен"
        log("Навигация: стоп")
    }

    /** GET /v1/health (без токена) по адресу из поля — только если адрес разрешён политикой. */
    private fun checkServer() {
        val url = when (val r = container.backendUrlPolicy.validate(binding.serverUrl.text?.toString().orEmpty())) {
            is BackendUrlPolicy.Result.Invalid -> {
                binding.serverStatus.text = "Не проверено: ${r.message}"
                return
            }
            is BackendUrlPolicy.Result.Valid -> r.url
        }
        binding.serverStatus.text = "Проверяю $url …"
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            // Токен не передаём вовсе: /health без авторизации, а адрес мог ещё не быть сохранён.
            val r = RetrofitBackendApi(url, token = "", client = container.httpClient).health()
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
            // Здесь оператор, а не посетитель — можно спросить разрешение ещё раз.
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            log("Нет разрешения RECORD_AUDIO — запрошено")
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

    /** [pii] — текст посетителя в строке: на экран целиком, в logcat — только длина и хеш. */
    /**
     * Блок настройки экрана: ошибка одного виджета не роняет приложение (на роботе падение = выброс из киоска),
     * а попадает в лог диагностики, чтобы её было видно.
     */
    /** Костюм робота-компаньона (только вид): кнопки, выбранный — с точкой. */
    private fun bindCostumes() {
        val row = binding.costumeRow
        row.removeAllViews()
        val current = container.costume.costume.value
        kz.astanahub.robot.ui.screen.Costume.entries.forEach { c ->
            val b = MaterialButton(this).apply {
                text = (if (c == current) "● " else "") + c.label
                textSize = 20f
                isAllCaps = false
                setOnClickListener {
                    container.costume.set(c)
                    log("Костюм: ${c.label}")
                    bindCostumes()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private inline fun section(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Timber.e(e, "diagnostics: section %s failed", name)
            log("ОШИБКА в блоке «$name»: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun sectionHandler(name: String) = CoroutineExceptionHandler { _, e ->
        Timber.e(e, "diagnostics: coroutine %s failed", name)
        log("ОШИБКА в блоке «$name»: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun log(line: String, pii: String? = null) {
        val text = "${timeFormat.format(Date())}  $line\n"
        if (pii == null) Timber.i("DIAG %s", line) else Timber.i("DIAG (скрыто) %s", LogSafe.redact(pii))
        runOnUiThread {
            binding.logText.append(text)
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val IDLE_CHECK_MILLIS = 15_000L
        const val REQUEST_MIC = 1
    }
}
