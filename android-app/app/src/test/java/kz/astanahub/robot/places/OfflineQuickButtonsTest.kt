package kz.astanahub.robot.places

import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.core.IntentRouter
import kz.astanahub.robot.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQuickButtonsTest {

    private val places = FakeBackendApi.FAKE_PLACES.mapIndexed { i, p -> QuickPlace(p, canGuide = i != 1) }

    @Test
    fun `where and guide buttons from cached places`() {
        val ru = OfflineQuickButtons.build(places, Lang.RU).map { it.text }
        assertEquals(
            listOf("Где Ресепшен?", "Где Кафе?", "Где Коворкинг?", "Где Конференц-зал?", "Проводите: Ресепшен", "Проводите: Коворкинг"),
            ru,
        )
    }

    @Test
    fun `every offline button is understood locally in every language`() {
        for (lang in Lang.entries) {
            for (button in OfflineQuickButtons.build(places, lang, maxWhere = 10, maxGuide = 10)) {
                val routed = IntentRouter.route(button.text)
                val expected = if (button.id.startsWith("offline_guide_")) IntentRouter.Intent.GUIDE_TO else IntentRouter.Intent.WHERE_IS
                assertEquals(button.text, expected, routed.intent)
                val match = PlaceMatcher.match(routed.subject.ifBlank { button.text }, FakeBackendApi.FAKE_PLACES)
                assertEquals(button.text, button.id.substringAfterLast('_'), match?.place?.id)
            }
        }
    }
}
