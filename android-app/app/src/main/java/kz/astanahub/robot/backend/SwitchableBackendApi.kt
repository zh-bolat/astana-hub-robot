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

    /**
     * Применяется сразу: следующие запросы идут на новый адрес.
     * Сюда передаётся только адрес из сборки или прошедший [BackendUrlPolicy] (см. [BackendSettings]).
     */
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
         * «robot-api.example.kz» → «https://robot-api.example.kz/v1»; пустая строка — пусто.
         * Схема по умолчанию https; при [httpForPrivateIp] (debug) «10.54.180.166:8000» → «http://10.54.180.166:8000/v1».
         * Некорректный адрес → пусто (как «не задан»). Разрешён ли адрес — решает [BackendUrlPolicy].
         */
        fun normalizeUserUrl(raw: String, httpForPrivateIp: Boolean = false): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.contains("://")) {
                val host = u.substringBefore('/').substringBefore(':')
                val scheme = if (httpForPrivateIp && BackendUrlPolicy.isPrivateIpv4(host)) "http" else "https"
                u = "$scheme://$u"
            }
            val parsed = u.toHttpUrlOrNull() ?: return ""
            val path = parsed.encodedPath.trimEnd('/')
            val withApi = if (path.isEmpty()) parsed.newBuilder().encodedPath("/v1").build() else parsed
            return withApi.toString().trimEnd('/')
        }
    }
}

/**
 * Сохранённый адрес сервера; по умолчанию — BuildConfig.BACKEND_URL (он из сборки, ему доверяем).
 * Сохраняется только адрес, разрешённый [policy]; сохранённый раньше, но больше не разрешённый — игнорируется.
 */
class BackendSettings(context: Context, private val default: String, private val policy: BackendUrlPolicy) {
    private val prefs = context.applicationContext.getSharedPreferences("backend", Context.MODE_PRIVATE)

    val defaultUrl: String get() = default

    fun current(): String {
        val saved = prefs.getString(KEY_URL, null) ?: return default
        if (!policy.isAllowed(saved)) {
            Timber.w("saved backend url rejected by policy, using default")
            reset()
        }
        return resolve(saved, default, policy)
    }

    /** @return [BackendUrlPolicy.Result.Valid] с нормализованным адресом (сохранён) или причина отказа. */
    fun save(raw: String): BackendUrlPolicy.Result {
        val result = policy.validate(raw)
        if (result is BackendUrlPolicy.Result.Valid) prefs.edit().putString(KEY_URL, result.url).apply()
        return result
    }

    fun reset() = prefs.edit().remove(KEY_URL).apply()

    companion object {
        private const val KEY_URL = "url"

        /** Сохранённый адрес, если он ещё проходит проверку; иначе адрес по умолчанию из сборки. */
        fun resolve(saved: String?, default: String, policy: BackendUrlPolicy): String =
            (saved?.let(policy::validate) as? BackendUrlPolicy.Result.Valid)?.url ?: default
    }
}
