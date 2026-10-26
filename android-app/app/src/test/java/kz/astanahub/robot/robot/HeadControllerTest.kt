package kz.astanahub.robot.robot

import kz.astanahub.robot.ui.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadControllerTest {

    private class FakeHead : HeadGateway {
        val calls = mutableListOf<String>()
        var lost: (() -> Unit)? = null
        override fun startFocusFollow(faceId: Int, onLost: () -> Unit): Boolean {
            calls += "follow:$faceId"
            lost = onLost
            return true
        }
        override fun stopFocusFollow() { calls += "stop" }
        override fun resetHead() { calls += "reset" }
        override fun nod(degrees: Int) { calls += "nod:$degrees" }
        override fun turnTo(degrees: Int) { calls += "turn:$degrees" }
        override fun tilt(degrees: Int) { calls += "tilt:$degrees" }
    }

    private var now = 0L
    private val head = FakeHead()

    /** Отложенные действия: (когда, что) — выполняет [advance]. */
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private val controller = HeadController(
        head, clock = { now }, nodIntervalMillis = 20_000, gestureIntervalMillis = 9_000,
        schedule = { ms, block -> pending += (now + ms) to block },
    )

    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = pending.filter { it.first <= until }.minByOrNull { it.first } ?: break
            pending.remove(next)
            now = next.first
            next.second()
        }
        now = until
    }

    @Test
    fun `visitor is followed once, leaving stops and resets the head`() {
        controller.follow(3)
        controller.follow(3) // док: не вызывать повторно для того же человека
        controller.follow(-1) // без id лица — следить не за кем
        assertTrue(controller.isFollowing)
        controller.release()
        assertEquals(listOf("follow:3", "stop", "reset"), head.calls)
        assertFalse(controller.isFollowing)
    }

    @Test
    fun `no focus follow during navigation, resumes after`() {
        controller.follow(4)
        controller.setNavigating(true)
        controller.follow(5) // новый id во время поездки — только запомнить
        assertFalse(controller.isFollowing)
        controller.setNavigating(false)
        assertEquals(listOf("follow:4", "stop", "follow:5"), head.calls)
    }

    @Test
    fun `visitor left during navigation - head is not reset until the trip ends`() {
        controller.follow(4)
        controller.setNavigating(true)
        controller.release()
        controller.setNavigating(false)
        assertEquals(listOf("follow:4", "stop"), head.calls)
    }

    @Test
    fun `camera snapshot pauses following`() {
        controller.follow(2)
        controller.setCameraBusy(true)
        controller.setCameraBusy(false)
        assertEquals(listOf("follow:2", "stop", "follow:2"), head.calls)
    }

    @Test
    fun `lost target can be followed again`() {
        controller.follow(2)
        head.lost?.invoke()
        assertFalse(controller.isFollowing)
        controller.follow(2)
        assertEquals(listOf("follow:2", "follow:2"), head.calls)
    }

    @Test
    fun `nod 14 degrees on joy and delight, rarely and only when the head is free`() {
        controller.onEmotion(Emotion.HAPPY)
        now += 5_000
        controller.onEmotion(Emotion.EXCITED) // слишком часто
        now += 20_000
        controller.onEmotion(Emotion.SAD) // не кивает на грусть
        controller.onEmotion(Emotion.SURPRISED) // удивление — без кивка
        controller.follow(1)
        controller.onEmotion(Emotion.HAPPY) // голова занята слежением
        controller.release()
        controller.setNavigating(true)
        controller.onEmotion(Emotion.HAPPY) // едем
        controller.setNavigating(false)
        controller.setCameraBusy(true)
        controller.onEmotion(Emotion.HAPPY) // снимок
        controller.setCameraBusy(false)
        controller.onEmotion(Emotion.EXCITED)
        assertEquals(listOf("nod:14", "follow:1", "stop", "reset", "nod:14"), head.calls)
        assertTrue(HeadController.NOD_DEGREES in 12..15)
    }

    @Test
    fun `greeting nod comes first, following starts after the nod`() {
        controller.greet()
        controller.follow(7)
        assertEquals(listOf("nod:14"), head.calls) // слежение оборвало бы кивок
        controller.onEmotion(Emotion.EXCITED) // эмоция приветствия — второго кивка нет
        advance(HeadController.NOD_MS)
        assertEquals(listOf("nod:14", "follow:7"), head.calls)
        assertTrue(controller.isFollowing)
    }

    @Test
    fun `greeting while following - no nod, following untouched`() {
        controller.follow(7)
        controller.greet()
        assertEquals(listOf("follow:7"), head.calls)
    }

    @Test
    fun `where_is turns the head toward the place, clamped to 40 degrees, and back after 3-4 s`() {
        controller.lookToward(75.0)
        assertEquals(listOf("turn:40"), head.calls)
        assertTrue(HeadController.LOOK_HOLD_MS in 3_000..4_000)
        advance(HeadController.LOOK_HOLD_MS - 1)
        assertEquals(listOf("turn:40"), head.calls)
        advance(1)
        assertEquals(listOf("turn:40", "turn:0"), head.calls)
        controller.lookToward(-12.4)
        assertEquals("turn:-12", head.calls.last())
        advance(HeadController.LOOK_HOLD_MS)
        assertEquals("turn:0", head.calls.last())
        controller.lookToward(-100.0)
        assertEquals("turn:-40", head.calls.last())
    }

    @Test
    fun `where_is does not move a busy head`() {
        controller.follow(1)
        controller.lookToward(30.0)
        controller.release()
        controller.setNavigating(true)
        controller.lookToward(30.0)
        controller.setNavigating(false)
        controller.setCameraBusy(true)
        controller.lookToward(30.0)
        assertEquals(listOf("follow:1", "stop", "reset"), head.calls)
    }

    @Test
    fun `trip started while looking at the place - the head is not turned back, navigation owns it`() {
        controller.lookToward(30.0)
        controller.setNavigating(true)
        advance(HeadController.LOOK_HOLD_MS)
        assertEquals(listOf("turn:30"), head.calls)
    }

    @Test
    fun `where_is while the head is still turned is ignored, the way back still happens`() {
        controller.lookToward(30.0)
        advance(HeadController.LOOK_HOLD_MS) // вернулась
        controller.lookToward(20.0)
        advance(1_000)
        controller.lookToward(10.0) // голова ещё занята первым взглядом — игнор
        advance(HeadController.LOOK_HOLD_MS)
        assertEquals(listOf("turn:30", "turn:0", "turn:20", "turn:0"), head.calls)
    }

    @Test
    fun `visitor seen while looking at the place - following starts after the head is back`() {
        controller.lookToward(30.0)
        controller.follow(5)
        assertEquals(listOf("turn:30"), head.calls)
        advance(HeadController.LOOK_HOLD_MS)
        assertEquals("follow:5", head.calls.last())
        assertTrue(controller.isFollowing)
        assertEquals(1, head.calls.count { it == "follow:5" })
    }

    @Test
    fun `speaking tilt - not more often than every 9 s, only a free head`() {
        controller.onSpeaking()
        now += 4_000
        controller.onSpeaking() // рано
        now += 6_000
        controller.onSpeaking()
        now += 10_000
        controller.follow(2)
        controller.onSpeaking() // слежение
        controller.release()
        now += 10_000
        controller.lookToward(20.0)
        controller.onSpeaking() // голова смотрит на место
        assertEquals(listOf("tilt:6", "tilt:6", "follow:2", "stop", "reset", "turn:20"), head.calls)
        assertTrue(HeadController.GESTURE_INTERVAL_MS in 8_000..10_000)
    }

    @Test
    fun `a nod also counts as a gesture - no tilt right after it`() {
        controller.onEmotion(Emotion.HAPPY)
        now += 2_000
        controller.onSpeaking()
        now += 8_000
        controller.onSpeaking()
        assertEquals(listOf("nod:14", "tilt:6"), head.calls)
    }
}
