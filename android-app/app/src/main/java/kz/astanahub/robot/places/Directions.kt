package kz.astanahub.robot.places

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kz.astanahub.robot.robot.RobotPose

/** Сторона относительно ПОСЕТИТЕЛЯ, который стоит лицом к роботу. */
enum class Side { AHEAD, BEHIND, LEFT, RIGHT }

/** Сторона относительно корпуса робота (x вперёд, y влево, theta против часовой — как в RobotAPI/ROS). */
enum class RobotSide { AHEAD, LEFT, RIGHT, BEHIND }

data class Bearing(val distanceM: Double, val relativeAngleRad: Double) {
    val robotSide: RobotSide get() = Directions.robotSide(relativeAngleRad)
    val visitorSide: Side get() = Directions.toVisitor(robotSide)
}

object Directions {

    /** Посетитель стоит перед роботом лицом к нему: левое у робота — правое у посетителя. ПРОВЕРИТЬ НА РОБОТЕ. */
    const val VISITOR_FACES_ROBOT = true

    fun bearing(pose: RobotPose, targetX: Double, targetY: Double): Bearing {
        val dx = targetX - pose.x
        val dy = targetY - pose.y
        return Bearing(hypot(dx, dy), normalizeAngle(atan2(dy, dx) - pose.theta))
    }

    fun normalizeAngle(a: Double): Double {
        var r = a % (2 * PI)
        if (r > PI) r -= 2 * PI
        if (r < -PI) r += 2 * PI
        return r
    }

    fun robotSide(angle: Double): RobotSide {
        val deg = Math.toDegrees(angle)
        return when {
            abs(deg) <= 45 -> RobotSide.AHEAD
            deg > 45 && deg < 135 -> RobotSide.LEFT
            deg < -45 && deg > -135 -> RobotSide.RIGHT
            else -> RobotSide.BEHIND
        }
    }

    fun toVisitor(side: RobotSide): Side = if (!VISITOR_FACES_ROBOT) {
        when (side) {
            RobotSide.AHEAD -> Side.AHEAD; RobotSide.BEHIND -> Side.BEHIND
            RobotSide.LEFT -> Side.LEFT; RobotSide.RIGHT -> Side.RIGHT
        }
    } else {
        when (side) {
            RobotSide.AHEAD -> Side.BEHIND // за спиной посетителя
            RobotSide.BEHIND -> Side.AHEAD // за роботом
            RobotSide.LEFT -> Side.RIGHT
            RobotSide.RIGHT -> Side.LEFT
        }
    }

    /** Расстояние «по-человечески»: null — совсем рядом (< 2 м); дальше округление 1 / 5 / 10 / 50 м. */
    fun humanMeters(d: Double): Int? = when {
        d < 2.0 -> null
        d < 10.0 -> d.roundToInt()
        d < 30.0 -> (d / 5).roundToInt() * 5
        d < 100.0 -> (d / 10).roundToInt() * 10
        else -> (d / 50).roundToInt() * 50
    }
}
