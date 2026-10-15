package kz.astanahub.robot.places

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.backend.PlaceDto
import kz.astanahub.robot.backend.toDomain
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.RobotGateway
import timber.log.Timber

@Serializable
internal data class PlacesCacheDto(val etag: String? = null, val places: List<PlaceDto> = emptyList())

/**
 * Места: /v1/places (ETag → 304) + офлайн-кеш в файле + точки карты робота (getPlaceList).
 * Без сети работает по кешу; точки карты без описания на сервере тоже участвуют (name = map_name).
 */
class PlaceRepository(
    private val backend: BackendApi,
    private val robot: RobotGateway,
    private val cacheFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var serverPlaces: List<Place>? = null
    private var etag: String? = null
    private var mapPlaces: List<MapPlace> = emptyList()

    /** Обновить список с сервера; при ошибке остаёмся на кеше. */
    suspend fun refresh(): Boolean = mutex.withLock {
        loadCacheIfNeeded()
        when (val r = backend.places(etag)) {
            is ApiResult.Success -> {
                val result = r.value
                if (!result.notModified && result.places != null) {
                    serverPlaces = result.places
                    etag = result.etag
                    saveCache(result.places, result.etag)
                    Timber.i("places updated: %d, etag=%s", result.places.size, result.etag)
                }
                true
            }
            is ApiResult.Failure -> {
                Timber.w("places refresh failed: %s (cache: %d)", r.error.kind, serverPlaces?.size ?: 0)
                false
            }
        }
    }

    /** Точки карты робота; при недоступном RobotAPI — последний удачный список. */
    suspend fun mapPlaces(): List<MapPlace> {
        val fresh = runCatching { robot.getPlaces() }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) mapPlaces = fresh
        return mapPlaces
    }

    /** Места для сопоставления: серверные + точки карты, которых нет на сервере. */
    suspend fun places(): List<Place> {
        val server = mutex.withLock { loadCacheIfNeeded(); serverPlaces.orEmpty() }
        val known = server.map { it.mapName.trim().lowercase() }.toSet()
        val mapOnly = mapPlaces()
            .filter { it.name.trim().lowercase() !in known }
            .map { Place(id = it.name, mapName = it.name, names = emptyMap(), synonyms = emptyList(), description = emptyMap()) }
        return server + mapOnly
    }

    fun findOnMap(place: Place, map: List<MapPlace>): MapPlace? =
        map.firstOrNull { it.name.trim().equals(place.mapName.trim(), ignoreCase = true) }

    private suspend fun loadCacheIfNeeded() {
        if (serverPlaces != null) return
        val cache = withContext(io) {
            runCatching {
                if (cacheFile.isFile) json.decodeFromString(PlacesCacheDto.serializer(), cacheFile.readText()) else null
            }.onFailure { Timber.e(it, "places cache read") }.getOrNull()
        }
        if (cache != null) {
            serverPlaces = cache.places.map { it.toDomain() }
            etag = cache.etag
            Timber.i("places from cache: %d", cache.places.size)
        }
    }

    private suspend fun saveCache(places: List<Place>, etag: String?) = withContext(io) {
        runCatching {
            val dto = PlacesCacheDto(etag, places.map { PlaceDto(it.id, it.mapName, it.names, it.synonyms, it.description) })
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(PlacesCacheDto.serializer(), dto))
            tmp.renameTo(cacheFile)
        }.onFailure { Timber.e(it, "places cache write") }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
