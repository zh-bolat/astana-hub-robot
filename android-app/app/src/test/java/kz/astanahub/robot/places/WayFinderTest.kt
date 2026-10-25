package kz.astanahub.robot.places

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kz.astanahub.robot.backend.ApiResult
import kz.astanahub.robot.backend.BackendApi
import kz.astanahub.robot.backend.BackendError
import kz.astanahub.robot.backend.ErrorKind
import kz.astanahub.robot.backend.FakeBackendApi
import kz.astanahub.robot.backend.PlacesResult
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.robot.MapPlace
import kz.astanahub.robot.robot.RobotGateway
import kz.astanahub.robot.robot.RobotPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WayFinderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val backend = mockk<BackendApi>()
    private val robot = mockk<RobotGateway>()
    private val map = listOf(
        MapPlace("Кафе", 0.0, 12.0),
        MapPlace("Ресепшен", 3.0, 0.0),
        MapPlace("Склад", -5.0, 0.0, status = 1),
    )

    private fun finder(file: File = File(tmp.root, "places.json")) = WayFinder(PlaceRepository(backend, robot, file), robot)

    private fun online() {
        coEvery { backend.places(any()) } returns ApiResult.Success(PlacesResult(FakeBackendApi.FAKE_PLACES, "\"v1\"", false))
    }

    @Test
    fun `where is answers locally with side and distance`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns map
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        val wf = finder()
        wf.refreshPlaces()

        val a = wf.whereIs("асхана", Lang.RU)!!
        assertEquals("Кафе — справа от вас, примерно в 10 метрах. Могу проводить.", a.text)
        assertEquals("cafe", a.guideTarget?.id)

        val en = wf.whereIs("cafe", Lang.EN)!!
        assertTrue(en.text, en.text.startsWith("Cafe is on your right"))
    }

    @Test
    fun `live directions - base places on the map keyed by place_id, all languages, no guide offer`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns listOf(
            MapPlace("Кафе", 0.0, 15.0),
            MapPlace("Ресепшен", 3.0, 0.0),
            MapPlace("Склад", -5.0, 0.0, status = 1), // точки нет в базе — сервер её не знает
            MapPlace("Туалет", 0.0, -1.5, status = 1), // недостижима, но направление известно
        )
        val wf = finder()
        wf.refreshPlaces()

        val d = wf.directionsFrom(RobotPose(0.0, 0.0, 0.0))
        // Коворкинга и конференц-зала нет на карте; «Склад» — не место базы.
        assertEquals(listOf("reception", "cafe", "wc"), d.keys.toList())
        d.values.forEach { assertEquals(Lang.entries.toSet(), it.keys) }
        assertEquals("Кафе — справа от вас, примерно в 15 метрах.", d.getValue("cafe")[Lang.RU])
        assertEquals("Кафе сіздің оң жағыңызда, шамамен 15 метр жерде.", d.getValue("cafe")[Lang.KK])
        assertEquals("Cafe is on your right, about 15 meters away.", d.getValue("cafe")[Lang.EN])
        assertEquals("Әжетхана өте жақын — сіздің сол жағыңызда.", d.getValue("wc")[Lang.KK])
        val offers = Lang.entries.map(WayPhrases::offerGuide)
        d.values.flatMap { it.values }.forEach { phrase ->
            assertTrue(phrase, offers.none { phrase.contains(it) })
            assertTrue(phrase, !phrase.contains("провод", ignoreCase = true))
        }
    }

    @Test
    fun `live directions follow the robot pose and are empty without a map`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns listOf(MapPlace("Кафе", 0.0, 15.0))
        val wf = finder()
        wf.refreshPlaces()
        // Робот развернулся на 90° влево: кафе теперь перед ним — за спиной посетителя.
        val turned = wf.directionsFrom(RobotPose(0.0, 0.0, Math.PI / 2))
        assertEquals("Кафе — у вас за спиной, примерно в 15 метрах.", turned.getValue("cafe")[Lang.RU])

        coEvery { robot.getPlaces() } returns emptyList()
        val noMap = WayFinder(PlaceRepository(backend, robot, File(tmp.root, "other.json")), robot)
        noMap.refreshPlaces()
        assertTrue(noMap.directionsFrom(RobotPose(0.0, 0.0, 0.0)).isEmpty())
    }

    @Test
    fun `offline uses file cache from previous run`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns map
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        val file = File(tmp.root, "cache.json")
        finder(file).refreshPlaces()

        coEvery { backend.places(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        val offline = finder(file)
        offline.refreshPlaces()
        assertNotNull(offline.whereIs("кафешка", Lang.RU))
        coVerify { backend.places("\"v1\"") } // ETag из кеша
    }

    @Test
    fun `unknown place returns null so the server can be asked`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns map
        val wf = finder()
        wf.refreshPlaces()
        assertNull(wf.whereIs("банкомат", Lang.RU))
    }

    @Test
    fun `place known to server but not on map, and robot without pose`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns map
        coEvery { robot.getPosition() } returns null
        val wf = finder()
        wf.refreshPlaces()

        val noMap = wf.whereIs("туалет", Lang.RU)!!
        assertTrue(noMap.text, noMap.text.contains("нет на моей карте"))
        assertNull(noMap.guideTarget)

        val noPose = wf.whereIs("кафе", Lang.RU)!!
        assertTrue(noPose.text, noPose.text.contains("не могу определить направление"))
        assertNotNull(noPose.guideTarget)
    }

    @Test
    fun `map-only points are found without server`() = runBlocking {
        coEvery { backend.places(any()) } returns ApiResult.Failure(BackendError(ErrorKind.NETWORK))
        coEvery { robot.getPlaces() } returns map
        coEvery { robot.getPosition() } returns RobotPose(0.0, 0.0, 0.0)
        val wf = finder()
        val a = wf.whereIs("склад", Lang.RU)!!
        assertNull("недостижимая точка — без «проводить»", a.guideTarget)
    }

    @Test
    fun `where am i gives nearest point`() = runBlocking {
        online()
        coEvery { robot.getPlaces() } returns map
        coEvery { robot.getPosition() } returns RobotPose(2.0, 0.5, 0.0)
        val wf = finder()
        wf.refreshPlaces()
        assertEquals("Мы сейчас у точки «Ресепшен».", wf.whereAmI(Lang.RU).text)

        coEvery { robot.getPosition() } returns null
        assertEquals(WayPhrases.unknownPosition(Lang.EN), wf.whereAmI(Lang.EN).text)
    }
}
