package kz.astanahub.robot.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Склейка фраз в куски для TTS: лимит 300 символов, целыми фразами, без задержки старта речи. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhraseQueueTest {

    private fun sentence(len: Int, c: Char = 'а') = c.toString().repeat(len - 1) + "."

    @Test
    fun `queued phrases are merged by whole phrases up to 300 chars`() = runTest {
        val q = PhraseQueue()
        val a = sentence(100, 'a')
        val b = sentence(150, 'b')
        val c = sentence(49, 'c') // 100 + 1 + 150 + 1 + 49 = 301 — не влезает
        val d = sentence(10, 'd')
        listOf(a, b, c, d).forEach(q::add)
        q.close()

        val first = q.nextChunk()!!
        assertEquals(listOf(a, b), first.phrases)
        assertEquals("$a $b", first.text)
        assertTrue(first.text.length <= 300)
        assertEquals(listOf(c, d), q.nextChunk()!!.phrases)
        assertNull(q.nextChunk())
    }

    @Test
    fun `exactly 300 chars still fits, a long phrase goes alone`() = runTest {
        val q = PhraseQueue()
        q.add(sentence(150, 'a'))
        q.add(sentence(149, 'b')) // 150 + 1 + 149 = 300
        q.add(sentence(300, 'c'))
        q.close()
        assertEquals(300, q.nextChunk()!!.text.length)
        assertEquals(1, q.nextChunk()!!.phrases.size)
        assertNull(q.nextChunk())
    }

    @Test
    fun `single takes only the first phrase - speech starts without waiting for the rest`() = runTest {
        val q = PhraseQueue()
        listOf("Раз.", "Два.", "Три.").forEach(q::add)
        assertEquals(listOf("Раз."), q.nextChunk(single = true, coalesceMillis = 150)!!.phrases)
        assertEquals(0, currentTime)
        assertEquals(listOf("Два.", "Три."), q.nextChunk()!!.phrases)
    }

    @Test
    fun `waits for the first phrase, returns null when closed and empty`() = runTest(StandardTestDispatcher()) {
        val q = PhraseQueue()
        val chunk = async { q.nextChunk() }
        advanceTimeBy(5_000)
        assertFalse(chunk.isCompleted)
        q.add("Привет.")
        runCurrent()
        assertEquals("Привет.", chunk.await()!!.text)

        val end = async { q.nextChunk() }
        runCurrent()
        q.close()
        runCurrent()
        assertNull(end.await())
    }

    @Test
    fun `coalesce waits at most 150 ms for more phrases`() = runTest(StandardTestDispatcher()) {
        val q = PhraseQueue()
        q.add("Раз.")
        launch {
            delay(100); q.add("Два.")   // успевает в окно
            delay(100); q.add("Три.")   // 200 мс — уже следующий кусок
        }
        val chunk = q.nextChunk(coalesceMillis = 150)!!
        assertEquals(listOf("Раз.", "Два."), chunk.phrases)
        assertEquals(150, currentTime)
        assertEquals(listOf("Три."), q.nextChunk()!!.phrases)
    }

    @Test
    fun `no coalesce wait when nothing is playing`() = runTest(StandardTestDispatcher()) {
        val q = PhraseQueue()
        q.add("Раз.")
        assertEquals(listOf("Раз."), q.nextChunk(coalesceMillis = 150, shouldWait = { false })!!.phrases)
        assertEquals(0, currentTime)
    }

    @Test
    fun `coalesce stops early when the chunk is full or the queue closes`() = runTest(StandardTestDispatcher()) {
        val q = PhraseQueue()
        q.add("Раз.")
        launch { delay(20); q.close() }
        assertEquals(listOf("Раз."), q.nextChunk(coalesceMillis = 150)!!.phrases)
        assertEquals(20, currentTime)
    }

    @Test
    fun `pushFront returns unspoken phrases in order`() = runTest {
        val q = PhraseQueue()
        q.add("Три.")
        q.pushFront(listOf("Раз.", "Два."))
        q.close()
        assertEquals(listOf("Раз.", "Два.", "Три."), q.pending())
        assertEquals("Раз. Два. Три.", q.nextChunk()!!.text)
    }
}
