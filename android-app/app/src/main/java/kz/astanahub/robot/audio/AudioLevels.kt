package kz.astanahub.robot.audio

import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kz.astanahub.robot.ui.face.FaceAudioSource
import kz.astanahub.robot.ui.face.SpeechSynth

/**
 * Громкость звука для лица: микрофон (голос посетителя) и речь робота. Пишут аудиопотоки (запись, плеер),
 * читает кадр лица на главном потоке — через volatile-поля, без блокировок и аллокаций.
 *
 * Источники речи робота: live — точно по PCM и позиции воспроизведения ([pcm]); MP3 серверного TTS —
 * Visualizer на сессии MediaPlayer ([reportPlayback], см. [PlaybackVisualizer]). Встроенный TTS громкость
 * не отдаёт — тогда [playbackLevel] = −1 и лицо двигает рот синтетически.
 *
 * [debugMic]/[debugPlayback] — только отладка на эмуляторе (adb broadcast): имитация голоса.
 */
class AudioLevels(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) : FaceAudioSource {

    @Volatile private var mic = 0f
    @Volatile private var micAt = Long.MIN_VALUE / 2
    @Volatile private var play = 0f
    @Volatile private var playAt = Long.MIN_VALUE / 2
    @Volatile private var voiceNow = false
    @Volatile private var voiceAt = Long.MIN_VALUE / 2
    @Volatile private var voiceStaleMs = VOICE_STALE_MS

    private val _voice = MutableStateFlow(false)

    /**
     * VAD как поток (без устаревания) — для MainViewModel: голос будит из сна. Кадр лица читает [voiceActive]
     * (с устареванием); поток меняется только на смене значения (StateFlow сам отбрасывает повторы).
     */
    val voice: StateFlow<Boolean> = _voice

    /** Речь live-режима: громкость по PCM в момент, который реально звучит из динамика. */
    val pcm = PcmPlaybackMeter(clockMs)

    @Volatile var debugMic: Boolean = false
        set(value) {
            field = value
            _voice.value = value
        }
    @Volatile var debugPlayback: Boolean = false

    /** Отладка: постоянный гул зала (громкость без речи — VAD молчит). */
    @Volatile var debugNoise: Boolean = false
    private val debugMicSynth = SpeechSynth(Random(11))
    private val debugPlaySynth = SpeechSynth(Random(23))

    /** Уровень микрофона 0..1 (RMS / 32768) — из потока записи. */
    fun reportMic(level: Float) {
        mic = level
        micAt = clockMs()
    }

    /**
     * VAD: посетитель говорит / замолчал. Источники: адаптивный VAD приложения (`EnergyVad` / `LiveEndpointer`
     * в ServerVoiceGateway) и `user_speaking` … `user_final` сервера (LiveSession). Не обновлялся [VOICE_STALE_MS] —
     * считается тишиной (источник оборвался, не сказав «замолчал»).
     */
    fun reportVoice(active: Boolean, sticky: Boolean = false) {
        voiceNow = active
        voiceAt = clockMs()
        // Частые источники (VAD приложения — каждый кусок 40–100 мс) устаревают за секунду; редкие (сервер шлёт
        // `user_speaking` раз, конец — `user_final`) держатся до [VOICE_STALE_MS].
        voiceStaleMs = if (sticky) VOICE_STALE_MS else VOICE_STALE_FREQUENT_MS
        _voice.value = active
    }

    override fun voiceActive(): Boolean {
        if (debugMic) return true
        return voiceNow && clockMs() - voiceAt <= voiceStaleMs
    }

    /** Уровень речи робота 0..1, измеренный не по PCM (Visualizer). */
    fun reportPlayback(level: Float) {
        play = level
        playAt = clockMs()
    }

    override fun micLevel(): Float {
        val now = clockMs()
        if (debugMic) return debugMicSynth.level(now, true) * 0.6f + if (debugNoise) DEBUG_NOISE_RMS else 0f
        if (debugNoise) return DEBUG_NOISE_RMS
        return if (now - micAt <= FRESH_MS) mic else 0f
    }

    override fun playbackLevel(): Float {
        val now = clockMs()
        if (debugPlayback) return debugPlaySynth.level(now, true)
        val p = pcm.level(now)
        if (p >= 0f) return p
        return if (now - playAt <= FRESH_MS) play else -1f
    }

