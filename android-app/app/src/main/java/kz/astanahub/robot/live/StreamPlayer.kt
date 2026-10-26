package kz.astanahub.robot.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kz.astanahub.robot.audio.PcmPlaybackMeter
import timber.log.Timber

/**
 * Выход звука для [StreamPlayer]: AudioTrack MODE_STREAM ([AndroidAudioSink]) или фейк в тестах.
 * Все вызовы — из одного потока (диспетчер сессии), [write] — неблокирующий.
 */
interface AudioSink {
    fun open(sampleRate: Int): Boolean

    /** Неблокирующая запись: сколько байт принято (0 — буфер полон), < 0 — ошибка. */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    fun play()

    /** Мгновенно: пауза + сброс буфера (то, что уже в буфере, не прозвучит). Счётчик сыгранного — с нуля. */
    fun pauseAndFlush()

    /** Сколько кадров (сэмплов mono) реально проиграно с [open] / последнего [pauseAndFlush]. */
    fun playedFrames(): Long

    fun release()
}

/**
 * Потоковое воспроизведение ответа `WS /v1/live`: звук пишется в [AudioSink] сразу по мере прихода, старт —
 * когда набралось [jitterMillis] (или ход кончился / буфер выхода полон). [flush] — мгновенная тишина: пауза,
 * сброс буфера выхода и очереди; после него в выход не уходит ни одного сэмпла старого звука.
 * Конец хода ([endTurn]) → [onDone], когда выход реально доиграл всё записанное (по [AudioSink.playedFrames]).
 *
 * Потоки: все методы и цикл записи — на одном однопоточном диспетчере [scope] (сессия это гарантирует),
 * поэтому [flush] не пересекается с записью.
 */
