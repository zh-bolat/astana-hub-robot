package kz.astanahub.robot.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.core.PhraseKey
import kz.astanahub.robot.core.Phrases
import kz.astanahub.robot.places.WayPhrases
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кеш фраз голосом ElevenLabs: хранение, разбиение по закешированным предложениям, синтез при старте. */
class PhraseCacheTest {

    private val phrases = Phrases { key, lang -> "${key.name} ${lang.code}." }

    @Test
    fun `file store keeps mp3 between instances and key depends on lang and text`() {
        val dir = Files.createTempDirectory("voice").toFile()
        val cache = PhraseAudioCache(FileAudioStore(dir))
        cache.put("Привет.", Lang.RU, byteArrayOf(1, 2, 3))
        val again = PhraseAudioCache(FileAudioStore(dir))
        assertArrayEquals(byteArrayOf(1, 2, 3), again.get("  Привет. ", Lang.RU))
        assertNull(again.get("Привет.", Lang.KK))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        dir.deleteRecursively()
    }

    @Test
    fun `segment splits out cached sentences and keeps the rest together`() {
        val cache = PhraseAudioCache(MemoryAudioStore())
        cache.put(WayPhrases.followMe(Lang.RU), Lang.RU, byteArrayOf(1))
        assertEquals(
            listOf("Идите за мной.", "Провожу: Кафе."),
            cache.segment(WayPhrases.guideStart("Кафе", Lang.RU), Lang.RU),
        )
        // ничего не закешировано — фраза как есть
        assertEquals(listOf("Раз. Два."), cache.segment("Раз. Два.", Lang.RU))
        cache.put("Два.", Lang.RU, byteArrayOf(2))
        assertEquals(listOf("Раз. Три.", "Два."), cache.segment("Раз. Три. Два.", Lang.RU))
        assertEquals(listOf("Раз.", "Два.", "Три. Четыре."), cache.segment("Раз. Два. Три. Четыре.", Lang.RU))
    }

    @Test
    fun `cached units cover all phrase keys, fixed way phrases and fillers in every language`() {
        Lang.entries.forEach { lang ->
            val units = CachedPhrases.units(phrases, lang)
            PhraseKey.entries.forEach { assertTrue(phrases.get(it, lang) in units) }
            WayPhrases.fixed(lang).forEach { assertTrue(it in units) }
            assertEquals(phrases.get(PhraseKey.NO_CONNECTION, lang), units.first())
        }
    }

    @Test
    fun `warm up synthesizes every missing phrase once and skips cached ones`() = runTest {
        val backend = mockk<BackendApi>()
        coEvery { backend.tts(any(), any()) } answers { ApiResult.Success(firstArg<String>().toByteArray()) }
        val cache = PhraseAudioCache(MemoryAudioStore())
        val warmer = PhraseCacheWarmer(backend, cache, phrases, backgroundScope)
        val total = Lang.entries.sumOf { CachedPhrases.units(phrases, it).size }

        warmer.run(force = false)
        coVerify(exactly = total) { backend.tts(any(), any()) }
        assertEquals(total, cache.size())
        assertEquals(PhraseCacheWarmer.Status(total, total, running = false), warmer.status.value)
        assertArrayEquals("GREETING ru.".toByteArray(), cache.get("GREETING ru.", Lang.RU))

        warmer.run(force = false) // всё уже есть — сеть не трогаем
        coVerify(exactly = total) { backend.tts(any(), any()) }

        warmer.run(force = true) // сменили сервер — синтезируем заново
        coVerify(exactly = 2 * total) { backend.tts(any(), any()) }
    }

    @Test
    fun `warm up without network stops at the first failure and keeps what is cached`() = runTest {
        val backend = mockk<BackendApi>()
        coEvery { backend.tts(any(), any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val cache = PhraseAudioCache(MemoryAudioStore())
        cache.put("старое", Lang.RU, byteArrayOf(1))
        val warmer = PhraseCacheWarmer(backend, cache, phrases, backgroundScope)
        warmer.run(force = false)
        coVerify(exactly = 1) { backend.tts(any(), any()) }
        assertTrue(cache.has("старое", Lang.RU)) // неполный проход ничего не удаляет
        assertFalse(warmer.status.value.running)
        assertTrue(warmer.status.value.lastError!!.startsWith("NETWORK"))
    }

    @Test
    fun `complete warm up drops files of phrases that are no longer used`() = runTest {
        val backend = mockk<BackendApi>()
        coEvery { backend.tts(any(), any()) } returns ApiResult.Success(byteArrayOf(7))
        val cache = PhraseAudioCache(MemoryAudioStore())
        cache.put("фраза прошлой версии", Lang.RU, byteArrayOf(1))
        PhraseCacheWarmer(backend, cache, phrases, backgroundScope).run(force = false)
        assertFalse(cache.has("фраза прошлой версии", Lang.RU))
    }
}
