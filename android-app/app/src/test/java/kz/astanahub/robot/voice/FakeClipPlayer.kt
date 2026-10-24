package kz.astanahub.robot.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.audio.AudioClip
import kz.astanahub.robot.audio.ClipPlayer

/**
 * Проигрыватель-дублёр на виртуальном времени: «MP3» — байты текста, звучит [millisPerChar] на символ.
 * Пишет, когда что началось/закончилось, и следит, что каждый подготовленный клип освобождён.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeClipPlayer(private val test: TestScope, private val millisPerChar: Long = 50) : ClipPlayer {
    class Clip(val text: String) : AudioClip

    val prepared = mutableListOf<Clip>()
    val released = mutableSetOf<Clip>()

    /** (текст, время старта) и (текст, время конца). */
    val started = mutableListOf<Pair<String, Long>>()
    val finished = mutableListOf<Pair<String, Long>>()
    private val stops = MutableStateFlow(0)

    override suspend fun prepare(bytes: ByteArray): AudioClip = Clip(String(bytes)).also { prepared += it }

    override suspend fun play(clip: AudioClip): Boolean {
        clip as Clip
        check(clip !in released) { "клип уже освобождён" }
        val stopSeen = stops.value
        started += clip.text to test.currentTime
        try {
            val stopped = withTimeoutOrNull(clip.text.length * millisPerChar) { stops.first { it != stopSeen } }
            if (stopped == null) finished += clip.text to test.currentTime
            return stopped == null
        } finally {
            released += clip
        }
    }

    override fun release(clip: AudioClip) {
        released += clip as Clip
    }

    override fun stop() = stops.update { it + 1 }
}