class StreamPlayer(
    private val scope: CoroutineScope,
    private val sink: AudioSink,
    private val clock: () -> Long,
    private val jitterMillis: Long = DEFAULT_JITTER_MS,
    /** Звук хода реально пошёл из динамика (первый раз за ход). */
    private val onAudioStart: (turnId: String?) -> Unit = {},
    /** Ход доигран до конца (не вызывается для сброшенных [flush] ходов). */
    private val onDone: (turnId: String?) -> Unit = {},
    /** Громкость того, что звучит, — для лица (рот по речи); null — не измеряем. */
    private val meter: PcmPlaybackMeter? = null,
) {
    private sealed interface Item {
        data class Begin(val turnId: String?) : Item
        class Audio(val pcm: ByteArray) : Item
        data class End(val turnId: String?) : Item
    }

    private val queue = ArrayDeque<Item>()
    private var offset = 0 // уже записано из головы очереди (Audio)
    private var format: LiveAudioFormat = LiveAudioFormat.DEFAULT
    private var opened = false
    private var started = false
    private var writtenFrames = 0L
    private var currentTurn: String? = null
    private var audioStartReported = false
    private var endDeadline: Long? = null

    private val _playing = MutableStateFlow(false)
    /** Из динамика сейчас идёт ответ (от старта до доигрывания / [flush]). */
    val playing: StateFlow<Boolean> = _playing

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    /** Байт уже в очереди (не записано в выход). */
    val queuedBytes: Int get() = queue.sumOf { if (it is Item.Audio) it.pcm.size else 0 } - offset

    fun configure(format: LiveAudioFormat) {
        if (opened && format == this.format) return
        if (opened) {
            sink.pauseAndFlush()
            sink.release()
            opened = false
        }
        this.format = format
        meter?.configure(format.sampleRate)
    }

    fun beginTurn(turnId: String?) = add(Item.Begin(turnId))

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        add(Item.Audio(pcm))
    }

    fun endTurn(turnId: String?) = add(Item.End(turnId))

    /** Перебили / «Стоп»: мгновенная тишина, очередь и буфер выхода выброшены, [onDone] не будет. */
    fun flush() {
        queue.clear()
        offset = 0
        endDeadline = null
        if (opened) sink.pauseAndFlush()
        writtenFrames = 0
        started = false
        currentTurn = null
        _playing.value = false
        meter?.reset()
    }

    fun release() {
        loop?.cancel()
        flush()
        if (opened) sink.release()
        opened = false
    }

    private fun add(item: Item) {
        queue.addLast(item)
        if (loop?.isActive != true) loop = scope.launch { run() }
        wake.trySend(Unit)
    }

    private suspend fun run() {
        while (true) {
            if (started) meter?.onPlayhead(sink.playedFrames(), true)
            val head = queue.firstOrNull()
            if (head == null) {
                wake.receive()
                continue
            }
            when (head) {
                is Item.Begin -> {
                    queue.removeFirst()
                    currentTurn = head.turnId
                    audioStartReported = false
                }
                is Item.Audio -> if (!writeHead(head)) delay(TICK_MS)
                is Item.End -> if (finishTurn(head)) queue.removeFirst() else delay(TICK_MS)
            }
        }
    }

    /** @return false — выход сейчас не принял данных (подождать). */
    private fun writeHead(item: Item.Audio): Boolean {
        if (!ensureOpen()) {
            queue.removeFirst()
            offset = 0
            return true
        }
        val len = minOf(item.pcm.size - offset, maxWriteBytes())
        val start = offset
        val firstFrame = writtenFrames
        val n = sink.write(item.pcm, offset, len)
        if (n < 0) {
            Timber.w("live audio write error %d — chunk dropped", n)
            queue.removeFirst()
            offset = 0
            return true
        }
        offset += n
        writtenFrames += n / 2
        if (n > 0) meter?.onWritten(item.pcm, start, n, firstFrame)
        if (offset >= item.pcm.size) {
            queue.removeFirst()
            offset = 0
        }
        val buffered = writtenFrames * 1000 / format.sampleRate
        if (!started && (buffered >= jitterMillis || n == 0 || nextIsEnd())) startPlayback()
        else if (started) reportAudioStart()
        return n > 0
    }

    private fun nextIsEnd(): Boolean = queue.firstOrNull() is Item.End

    /** @return true — ход доигран (или звука не было), можно снимать End с очереди. */
    private fun finishTurn(item: Item.End): Boolean {
        if (writtenFrames > 0 && !started) startPlayback()
        if (writtenFrames > 0) {
            val played = sink.playedFrames()
            val now = clock()
            val deadline = endDeadline ?: (now + (writtenFrames - played).coerceAtLeast(0) * 1000 / format.sampleRate + DONE_MARGIN_MS)
                .also { endDeadline = it }
            if (played < writtenFrames && now < deadline) return false
            if (played < writtenFrames) Timber.w("live playback: done by deadline (played %d of %d)", played, writtenFrames)
        }
        endDeadline = null
        // Следующий ход начинается с чистого выхода и снова копит джиттер-буфер.
        if (opened && started) sink.pauseAndFlush()
        writtenFrames = 0
        started = false
        _playing.value = false
        currentTurn = null
        meter?.reset()
        onDone(item.turnId)
        return true
    }

    private fun startPlayback() {
        sink.play()
        started = true
        meter?.onPlayhead(sink.playedFrames(), true)
        _playing.value = true
        reportAudioStart()
    }

    private fun reportAudioStart() {
        if (audioStartReported) return
        audioStartReported = true
        onAudioStart(currentTurn)
    }

    private fun ensureOpen(): Boolean {
        if (opened) return true
        opened = sink.open(format.sampleRate)
        if (!opened) Timber.w("live audio: sink did not open (%d Hz)", format.sampleRate)
        return opened
    }

    /** Пишем не больше ~20 мс за раз: [flush] между записями срабатывает с точностью до кадра. */
    private fun maxWriteBytes(): Int = (format.sampleRate / 50 * 2).coerceAtLeast(2)

    companion object {
        const val DEFAULT_JITTER_MS = 120L
        const val TICK_MS = 5L
        /** Запас к расчётному времени доигрывания (задержка аудиотракта). */
        const val DONE_MARGIN_MS = 800L
    }
}

/**
 * AudioTrack MODE_STREAM, PCM 16-bit mono, неблокирующая запись.
 * ПРОВЕРИТЬ НА РОБОТЕ: задержка выхода (getLatency недоступен публично), сбрасывается ли playbackHeadPosition
 * после pause()+flush() на Nova, и попадает ли этот звук в опорный сигнал AEC (USAGE_VOICE_COMMUNICATION).
 */
class AndroidAudioSink(
    /** USAGE_VOICE_COMMUNICATION — звук идёт опорой для эхоподавления; иначе USAGE_ASSISTANT. */
    private val communicationUsage: Boolean = true,
) : AudioSink {
    private var track: AudioTrack? = null
    private var base = 0L

    override fun open(sampleRate: Int): Boolean {
        release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferBytes = maxOf(minBuffer, sampleRate * 2 / 2) // ≥ 500 мс: сеть неровная, буфер выхода не узкое место
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (communicationUsage) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrNull()
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
            t?.release()
            return false
        }
        track = t
        base = 0
        return true
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int =
        track?.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING) ?: -1

    override fun play() {
        runCatching { track?.play() }
    }

    override fun pauseAndFlush() {
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
        }
        base = head(t)
    }

    override fun playedFrames(): Long {
        val t = track ?: return 0
        val h = head(t)
        if (h < base) base = 0 // голова сбросилась в 0 позже flush (зависит от устройства)
        return h - base
    }

    /** playbackHeadPosition — беззнаковый int. */
    private fun head(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun release() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
