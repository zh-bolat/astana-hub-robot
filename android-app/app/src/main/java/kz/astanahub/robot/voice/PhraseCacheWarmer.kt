package kz.astanahub.robot.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.PhraseSplitter
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.WayPhrases
import timber.log.Timber

/** Какие фразы робот озвучивает сам и держит в кеше голоса ElevenLabs. */
object CachedPhrases {
    /** Порядок — по важности: «нет связи» и «не могу говорить» нужнее всего без сети. */
    private val KEY_ORDER = listOf(PhraseKey.NO_CONNECTION, PhraseKey.CANT_SPEAK) +
        PhraseKey.entries.filter { it != PhraseKey.NO_CONNECTION && it != PhraseKey.CANT_SPEAK }

    fun texts(phrases: Phrases, lang: Lang): List<String> =
        KEY_ORDER.map { phrases.get(it, lang) } + WayPhrases.fixed(lang)

    /** Ровно те куски, которые пойдут в TTS (≤ 300 символов, как PhraseSplitter режет локальные фразы). */
    fun units(phrases: Phrases, lang: Lang): List<String> =
        texts(phrases, lang).flatMap { PhraseSplitter.split(it) }.distinct()
}

/**
 * Синтезирует [CachedPhrases] через `/v1/tts` фоном и кладёт в [PhraseAudioCache]: при старте (только недостающие)
 * и при смене адреса сервера (все заново — голос мог смениться). Первая ошибка связи останавливает проход —
 * без сети не копим таймауты; следующий проход — при восстановлении связи.
 */
class PhraseCacheWarmer(
    private val backend: BackendApi,
    private val cache: PhraseAudioCache,
    private val phrases: Phrases,
    private val scope: CoroutineScope,
    private val langs: List<Lang> = Lang.entries,
) {
    data class Status(val cached: Int = 0, val total: Int = 0, val running: Boolean = false, val lastError: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var job: Job? = null

    /** [force] — синтезировать заново и то, что уже есть (смена сервера / кнопка в диагностике). */
    @Synchronized
    fun warmUp(force: Boolean = false): Job {
        val previous = job
        if (!force && previous?.isActive == true) return previous
        previous?.cancel()
        return scope.launch { run(force) }.also { job = it }
    }

    suspend fun run(force: Boolean) {
        val units = langs.flatMap { lang -> CachedPhrases.units(phrases, lang).map { lang to it } }
        val keys = units.map { (lang, text) -> cache.key(text, lang) }.toSet()
        var cached = if (force) 0 else units.count { (lang, text) -> cache.has(text, lang) }
        _status.value = Status(cached = cached, total = units.size, running = true)
        var synthesized = 0
        for ((lang, text) in units) {
            if (!force && cache.has(text, lang)) continue
            when (val r = backend.tts(text, lang)) {
                is ApiResult.Success -> {
                    cache.put(text, lang, r.value)
                    synthesized++
                    cached = (cached + 1).coerceAtMost(units.size)
                    _status.update { it.copy(cached = cached) }
                }
                is ApiResult.Failure -> {
                    Timber.w("voice cache: tts failed (%s), stop for now", r.error.kind)
                    _status.update { it.copy(running = false, lastError = "${r.error.kind} ${r.error.message.orEmpty()}".trim()) }
                    if (r.error.kind != ErrorKind.BAD_REQUEST) return
                }
            }
        }
        // Полный проход удался: файлы старых текстов (другая версия приложения) больше не нужны.
        cache.retainOnly(keys)
        Timber.i("voice cache: %d phrases ready (%d synthesized)", units.count { (l, t) -> cache.has(t, l) }, synthesized)
        _status.value = Status(cached = units.count { (l, t) -> cache.has(t, l) }, total = units.size, running = false)
    }
}
