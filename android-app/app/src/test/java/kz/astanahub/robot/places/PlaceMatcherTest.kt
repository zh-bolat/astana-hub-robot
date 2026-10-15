package kz.astanahub.robot.places

import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceMatcherTest {

    private val places = FakeBackendApi.FAKE_PLACES

    private fun id(query: String) = PlaceMatcher.match(query, places)?.place?.id

    @Test
    fun `names, synonyms and cases`() {
        val cases = mapOf(
            "Где кафе?" to "cafe",
            "где находится кафешка" to "cafe",
            "Асхана қайда?" to "cafe",
            "асханаға апар" to "cafe",
            "Where is the cafe?" to "cafe",
            "хочу кофе" to "cafe",
            "как пройти к ресепшену" to "reception",
            "у ресепшена" to "reception",
            "Where is the reception" to "reception",
            "front desk" to "reception",
            "где туалет" to "wc",
            "уборная" to "wc",
            "Әжетхана қай жерде?" to "wc",
            "дәретхана" to "wc",
            "where is the restroom" to "wc",
            "конференц-зал" to "hall",
            "в конференц зале" to "hall",
            "конференс зал" to "hall",
            "auditorium" to "hall",
            "в коворкинге" to "coworking",
            "коворкинга" to "coworking",
            "open space" to "coworking",
        )
        cases.forEach { (q, expected) -> assertEquals(q, expected, id(q)) }
    }

    @Test
    fun `unknown places are not matched`() {
        listOf("где переговорка", "где директор", "Где", "где здесь банкомат", "кофта").forEach {
            assertNull(it, id(it))
        }
    }

    @Test
    fun `longer exact term wins over short one`() {
        val list = listOf(
            Place("small", "Зал", mapOf("ru" to "Зал"), emptyList(), emptyMap()),
            Place("conf", "Конференц-зал", mapOf("ru" to "Конференц-зал"), emptyList(), emptyMap()),
        )
        assertEquals("conf", PlaceMatcher.match("где конференц-зал", list)?.place?.id)
        assertEquals("small", PlaceMatcher.match("где зал", list)?.place?.id)
    }

    @Test
    fun `map-only places match by map name`() {
        val list = listOf(Place("Point A", "Point A", emptyMap(), emptyList(), emptyMap()))
        assertEquals("Point A", PlaceMatcher.match("where is point a", list)?.place?.id)
    }

    @Test
    fun `normalizer folds kazakh letters and punctuation`() {
        assertEquals("ажетхана кай жерде", TextNormalizer.normalize("Әжетхана, қай жерде?!"))
        assertEquals("конференц зал", TextNormalizer.normalize("Конференц-Зал"))
        assertEquals(1, PlaceMatcher.levenshtein("конференс", "конференц"))
    }
}
