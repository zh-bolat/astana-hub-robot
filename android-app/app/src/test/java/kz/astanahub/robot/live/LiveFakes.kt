package kz.astanahub.robot.live

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

/** Фейковый транспорт: кадры клиента копятся, события сервера тест кладёт через [server]. */
class FakeLiveConnection(
    /** На `hello` сервер сразу отвечает `ready` в этом формате (null — не отвечает). */
    private val autoReady: String? = "pcm_s16le_16000",
) : LiveConnection {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<LiveEvent> = channel

    val texts = mutableListOf<String>()
    val audio = mutableListOf<ByteArray>()
    var closed = false
        private set

    override fun sendText(frame: String): Boolean {
        if (closed) return false
        texts += frame
        if (autoReady != null && frame.contains("\"type\":\"hello\"")) server(LiveEvent.Ready(LiveAudioFormat.parse(autoReady)))
        return true
    }

    override fun sendAudio(pcm: ByteArray): Boolean {
        if (closed) return false
        audio += pcm
        return true
    }

    override fun close() {
        closed = true
        channel.close()
    }

    fun server(event: LiveEvent) {
        channel.trySend(event)
    }

    /** Текстовые кадры клиента указанного типа. */
    fun framesOfType(type: String) = texts.filter { it.contains("\"type\":\"$type\"") }
}

/** Фейковый микрофон: [frames] кадров с шагом frameMillis виртуального времени, потом тишина без кадров. */
class FakeLiveMicrophone(
    private val aecAvailable: Boolean = false,
    private val frames: Int = 3,
    private val frameMillis: Long = 40,
    private val fill: Byte = 7,
    private val failOpen: Boolean = false,
) : LiveMicrophone {
    var opened = 0
        private set
    var closed = 0
        private set

    override fun open(sampleRate: Int): OpenedMic? {
        if (failOpen) return null
        opened++
        return object : OpenedMic {
            private var left = frames
            override val aec: Boolean = aecAvailable
            override val description: String = "fake"

            override suspend fun read(frame: ByteArray): Boolean {
                if (left-- <= 0) awaitCancellation()
                delay(frameMillis)
                frame.fill(fill)
                return true
            }

            override fun close() {
                closed++
            }
        }
    }
}

/**
 * Фейковый выход звука: буфер [capacityBytes], «играет» в реальном темпе по виртуальным часам [now].
 * Пишет журнал: сколько байт принято после последнего flush, сколько после play.
 */
class FakeAudioSink(
    private val now: () -> Long,
    private val capacityBytes: Int = 16_000, // 500 мс при 16 кГц
) : AudioSink {
    var sampleRate = 0
        private set
    var openCount = 0
        private set
    var playing = false
        private set
    var flushes = 0
        private set
    /** Всего принято байт за всё время. */
    var totalWritten = 0L
        private set
    /** Байт принято после последнего flush (тест «тишина после перебивания»). */
    var writtenSinceFlush = 0L
        private set
    var playCalls = 0
        private set
    /** Время первого play() (виртуальное). */
    var firstPlayAt: Long? = null
        private set

    private var framesWritten = 0L
    private var playedBase = 0L
    private var playStartedAt = 0L

    override fun open(sampleRate: Int): Boolean {
        this.sampleRate = sampleRate
        openCount++
        return true
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val buffered = (framesWritten - playedFrames()) * 2
        val free = (capacityBytes - buffered).toInt().coerceAtLeast(0)
        val n = minOf(free, length) and 1.inv()
        framesWritten += n / 2
        totalWritten += n
        writtenSinceFlush += n
        return n
    }

    override fun play() {
        if (playing) return
        playing = true
        playCalls++
        if (firstPlayAt == null) firstPlayAt = now()
        playStartedAt = now()
    }

    override fun pauseAndFlush() {
        playing = false
        flushes++
        framesWritten = 0
        playedBase = 0
        writtenSinceFlush = 0
    }

    override fun playedFrames(): Long {
        if (!playing) return playedBase.coerceAtMost(framesWritten)
        val elapsed = now() - playStartedAt
        return (playedBase + elapsed * sampleRate / 1000).coerceAtMost(framesWritten)
    }

    override fun release() {
        playing = false
    }
}
