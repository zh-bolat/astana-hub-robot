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
        override fun nod() { calls += "nod" }
    }

    private var now = 0L
    private val head = FakeHead()
    private val controller = HeadController(head, clock = { now }, nodIntervalMillis = 20_000)

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
    fun `nod on joyful emotions, rarely and only when the head is free`() {
        controller.onEmotion(Emotion.HAPPY)
        now += 5_000
        controller.onEmotion(Emotion.EXCITED) // слишком часто
        now += 20_000
        controller.onEmotion(Emotion.SAD) // не кивает на грусть
        controller.follow(1)
        controller.onEmotion(Emotion.SURPRISED) // голова занята слежением
        controller.release()
        controller.setNavigating(true)
        controller.onEmotion(Emotion.HAPPY) // едем
        controller.setNavigating(false)
        controller.onEmotion(Emotion.HAPPY)
        assertEquals(listOf("nod", "follow:1", "stop", "reset", "nod"), head.calls)
    }
}
