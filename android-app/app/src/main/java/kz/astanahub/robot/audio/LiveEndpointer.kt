package kz.astanahub.robot.audio

/** Что заметил [LiveEndpointer] в очередном куске звука. */
enum class LiveVadEvent {
    NONE,

    /** Громче адаптивного порога не меньше [VadConfig.startMillis] подряд: можно открывать live STT. */
    SPEECH_STARTED,

    /** После речи [VadConfig.endSilenceMillis] тишины: слать `{"type":"end"}`. */
    SPEECH_ENDED,

    /** Речи не было [VadConfig.noSpeechTimeoutMillis]: закончить прослушивание без запроса. */
    NO_SPEECH,

    /** Фраза слишком длинная: конец найдёт сервер. */
    MAX_LENGTH,
}

/**
 * VAD live STT на стороне робота по кускам PCM16 LE (тем же, что уходят на сервер), на основе [EnergyVad]:
 * 1. **Старт** ([LiveVadEvent.SPEECH_STARTED]) — только если энергия выше адаптивного порога шума
 *    [VadConfig.startMillis] подряд. До этого WebSocket не открывается: смешок, «м», хлопок, шорох не тратят
 *    запрос распознавания. Звук, оборвавшийся раньше, — [shortSounds] (без запроса и без `end`).
 * 2. **Конец** ([LiveEndpointer.accept] = true, один раз) — [VadConfig.endSilenceMillis] тишины после речи:
 *    приложение шлёт `{"type":"end"}`, не дожидаясь серверного VAD (~0,8 с).
 */
class LiveEndpointer(private val config: VadConfig = VadConfig()) {
    private val vad = EnergyVad(config)
    private val frame = ShortArray(config.frameSamples)
    private var filled = 0
    private var pendingLowByte: Int = -1

    /** Речь началась (порог громкости и минимальная длительность пройдены). */
    var started: Boolean = false
        private set
    var ended: Boolean = false
        private set
    /** VAD закончил работу (конец фразы, нет речи, слишком длинно). */
    val finished: Boolean get() = vad.finished

    /** Короткие звуки до начала речи (громче порога, но короче минимальной длительности). */
    val shortSounds: Int get() = vad.shortSounds
    val lastShortSoundMillis: Int get() = vad.lastShortSoundMillis

    /** @return true ровно один раз — в момент, когда фраза закончилась. */
    fun accept(pcmLe: ByteArray, count: Int = pcmLe.size): Boolean = feed(pcmLe, count) == LiveVadEvent.SPEECH_ENDED

    /**
     * Кусок звука → самое важное событие в нём (конец / нет речи / слишком длинно важнее старта).
     * Старт и конец в одном куске возможны только на очень длинных кусках: тогда [started] тоже true.
     */
    fun feed(pcmLe: ByteArray, count: Int = pcmLe.size): LiveVadEvent {
        if (ended || vad.finished) return LiveVadEvent.NONE
        var result = LiveVadEvent.NONE
        var i = 0
        while (i < count) {
            val sample: Int
            if (pendingLowByte >= 0) {
                sample = (pcmLe[i].toInt() shl 8) or pendingLowByte
                pendingLowByte = -1
                i += 1
            } else if (i + 1 < count) {
                sample = (pcmLe[i + 1].toInt() shl 8) or (pcmLe[i].toInt() and 0xFF)
                i += 2
            } else {
                pendingLowByte = pcmLe[i].toInt() and 0xFF
                i += 1
                continue
            }
            frame[filled++] = sample.toShort()
            if (filled == frame.size) {
                filled = 0
                when (vad.accept(frame.copyOf())) {
                    VadEvent.SPEECH_STARTED -> {
                        started = true
                        result = LiveVadEvent.SPEECH_STARTED
                    }
                    VadEvent.SPEECH_ENDED -> {
                        ended = true
                        return LiveVadEvent.SPEECH_ENDED
                    }
                    VadEvent.NO_SPEECH -> return LiveVadEvent.NO_SPEECH
                    VadEvent.MAX_LENGTH -> return LiveVadEvent.MAX_LENGTH
                    VadEvent.NONE -> Unit
                }
            }
        }
        return result
    }
}
