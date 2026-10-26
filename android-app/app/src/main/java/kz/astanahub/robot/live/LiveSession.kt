package kz.astanahub.robot.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.astanahub.robot.audio.AudioLevels
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import timber.log.Timber

/** Создаёт [LiveSession] на разговор; null — живой режим недоступен (нет адреса сервера и т. п.). */
fun interface LiveSessionFactory {
    fun create(scope: CoroutineScope, sessionId: String, lang: Lang): LiveSession?
}

/** Что живой разговор сообщает экрану. */
sealed interface LiveSignal {
    /** Сервер готов (`ready`), `hello` ушёл; [aec] — что сказали серверу. */
    data class Ready(val aec: Boolean) : LiveSignal
    data object Listening : LiveSignal
    /** Человек говорит (на собственный голос робота без AEC не приходит). */
    data object UserSpeaking : LiveSignal
    data class UserPartial(val text: String) : LiveSignal
    data class UserFinal(val text: String, val lang: Lang?) : LiveSignal
    data object Ignored : LiveSignal
    data object Thinking : LiveSignal
    data class TurnStart(val turnId: String?) : LiveSignal
    data class Emotion(val name: String) : LiveSignal
    data class Phrase(val text: String) : LiveSignal
    /** Звук хода пошёл из динамика. */
    data class AudioStarted(val turnId: String?) : LiveSignal
    data class Action(val type: String, val placeId: String?, val turnId: String?) : LiveSignal
    data class TurnEnd(val turnId: String?, val lang: Lang?, val found: Boolean?) : LiveSignal
    /** Звук хода оборван: посетитель перебил ([local] = false — решил сервер) или «Стоп» на роботе. */
    data class Interrupted(val turnId: String?, val local: Boolean) : LiveSignal
    /** Ход доигран до конца; `playback_done` уже отправлен. */
    data class PlaybackDone(val turnId: String?) : LiveSignal
    /** Сессия умерла (сеть, `error`, сервер без /v1/live, микрофон): переходить на запасной режим. */
    data class Failed(val error: BackendError) : LiveSignal
}

/** Параметры сессии; значения по умолчанию — из контракта. */
data class LiveConfig(
    /** Кадр микрофона: 40 мс = 1280 байт PCM16 16 кГц (контракт: 20–100 мс). */
    val frameMillis: Int = 40,
    val micSampleRate: Int = 16_000,
    /** Нет `ready` за столько после подключения — сервер не тот / завис: запасной режим. */
    val readyTimeoutMillis: Long = 5_000,
    val jitterMillis: Long = StreamPlayer.DEFAULT_JITTER_MS,
) {
    val frameBytes: Int get() = micSampleRate / 1000 * frameMillis * 2
}

/**
 * Один живой разговор `WS /v1/live` (docs/api-contract.md): микрофон непрерывно уходит на сервер, события и звук
 * ответа приходят обратно, звук играет [StreamPlayer] сразу по мере прихода.
 *
 * - Перебивание: `interrupted` с сервера или [interrupt] → мгновенно сброс звука; звук и фразы оборванного хода,
 *   которые ещё в пути, отбрасываются до следующего `turn_start`.
 * - Эхо: без AEC микрофон всё равно уходит на сервер (он сам отличает эхо), но пока звучит ответ,
 *   `user_speaking` / `user_partial` экрану не показываются — это собственный голос робота.
 * - Своя озвучка робота мимо сервера ([setLocalSpeech]: фразы навигации) — вместо микрофона уходит тишина,
 *   чтобы сервер не принял её за посетителя.
 *
 * Все события и звук обрабатываются на однопоточном [confined]; микрофон читается на [io].
 */
