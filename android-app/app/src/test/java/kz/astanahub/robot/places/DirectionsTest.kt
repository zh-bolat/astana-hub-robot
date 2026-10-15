package kz.astanahub.robot.places

import kotlin.math.PI
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.robot.RobotPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionsTest {

    private val origin = RobotPose(0.0, 0.0, 0.0)

    @Test
    fun `robot side from pose and target`() {
        assertEquals(RobotSide.AHEAD, Directions.bearing(origin, 10.0, 1.0).robotSide)
        assertEquals(RobotSide.LEFT, Directions.bearing(origin, 0.0, 5.0).robotSide)
        assertEquals(RobotSide.RIGHT, Directions.bearing(origin, 1.0, -5.0).robotSide)
        assertEquals(RobotSide.BEHIND, Directions.bearing(origin, -5.0, 0.5).robotSide)
    }

    @Test
    fun `theta rotates the frame`() {
        val facingLeft = RobotPose(0.0, 0.0, PI / 2) // робот смотрит вдоль +y
        assertEquals(RobotSide.AHEAD, Directions.bearing(facingLeft, 0.0, 5.0).robotSide)
        assertEquals(RobotSide.RIGHT, Directions.bearing(facingLeft, 5.0, 0.0).robotSide)
        val shifted = RobotPose(10.0, 10.0, PI)
        assertEquals(RobotSide.AHEAD, Directions.bearing(shifted, 0.0, 10.0).robotSide)
        assertEquals(10.0, Directions.bearing(shifted, 0.0, 10.0).distanceM, 1e-9)
    }

    @Test
    fun `visitor faces robot so sides are mirrored`() {
        assertEquals(Side.RIGHT, Directions.toVisitor(RobotSide.LEFT))
        assertEquals(Side.LEFT, Directions.toVisitor(RobotSide.RIGHT))
        assertEquals(Side.BEHIND, Directions.toVisitor(RobotSide.AHEAD))
        assertEquals(Side.AHEAD, Directions.toVisitor(RobotSide.BEHIND))
    }

    @Test
    fun `angle normalization`() {
        assertEquals(-PI / 2, Directions.normalizeAngle(3 * PI / 2), 1e-9)
        assertEquals(PI / 2, Directions.normalizeAngle(-3 * PI / 2), 1e-9)
    }

    @Test
    fun `human rounding of meters`() {
        assertNull(Directions.humanMeters(1.2))
        assertEquals(3, Directions.humanMeters(2.6))
        assertEquals(9, Directions.humanMeters(9.4))
        assertEquals(15, Directions.humanMeters(13.0))
        assertEquals(25, Directions.humanMeters(27.4))
        assertEquals(40, Directions.humanMeters(43.0))
        assertEquals(150, Directions.humanMeters(160.0))
    }

    @Test
    fun `phrases in three languages`() {
        val b = Directions.bearing(origin, 0.0, 15.0) // слева от робота → справа от посетителя
        assertEquals("Кафе — справа от вас, примерно в 15 метрах. Могу проводить.", WayPhrases.whereIs("Кафе", b, Lang.RU, true))
        assertEquals("Кафе сіздің оң жағыңызда, шамамен 15 метр жерде.", WayPhrases.whereIs("Кафе", b, Lang.KK, false))
        assertEquals("Cafe is on your right, about 15 meters away.", WayPhrases.whereIs("Cafe", b, Lang.EN, false))
        assertEquals("Кафе совсем рядом — у вас за спиной.", WayPhrases.whereIs("Кафе", Directions.bearing(origin, 1.0, 0.0), Lang.RU, false))
    }

    @Test
    fun `russian locative agreement`() {
        assertEquals("1 метре", WayPhrases.ruMetersLocative(1))
        assertEquals("21 метре", WayPhrases.ruMetersLocative(21))
        assertEquals("11 метрах", WayPhrases.ruMetersLocative(11))
        assertEquals("5 метрах", WayPhrases.ruMetersLocative(5))
    }
}