    companion object {
        /** Уровень старше — уже не «сейчас» (поток записи остановлен). */
        const val FRESH_MS = 250L

        /** VAD сервера без обновлений столько — тишина. Сервер шлёт `user_speaking` один раз, конец — `user_final`. */
        const val VOICE_STALE_MS = 8_000L

        /** VAD приложения шлёт состояние на каждый кусок звука — оборвался, значит, через секунду тишина. */
        const val VOICE_STALE_FREQUENT_MS = 1_000L

        /** Гул зала для отладки: −30 дБFS (вентиляция и толпа в атриуме). */
        const val DEBUG_NOISE_RMS = 0.03f

        /** RMS кадра PCM 16-bit LE mono / 32768. */
        fun rmsPcm16(bytes: ByteArray, offset: Int, length: Int): Float {
            val samples = length / 2
            if (samples <= 0) return 0f
            var sum = 0.0
            var i = offset
            val end = offset + samples * 2
            while (i < end) {
                val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                sum += (s * s).toDouble()
                i += 2
            }
            return (sqrt(sum / samples) / 32768.0).toFloat()
        }

        /** RMS волны Visualizer (8 бит без знака, 128 — ноль) / 128. */
        fun rmsU8(bytes: ByteArray): Float {
            if (bytes.isEmpty()) return 0f
            var sum = 0.0
            for (b in bytes) {
                val s = (b.toInt() and 0xFF) - 128
                sum += (s * s).toDouble()
            }
            return (sqrt(sum / bytes.size) / 128.0).toFloat()
        }
    }
}

/**
 * Громкость потокового воспроизведения (live) в момент, который реально звучит: писатель ([StreamPlayer], свой
 * поток) кладёт RMS блоков по 20 мс с номером первого кадра и сообщает позицию воспроизведения; читатель
 * (кадр лица) экстраполирует позицию по часам и берёт RMS блока под ней. Кольцо фиксированного размера,
 * один писатель / один читатель, без блокировок; редкое «рваное» чтение старого слота даёт лишь мелкий всплеск.
 */
class PcmPlaybackMeter(private val clockMs: () -> Long) {
    private val blockStart = LongArray(CAPACITY)
    private val blockRms = FloatArray(CAPACITY)

    /** Сколько блоков записано с последнего [reset] (запись в массивы — до инкремента). */
    @Volatile private var count = 0

    @Volatile private var sampleRate = 24_000
    @Volatile private var playing = false
    @Volatile private var playedFrames = 0L
    @Volatile private var playedAt = 0L
    @Volatile private var writtenFrames = 0L

    // Только писатель: незаконченный блок.
    private var accSum = 0.0
    private var accFrames = 0
    private var accStart = 0L

    fun configure(rate: Int) {
        sampleRate = rate
    }

    /** Записано в выход [length] байт PCM16 mono, первый кадр — [firstFrame] (номер с начала хода). */
    fun onWritten(pcm: ByteArray, offset: Int, length: Int, firstFrame: Long) {
        val block = sampleRate / BLOCKS_PER_SECOND
        var i = offset
        val end = offset + (length / 2) * 2
        var frame = firstFrame
        while (i < end) {
            if (accFrames == 0) accStart = frame
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            accSum += (s * s).toDouble()
            accFrames++
            frame++
            i += 2
            if (accFrames >= block) flushBlock()
        }
        writtenFrames = frame
    }

    /** Позиция воспроизведения (кадров с начала хода), [active] — звук идёт. */
    fun onPlayhead(played: Long, active: Boolean) {
        playedFrames = played
        playedAt = clockMs()
        playing = active
    }

    /** Звук оборван или ход доигран: дальше — тишина до нового хода. */
    fun reset() {
        playing = false
        count = 0
        accFrames = 0
        accSum = 0.0
        writtenFrames = 0
        playedFrames = 0
    }

    /** RMS того, что звучит сейчас; −1 — live сейчас не играет. */
    fun level(nowMs: Long): Float {
        if (!playing) return -1f
        val n = count
        if (n == 0) return 0f
        val estimated = (playedFrames + (nowMs - playedAt).coerceAtLeast(0) * sampleRate / 1000)
            .coerceAtMost(writtenFrames)
        var k = n - 1
        val oldest = (n - CAPACITY).coerceAtLeast(0)
        while (k >= oldest) {
            val slot = k % CAPACITY
            if (blockStart[slot] <= estimated) return blockRms[slot]
            k--
        }
        return 0f
    }

    private fun flushBlock() {
        val slot = count % CAPACITY
        blockStart[slot] = accStart
        blockRms[slot] = (sqrt(accSum / accFrames) / 32768.0).toFloat()
        accSum = 0.0
        accFrames = 0
        count += 1
    }

    companion object {
        /** Блок — 20 мс. */
        const val BLOCKS_PER_SECOND = 50
        const val CAPACITY = 512
    }
}