class LiveSession(
    parent: CoroutineScope,
    private val connect: () -> LiveConnection?,
    private val microphone: LiveMicrophone,
    sink: AudioSink,
    private val clock: () -> Long,
    private val config: LiveConfig = LiveConfig(),
    private val confined: CoroutineDispatcher = DEFAULT_CONFINED,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Громкость микрофона и речи робота — для лица; null — не измеряем (тесты). */
    private val levels: AudioLevels? = null,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + confined)
    private val _signals = Channel<LiveSignal>(Channel.UNLIMITED)
    val signals: Flow<LiveSignal> = _signals.receiveAsFlow()

    private val player = StreamPlayer(
        scope = scope,
        sink = sink,
        clock = clock,
        jitterMillis = config.jitterMillis,
        onAudioStart = { emit(LiveSignal.AudioStarted(it)) },
        onDone = ::onPlaybackDone,
        meter = levels?.pcm,
    )

    private var connection: LiveConnection? = null
    private var micJob: Job? = null
    private var readyJob: Job? = null
    @Volatile private var ready = false
    private var greetPending = false
    @Volatile private var finished = false

    /** Эхоподавление на микрофоне реально включено (известно после [start]). */
    @Volatile var aec: Boolean = false
        private set

    /** Своя озвучка робота мимо сервера: вместо микрофона — тишина. */
    @Volatile private var localSpeech = false

    /** Ход, звук которого сейчас идёт / придёт; null — между ходами. */
    private var currentTurn: String? = null
    /** Оборванный ход: его звук и фразы в пути отбрасываем до следующего `turn_start`. */
    private var dropping = false

    /** Сейчас звучит ответ робота. */
    val playing: Boolean get() = player.playing.value

    val active: Boolean get() = !finished

    /** Подключиться, открыть микрофон, `hello`; [greet] — после `ready` отправить `visitor_arrived`. */
    fun start(lang: Lang, placeId: String?, greet: Boolean) {
        scope.launch {
            val conn = try {
                connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "live connect")
                null
            }
            if (conn == null) return@launch fail(BackendError(ErrorKind.NOT_CONFIGURED, message = "live is not available"))
            connection = conn
            val mic = openMic() ?: return@launch fail(BackendError(ErrorKind.UNKNOWN, message = "microphone did not open"))
            aec = mic.aec
            greetPending = greet
            conn.sendText(LiveFrames.hello(lang, placeId, mic.aec))
            Timber.i("live: hello sent (lang=%s aec=%s, %s)", lang.code, mic.aec, mic.description)
            micJob = scope.launch(io) { streamMic(conn, mic) }
            readyJob = scope.launch {
                delay(config.readyTimeoutMillis)
                if (!ready) fail(BackendError(ErrorKind.TIMEOUT, message = "no ready from /v1/live"))
            }
            for (event in conn.events) {
                handle(event)
                if (finished) break
            }
        }
    }

    /** Робот сам заметил перебивание («Стоп»): тишина сразу, сервер — `interrupt`. */
    fun interrupt() {
        scope.launch {
            if (finished) return@launch
            val wasPlaying = player.playing.value || player.queuedBytes > 0 || currentTurn != null
            player.flush()
            if (currentTurn != null) dropping = true
            connection?.sendText(LiveFrames.interrupt())
            if (wasPlaying) emit(LiveSignal.Interrupted(currentTurn, local = true))
        }
    }

    fun sendState(placeId: String?, navigating: Boolean) {
        scope.launch { if (!finished) connection?.sendText(LiveFrames.state(placeId, navigating)) }
    }

    /**
     * Фразы «где X» от текущей позиции (`state.directions`, полный набор). Только после `ready`: до него сервер
     * сессию ещё не завёл. @return false — сессия закрыта или ещё не готова (кадр не ушёл).
     */
    fun sendDirections(directions: Map<String, Map<Lang, String>>): Boolean {
        if (finished || !ready) return false
        val frame = LiveFrames.directions(directions)
        scope.launch { if (!finished) connection?.sendText(frame) }
        return true
    }

    fun visitorArrived() {
        scope.launch {
            if (finished) return@launch
            if (ready) connection?.sendText(LiveFrames.visitorArrived()) else greetPending = true
        }
    }

    fun setLocalSpeech(active: Boolean) {
        localSpeech = active
    }

    /** Штатно закрыть (посетитель ушёл, сон, смена режима). [LiveSignal.Failed] не приходит. */
    fun close() {
        if (finished) return
        finished = true
        Timber.i("live: closed")
        shutdown()
    }

    private suspend fun openMic(): OpenedMic? = withContext(io) {
        // Обычное прослушивание могло ещё не отпустить микрофон — несколько попыток.
        repeat(MIC_OPEN_ATTEMPTS) { attempt ->
            microphone.open(config.micSampleRate)?.let { return@withContext it }
            if (attempt < MIC_OPEN_ATTEMPTS - 1) delay(MIC_RETRY_MS)
        }
        null
    }

    private suspend fun streamMic(conn: LiveConnection, mic: OpenedMic) {
        val frame = ByteArray(config.frameBytes)
        val silence = ByteArray(config.frameBytes)
        try {
            while (currentCoroutineContext().isActive) {
                if (!mic.read(frame)) {
                    scope.launch { fail(BackendError(ErrorKind.UNKNOWN, message = "microphone read failed")) }
                    return
                }
                levels?.reportMic(AudioLevels.rmsPcm16(frame, 0, frame.size))
                conn.sendAudio(if (localSpeech) silence else frame.copyOf())
            }
        } finally {
            mic.close()
        }
    }

    private fun handle(event: LiveEvent) {
        when (event) {
            is LiveEvent.Ready -> {
                ready = true
                readyJob?.cancel()
                player.configure(event.format ?: LiveAudioFormat.DEFAULT)
                if (greetPending) {
                    greetPending = false
                    connection?.sendText(LiveFrames.visitorArrived())
                }
                emit(LiveSignal.Ready(aec))
            }
            LiveEvent.Listening -> emit(LiveSignal.Listening)
            LiveEvent.UserSpeaking -> if (!echoGuard()) {
                levels?.reportVoice(true, sticky = true) // VAD сервера: лицо «слушает» до user_final
                emit(LiveSignal.UserSpeaking)
            }
            is LiveEvent.UserPartial -> if (!echoGuard()) {
                levels?.reportVoice(true, sticky = true)
                emit(LiveSignal.UserPartial(event.text))
            }
            is LiveEvent.UserFinal -> {
                levels?.reportVoice(false)
                emit(LiveSignal.UserFinal(event.text, event.lang))
            }
            LiveEvent.Ignored -> {
                levels?.reportVoice(false)
                emit(LiveSignal.Ignored)
            }
            LiveEvent.Thinking -> {
                levels?.reportVoice(false)
                emit(LiveSignal.Thinking)
            }
            is LiveEvent.TurnStart -> {
                currentTurn = event.turnId
                dropping = false
                player.beginTurn(event.turnId)
                emit(LiveSignal.TurnStart(event.turnId))
            }
            is LiveEvent.Emotion -> if (!dropping) emit(LiveSignal.Emotion(event.name))
            is LiveEvent.Phrase -> if (!dropping) emit(LiveSignal.Phrase(event.text))
            is LiveEvent.Audio -> if (!dropping) player.enqueue(event.pcm)
            is LiveEvent.Action -> {
                // Ход берём из события; без `turn_id` (старый сервер) — текущий (null после `playback_done`).
                val turn = event.turnId ?: currentTurn
                // Отбрасываем только действия оборванного хода; действие уже доигранного хода — в силе.
                if (dropping && turn == currentTurn) return
                emit(LiveSignal.Action(event.type, event.placeId, turn))
            }
            is LiveEvent.TurnEnd -> {
                if (dropping) return
                player.endTurn(event.turnId ?: currentTurn)
                emit(LiveSignal.TurnEnd(event.turnId ?: currentTurn, event.lang, event.found))
            }
            is LiveEvent.Interrupted -> {
                player.flush()
                val turn = event.turnId ?: currentTurn
                if (turn == currentTurn) dropping = true
                emit(LiveSignal.Interrupted(turn, local = false))
            }
            is LiveEvent.Error -> fail(event.error)
            is LiveEvent.Closed -> fail(event.error ?: BackendError(ErrorKind.NETWORK, message = "live closed by server"))
        }
    }

    /** Без AEC, пока звучит ответ, «человек говорит» — скорее всего собственный голос робота. */
    private fun echoGuard(): Boolean = !aec && player.playing.value

    private fun onPlaybackDone(turnId: String?) {
        if (turnId != null && turnId == currentTurn) currentTurn = null
        if (turnId != null) connection?.sendText(LiveFrames.playbackDone(turnId))
        emit(LiveSignal.PlaybackDone(turnId))
    }

    private fun fail(error: BackendError) {
        if (finished) return
        finished = true
        Timber.w("live failed: %s %s", error.kind, error.message.orEmpty())
        _signals.trySend(LiveSignal.Failed(error))
        shutdown()
    }

    private fun shutdown() {
        levels?.reportVoice(false)
        readyJob?.cancel()
        micJob?.cancel()
        connection?.close()
        scope.launch {
            player.release()
            _signals.close()
            scope.cancel()
        }
    }

    private fun emit(signal: LiveSignal) {
        if (!finished) _signals.trySend(signal)
    }

    companion object {
        private const val MIC_OPEN_ATTEMPTS = 4
        private const val MIC_RETRY_MS = 150L

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val DEFAULT_CONFINED: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
