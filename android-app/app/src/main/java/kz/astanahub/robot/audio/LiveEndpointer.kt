package kz.astanahub.robot.audio

/**
 * Конец фразы для live STT на стороне робота: тот же [EnergyVad] по кускам PCM16 LE, что уходят на сервер.
 * Как только после речи [VadConfig.endSilenceMillis] тишины — [accept] возвращает true (один раз):
 * приложение шлёт `{"type":"end"}`, не дожидаясь серверного VAD (~0,8 с).
 * Речь не найдена / слишком длинная — ничего не делаем, фразу закроет сервер.
 */
class LiveEndpointer(private val config: VadConfig = VadConfig()) {
    private val vad = EnergyVad(config)
    private val frame = ShortArray(config.frameSamples)
    private var filled = 0
    private var pendingLowByte: Int = -1
    var ended: Boolean = false
        private set

    /** @return true ровно один раз — в момент, когда фраза закончилась. */
    fun accept(pcmLe: ByteArray, count: Int = pcmLe.size): Boolean {
        if (ended || vad.finished) return false
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
                    VadEvent.SPEECH_ENDED -> {
                        ended = true
                        return true
                    }
                    VadEvent.NO_SPEECH, VadEvent.MAX_LENGTH -> return false
                    else -> Unit
                }
            }
        }
        return false
    }
}
