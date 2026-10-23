package kz.astanahub.robot.places

import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.robot.RobotGateway

/** Место для офлайн-кнопки «Где X?» / «Проводите: X». */
data class QuickPlace(val place: Place, val canGuide: Boolean)

/** Ответ «где …» — полностью локальный. [guideTarget] — можно предложить «Проводить». */
data class WayAnswer(val text: String, val place: Place?, val guideTarget: Place?)

/** «Где X?», «Где я?» и выбор цели для «Проводи» — без сервера, по карте робота и кешу мест. */
class WayFinder(
    private val repository: PlaceRepository,
    private val robot: RobotGateway,
) {
    suspend fun refreshPlaces(): Boolean = repository.refresh()

    suspend fun find(query: String): Place? = PlaceMatcher.match(query, repository.places())?.place

    /** null — место не узнали (тогда спрашиваем сервер, если он есть). */
    suspend fun whereIs(query: String, lang: Lang): WayAnswer? {
        val place = find(query) ?: return null
        return describe(place, lang)
    }

    /**
     * Место из `action` агента (`place_id`, `map_name`): сначала по id в кеше мест, потом по имени точки карты.
     * Точки нет в кеше мест, но она есть на карте робота — место без переводов (имя = map_name).
     */
    suspend fun placeFor(placeId: String?, mapName: String?): Place? {
        val places = repository.places()
        placeId?.let { id -> places.firstOrNull { it.id == id } }?.let { return it }
        mapName?.let { m -> places.firstOrNull { it.mapName.equals(m, ignoreCase = true) } }?.let { return it }
        val name = mapName ?: placeId ?: return null
        val point = repository.mapPlaces().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return Place(placeId ?: point.name, point.name, emptyMap(), emptyList(), emptyMap())
    }

    /** «Где [place]»: направление и расстояние от текущей позиции робота. */
    suspend fun describe(place: Place, lang: Lang): WayAnswer {
        val name = displayName(place, lang)
        val map = repository.mapPlaces()
        val point = repository.findOnMap(place, map)
            ?: return WayAnswer(WayPhrases.notOnMap(name, place.description[lang.code], lang), place, null)
        val canGuide = point.isReachable
        val pose = robot.getPosition()
            ?: return WayAnswer(WayPhrases.whereIsNoPose(name, lang, canGuide), place, place.takeIf { canGuide })
        val bearing = Directions.bearing(pose, point.x, point.y)
        return WayAnswer(WayPhrases.whereIs(name, bearing, lang, canGuide), place, place.takeIf { canGuide })
    }

    /**
     * place_id для /ask и /look: ближайшая достижимая точка карты (id места с сервера, иначе имя на карте).
     * null — позиция неизвестна.
     */
    suspend fun nearestPlaceId(): String? {
        val pose = robot.getPosition() ?: return null
        val nearest = repository.mapPlaces().filter { it.isReachable }
            .minByOrNull { Directions.bearing(pose, it.x, it.y).distanceM } ?: return null
        return repository.places().firstOrNull { it.mapName.equals(nearest.name, ignoreCase = true) }?.id ?: nearest.name
    }

    /** Места для кнопок без сети: только те, что есть на карте; [QuickPlace.canGuide] — точка достижима. */
    suspend fun quickPlaces(): List<QuickPlace> {
        val map = repository.mapPlaces()
        return repository.places().mapNotNull { place ->
            repository.findOnMap(place, map)?.let { QuickPlace(place, it.isReachable) }
        }
    }

    suspend fun whereAmI(lang: Lang): WayAnswer {
        val pose = robot.getPosition() ?: return WayAnswer(WayPhrases.unknownPosition(lang), null, null)
        val map = repository.mapPlaces().filter { it.isReachable }
        val nearest = map.minByOrNull { Directions.bearing(pose, it.x, it.y).distanceM }
            ?: return WayAnswer(WayPhrases.unknownPosition(lang), null, null)
        val place = repository.places().firstOrNull { it.mapName.equals(nearest.name, ignoreCase = true) }
        val name = place?.let { displayName(it, lang) } ?: nearest.name
        val bearing = Directions.bearing(pose, nearest.x, nearest.y)
        val text = if (bearing.distanceM < HERE_RADIUS_M) WayPhrases.hereAt(name, lang) else WayPhrases.nearest(name, bearing, lang)
        return WayAnswer(text, place, null)
    }

    companion object {
        const val HERE_RADIUS_M = 3.0

        fun displayName(place: Place, lang: Lang): String =
            place.names[lang.code]?.takeIf { it.isNotBlank() } ?: place.names["ru"]?.takeIf { it.isNotBlank() } ?: place.mapName
    }
}
