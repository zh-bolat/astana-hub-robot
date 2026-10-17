package kz.astanahub.robot.places

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val backend = mockk<BackendApi>()
    private val robot = mockk<RobotGateway>()
    private var now = 0L

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined) =
        PlaceRepository(backend, robot, File(tmp.root, "places.json"), dispatcher, clock = { now })

    private fun places(list: List<kz.astanahub.robot.backend.Place>, etag: String) =
        ApiResult.Success(PlacesResult(list, etag, notModified = false))

    @Test
    fun `empty places response does not wipe a non-empty cache`() = runTest {
        coEvery { robot.getPlaces() } returns emptyList()
        coEvery { backend.places(any()) } returnsMany listOf(places(FakeBackendApi.FAKE_PLACES, "v1"), places(emptyList(), "v2"))
        val r = repo()
        r.refresh()
        r.refresh()
        assertEquals(FakeBackendApi.FAKE_PLACES.size, r.places().size)

        // И на диске тоже остался полный список (новый процесс читает кеш).
        coEvery { backend.places(any()) } returns places(emptyList(), "v3")
        val fresh = repo()
        fresh.refresh()
        assertEquals(FakeBackendApi.FAKE_PLACES.size, fresh.places().size)
        coVerify { backend.places("v1") } // etag пустого ответа не сохранён
    }

    @Test
    fun `empty list is accepted when there is no cache yet`() = runTest {
        coEvery { robot.getPlaces() } returns emptyList()
        coEvery { backend.places(any()) } returns places(emptyList(), "v0")
        val r = repo()
        assertTrue(r.refresh())
        assertTrue(r.places().isEmpty())
    }

    @Test
    fun `map points are cached for 10 minutes`() = runTest {
        coEvery { robot.getPlaces() } returns listOf(MapPlace("Кафе", 1.0, 1.0))
        val r = repo()
        r.mapPlaces(); r.mapPlaces()
        now += PlaceRepository.MAP_TTL_MILLIS - 1
        r.mapPlaces()
        coVerify(exactly = 1) { robot.getPlaces() }
        now += 2
        r.mapPlaces()
        coVerify(exactly = 2) { robot.getPlaces() }
        r.invalidateMap()
        r.mapPlaces()
        coVerify(exactly = 3) { robot.getPlaces() }
    }

    @Test
    fun `slow network refresh does not block local lookups`() = runTest(StandardTestDispatcher()) {
        coEvery { robot.getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0))
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        val gate = CompletableDeferred<Unit>()
        coEvery { backend.places(any()) } coAnswers { gate.await(); places(FakeBackendApi.FAKE_PLACES, "v1") }
        val r = repo(StandardTestDispatcher(testScheduler))
        val wf = WayFinder(r, robot)

        val refresh = async { r.refresh() }
        runCurrent() // refresh висит в сети
        val answer = async { wf.whereIs("кафе", Lang.RU) }
        runCurrent()
        assertTrue("поиск места не ждёт сеть", answer.isCompleted)
        assertTrue(!refresh.isCompleted)

        gate.complete(Unit)
        assertTrue(refresh.await())
    }

    @Test
    fun `nearest place id for ask context`() = runTest {
        coEvery { robot.getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 12.0), MapPlace("Ресепшен", 3.0, 0.0), MapPlace("Склад", 1.0, 0.0, status = 1))
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        coEvery { backend.places(any()) } returns places(FakeBackendApi.FAKE_PLACES, "v1")
        val r = repo()
        r.refresh()
        assertEquals("reception", WayFinder(r, robot).nearestPlaceId())

        coEvery { robot.getPosition() } returns null
        assertEquals(null, WayFinder(r, robot).nearestPlaceId())
    }
}
