package kz.astanahub.robot.places

import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang

/**
 * Кнопки быстрого доступа без сети — из кеша мест: «Где кафе?», «Проводите: Ресепшен».
 * Формулировки подобраны так, чтобы IntentRouter узнал where_is / guide_to, а PlaceMatcher — место
 * (служебные слова «проводите», «апарыңыз», «take me to» отбрасываются при сопоставлении).
 */
object OfflineQuickButtons {
    const val MAX_WHERE = 4
    const val MAX_GUIDE = 2

    fun whereText(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Где $name?"
        Lang.KK -> "$name қайда?"
        Lang.EN -> "Where is $name?"
    }

    fun guideText(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Проводите: $name"
        Lang.KK -> "Апарыңыз: $name"
        Lang.EN -> "Take me to $name"
    }

    fun build(places: List<QuickPlace>, lang: Lang, maxWhere: Int = MAX_WHERE, maxGuide: Int = MAX_GUIDE): List<QuickQuestion> {
        val where = places.take(maxWhere).map { qp ->
            QuickQuestion("offline_where_${qp.place.id}", whereText(WayFinder.displayName(qp.place, lang), lang))
        }
        val guide = places.filter { it.canGuide }.take(maxGuide).map { qp ->
            QuickQuestion("offline_guide_${qp.place.id}", guideText(WayFinder.displayName(qp.place, lang), lang))
        }
        return where + guide
    }
}
