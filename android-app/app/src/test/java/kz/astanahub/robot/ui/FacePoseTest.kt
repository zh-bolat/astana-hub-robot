package kz.astanahub.robot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacePoseTest {

    @Test
    fun `sleeping face has closed eyes and slow breathing whatever the phase`() {
        val p = FacePoses.target(Phase.SPEAKING, Emotion.EXCITED, sleeping = true)
        assertEquals(1f, p.closed)
        assertTrue(p.breath > 0f)
        assertEquals(0f, p.bounce)
    }

    @Test
    fun `phases win over emotions while listening and thinking`() {
        assertEquals(FacePoses.target(Phase.LISTENING, Emotion.NEUTRAL, false), FacePoses.target(Phase.LISTENING, Emotion.SAD, false))
        assertEquals(-28f, FacePoses.target(Phase.THINKING, Emotion.HAPPY, false).dy)
    }

    @Test
    fun `each emotion changes the idle face`() {
        val poses = Emotion.entries.map { FacePoses.target(Phase.IDLE, it, false) }
        assertEquals(Emotion.entries.size, poses.toSet().size)
        assertTrue(FacePoses.target(Phase.IDLE, Emotion.HAPPY, false).smile > 0f)
        assertTrue(FacePoses.target(Phase.IDLE, Emotion.SAD, false).lid > 0f)
        assertTrue(FacePoses.target(Phase.IDLE, Emotion.EXCITED, false).bounce > 0f)
        assertTrue(FacePoses.target(Phase.IDLE, Emotion.SURPRISED, false).sy > 1.2f)
        assertTrue(FacePoses.target(Phase.IDLE, Emotion.NEUTRAL, false).breath > 0f)
    }

    @Test
    fun `speaking keeps the emotion`() {
        val p = FacePoses.target(Phase.SPEAKING, Emotion.HAPPY, false)
        assertEquals(1f, p.speak)
        assertTrue(p.smile > 0f)
    }

    @Test
    fun `emotion names from the agent`() {
        assertEquals(Emotion.EXCITED, Emotion.fromName("Excited"))
        assertNull(Emotion.fromName("angry"))
        assertEquals(Emotion.HAPPY, MoodRules.forUtterance("Спасибо большое!"))
        assertEquals(Emotion.EXCITED, MoodRules.forUtterance("Сәлем"))
        assertNull(MoodRules.forUtterance("Где кафе?"))
    }
}
