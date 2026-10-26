package kz.astanahub.robot.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDirectorTest {

    private val inputs = FaceInputs()
    private val director = FaceDirector()
    private var now = 10_000L

    private fun tick(ms: Long = 16, voice: Boolean = false): FaceMood {
        now += ms
        director.update(inputs, voice, now)
        return director.mood
    }

    /** Прогнать [ms] миллисекунд кадрами по 16 мс. */
    private fun advance(ms: Long, voice: Boolean = false): FaceMood {
        var left = ms
        while (left > 0) {
            tick(minOf(16, left), voice)
            left -= 16
        }
        return director.mood
    }

    @Test
    fun `activities map to moods`() {
        val expected = mapOf(
            FaceActivity.IDLE to FaceMood.IDLE,
            FaceActivity.LISTENING to FaceMood.IDLE, // слушает «вообще», но никто не говорит — покой
            FaceActivity.THINKING to FaceMood.THINKING,
            FaceActivity.LOOKING to FaceMood.LOOKING,
            FaceActivity.SPEAKING to FaceMood.SPEAKING,
            FaceActivity.TRAVELING to FaceMood.TRAVEL,
        )
        for ((activity, mood) in expected) {
            inputs.activity = activity
            assertEquals("$activity", mood, tick())
        }
    }

    @Test
    fun `visitor partial text means listening`() {
        inputs.activity = FaceActivity.LISTENING
        inputs.userSpeaking = true
        assertEquals(FaceMood.LISTENING, tick())
        inputs.userSpeaking = false
        assertEquals(FaceMood.IDLE, tick())
    }

    @Test
    fun `vad voice turns listening on at once and holds it over short pauses`() {
        inputs.activity = FaceActivity.IDLE
        assertEquals(FaceMood.LISTENING, tick(voice = true))
        // Пауза между словами короче удержания — всё ещё слушает.
        assertEquals(FaceMood.LISTENING, advance(400, voice = false))
        assertEquals(FaceMood.LISTENING, tick(voice = true))
        // Замолчал дольше удержания — покой.
        assertEquals(FaceMood.IDLE, advance(600, voice = false))
    }

    @Test
    fun `no vad - no listening, whatever the room loudness`() {
        // Громкость в директор не входит вовсе: только VAD и текст посетителя.
        inputs.activity = FaceActivity.LISTENING
        assertEquals(FaceMood.IDLE, advance(10_000, voice = false))
    }

    @Test
    fun `falling asleep yawns first then sleeps`() {
        tick()
        inputs.awake = false
        assertEquals(FaceMood.YAWN, tick())
        assertEquals(FaceMood.YAWN, advance(FaceDirector.YAWN_MS - 100))
        assertEquals(FaceMood.SLEEP, advance(200))
    }

    @Test
    fun `starting asleep skips the yawn`() {
        inputs.awake = false
        assertEquals(FaceMood.SLEEP, tick())
    }

    @Test
    fun `waking up is perky for a moment then follows activity`() {
        inputs.awake = false
        tick()
        advance(5_000)
        inputs.awake = true
        inputs.activity = FaceActivity.SPEAKING
        assertEquals(FaceMood.WAKE, tick())
        assertEquals(FaceMood.SPEAKING, advance(FaceDirector.WAKE_MS + 20))
    }

    @Test
    fun `reaction shows and expires`() {
        inputs.activity = FaceActivity.SPEAKING
        tick()
        director.cue(FaceCue.ERROR, now)
        assertEquals(FaceMood.ERROR, tick())
        assertEquals(FaceMood.ERROR, advance(2_400))
        assertEquals(FaceMood.SPEAKING, advance(300))
    }

    @Test
    fun `every cue has a reaction mood`() {
        for (c in FaceCue.entries) {
            director.cue(c, now)
            val mood = tick()
            assertEquals(c.name, FaceDirector.REACTIONS.getValue(c).first, mood)
        }
    }

    @Test
    fun `new activity cuts a reaction short`() {
        inputs.activity = FaceActivity.IDLE
        tick()
        director.cue(FaceCue.ARRIVED, now)
        assertEquals(FaceMood.HAPPY, tick())
        inputs.activity = FaceActivity.THINKING
        assertEquals(FaceMood.THINKING, tick())
    }

    @Test
    fun `visitor starting to talk cuts a reaction short`() {
        director.cue(FaceCue.CONFUSED, now)
        assertEquals(FaceMood.CONFUSED, tick())
        inputs.userSpeaking = true
        assertEquals(FaceMood.LISTENING, tick())
    }

    @Test
    fun `speaking does not cut a reaction - error face talks`() {
        inputs.activity = FaceActivity.THINKING
        tick()
        director.cue(FaceCue.ERROR, now)
        inputs.activity = FaceActivity.SPEAKING
        assertEquals(FaceMood.ERROR, tick())
    }

    @Test
    fun `petting wins over activity and lingers a little`() {
        inputs.activity = FaceActivity.SPEAKING
        inputs.petting = true
        assertEquals(FaceMood.PETTED, tick())
        inputs.petting = false
        assertEquals(FaceMood.PETTED, advance(FaceDirector.PETTING_TAIL_MS - 50))
        assertEquals(FaceMood.SPEAKING, advance(100))
    }

    @Test
    fun `sleep wins over everything except debug override`() {
        tick()
        inputs.awake = false
        inputs.petting = true
        director.cue(FaceCue.ERROR, now)
        assertEquals(FaceMood.YAWN, tick())
        director.forcedMood = FaceMood.HAPPY
        assertEquals(FaceMood.HAPPY, tick())
        director.forcedMood = null
        assertEquals(FaceMood.YAWN, tick())
    }

    @Test
    fun `mood change is reported once`() {
        inputs.activity = FaceActivity.IDLE
        tick()
        inputs.activity = FaceActivity.THINKING
        tick()
        assertTrue(director.moodChanged)
        tick()
        assertFalse(director.moodChanged)
    }

    @Test
    fun `emotion passes through while awake and is neutral asleep`() {
        inputs.emotion = FaceEmotion.HAPPY
        tick()
        assertEquals(FaceEmotion.HAPPY, director.emotion)
        assertTrue(director.emotionChanged)
        inputs.awake = false
        tick()
        assertEquals(FaceEmotion.NEUTRAL, director.emotion)
        director.forcedEmotion = FaceEmotion.SAD
        tick()
        assertEquals(FaceEmotion.SAD, director.emotion)
    }

    @Test
    fun `emotion overlay weight depends on mood`() {
        assertEquals(1f, Expressions.overlayWeight(FaceMood.IDLE), 0f)
        assertTrue(Expressions.overlayWeight(FaceMood.SPEAKING) in 0.5f..1f)
        assertEquals(0f, Expressions.overlayWeight(FaceMood.THINKING), 0f)
        assertEquals(0f, Expressions.overlayWeight(FaceMood.SLEEP), 0f)
        assertEquals(0f, Expressions.overlayWeight(FaceMood.ERROR), 0f)
    }
}
