package kz.astanahub.robot.backend

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.core.Lang
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * BackendApi с адресом, который меняется на лету (экран диагностики): ноутбук с сервером часто меняет IP.
 * Пустой адрес → [fallback] (эмулятор — заглушка, робот — «нет связи»).
 */
class SwitchableBackendApi(
    initialUrl: String,
    private val factory: (String) -> BackendApi,
    private val fallback: () -> BackendApi,
) : BackendApi {

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url

    @Volatile private var delegate: BackendApi = fallback()

    init {
        switchTo(initialUrl)
    }

    /** Применяется сразу: следующие запросы идут на новый адрес. */
    fun switchTo(rawUrl: String) {
        val url = normalizeUserUrl(rawUrl)
        delegate = if (url.isEmpty()) fallback() else factory(url)
        _url.value = url
        Timber.i("backend url → %s", url.ifEmpty { "(не задан)" })
    }

    override suspend fun health() = delegate.health()
    override suspend fun ask(question: String, context: AskContext) = delegate.ask(question, context)
    override suspend fun look(jpeg: ByteArray, question: String?, context: AskContext) = delegate.look(jpeg, question, context)
    override suspend fun places(etag: String?) = delegate.places(etag)
    override suspend fun quickQuestions(lang: Lang) = delegate.quickQuestions(lang)
    override suspend fun stt(wav: ByteArray, lang: Lang?, sessionId: String) = delegate.stt(wav, lang, sessionId)
    override suspend fun tts(text: String, lang: Lang) = delegate.tts(text, lang)

    companion object {
        /**
         * «10.54.180.166:8000» → «http://10.54.180.166:8000/v1»; пустая строка — пусто.
         * Некорректный адрес → пусто (как «не задан»).
         */
        fun normalizeUserUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            val parsed = u.toHttpUrlOrNull() ?: return ""
            val path = parsed.encodedPath.trimEnd('/')
            val withApi = if (path.isEmpty()) parsed.newBuilder().encodedPath("/v1").build() else parsed
            return withApi.toString().trimEnd('/')
        }
    }
}

/** Сохранённый адрес сервера; по умолчанию — BuildConfig.BACKEND_URL. */
class BackendSettings(context: Context, private val default: String) {
    private val prefs = context.applicationContext.getSharedPreferences("backend", Context.MODE_PRIVATE)

    val defaultUrl: String get() = default

    fun current(): String = prefs.getString(KEY_URL, null) ?: default

    fun save(url: String) = prefs.edit().putString(KEY_URL, url).apply()

    fun reset() = prefs.edit().remove(KEY_URL).apply()

    private companion object {
        const val KEY_URL = "url"
    }
}
