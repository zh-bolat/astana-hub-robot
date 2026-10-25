package kz.astanahub.robot.backend

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kz.astanahub.robot.core.Lang

/**
 * Ответ `POST /v1/tts?stream=true`: MP3 приходит кусками по мере синтеза у ElevenLabs.
 * [read] блокирует поток до прихода байтов (читать — не на главном потоке).
 */
interface TtsStream : Closeable {
    /** Сколько байт прочитано в [buffer]; -1 — поток закончился; IOException — обрыв посреди потока. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    /** Сервер отдал готовую фразу из своего кеша целиком (`X-Cache: hit`). */
    val fromCache: Boolean get() = false
}

/** Уже готовые байты как поток (сервер без стриминга, заглушки, тесты). */
class BytesTtsStream(private val bytes: ByteArray, override val fromCache: Boolean = false) : TtsStream {
    private var pos = 0

    override fun read(buffer: ByteArray): Int {
        if (pos >= bytes.size) return -1
        val n = minOf(buffer.size, bytes.size - pos)
        System.arraycopy(bytes, pos, buffer, 0, n)
        pos += n
        return n
    }

    override fun close() = Unit
}

/** Поток тела HTTP-ответа (OkHttp ResponseBody). */
class InputTtsStream(
    private val input: InputStream,
    private val onClose: () -> Unit,
    override val fromCache: Boolean,
) : TtsStream {
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun close() {
        runCatching { input.close() }
        onClose()
    }
}

/**
 * Тайминги одного хода разговора (`POST /v1/metrics/turn`, раздел «Мониторинг задержек»).
 * Все `*Ms` — от момента, когда VAD робота решил, что посетитель закончил; null — этапа не было.
 * Текстов реплик здесь нет и быть не должно.
 */
data class TurnMetrics(
    val sessionId: String,
    val requestId: String? = null,
    val lang: Lang? = null,
    /** `agent` / `ask` / `local` / `live`. */
    val mode: String,
    val sttFinalMs: Long? = null,
    val chatFirstEventMs: Long? = null,
    val firstPhraseMs: Long? = null,
    val ttsFirstByteMs: Long? = null,
    val firstAudioMs: Long? = null,
    val vadSilenceMs: Int? = null,
    val fallback: Boolean = false,
    val interrupted: Boolean = false,
) {
    companion object {
        const val MODE_AGENT = "agent"
        const val MODE_ASK = "ask"
        const val MODE_LOCAL = "local"
        /** Живой режим WS /v1/live: все `*_ms` — от `user_final` сервера (см. MainViewModel.LiveTurn). */
        const val MODE_LIVE = "live"
    }
}
