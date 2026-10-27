package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.Emotion
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MainUiState (настоящее состояние приложения) → входы лица. */
class FaceInputMapperTest {

    private val inputs = FaceInputs()

    @Test
    fun `phases map to face activities`() {
        assertEquals(FaceActivity.IDLE, FaceInputMapper.activity(Phase.IDLE))
        assertEquals(FaceActivity.LISTENING, FaceInputMapper.activity(Phase.LISTENING))
        assertEquals(FaceActivity.THINKING, FaceInputMapper.activity(Phase.THINKING))
        assertEquals(FaceActivity.LOOKING, FaceInputMapper.activity(Phase.LOOKING))
        assertEquals(FaceActivity.SPEAKING, FaceInputMapper.activity(Phase.SPEAKING))
        assertEquals(FaceActivity.TRAVELING, FaceInputMapper.activity(Phase.NAVIGATING))
    }

    @Test
    fun `server emotions map one to one`() {
        for (e in Emotion.entries) {
            assertEquals(e.name, FaceInputMapper.emotion(e).name)
        }
    }

    @Test
    fun `asleep robot sleeps on the face and in the companion head`() {
        FaceInputMapper.apply(MainUiState(mode = ScreenMode.SLEEP, asleep = true), inputs)
        assertFalse(inputs.awake)
        // MF4: страницу открыли свайпом во сне — робот в голове спит дальше.
        FaceInputMapper.apply(MainUiState(mode = ScreenMode.MENU, asleep = true), inputs)
        assertFalse(inputs.awake)
        FaceInputMapper.apply(MainUiState(mode = ScreenMode.FACE), inputs)
        assertTrue(inputs.awake)
        FaceInputMapper.apply(MainUiState(mode = ScreenMode.MENU), inputs)
        assertTrue(inputs.awake)
    }

    @Test
    fun `partial transcript means the visitor is talking, but not while the robot is busy`() {
        FaceInputMapper.apply(MainUiState(phase = Phase.LISTENING, partialQuestion = "где кафе"), inputs)
        assertTrue(inputs.userSpeaking)
        FaceInputMapper.apply(MainUiState(phase = Phase.IDLE, partialQuestion = "где"), inputs)
        assertTrue(inputs.userSpeaking)
        FaceInputMapper.apply(MainUiState(phase = Phase.THINKING, partialQuestion = "где кафе"), inputs)
        assertFalse(inputs.userSpeaking)
        FaceInputMapper.apply(MainUiState(phase = Phase.LISTENING, partialQuestion = " "), inputs)
        assertFalse(inputs.userSpeaking)
    }

    @Test
    fun `full state is applied`() {
        FaceInputMapper.apply(MainUiState(phase = Phase.SPEAKING, emotion = Emotion.EXCITED, mode = ScreenMode.FACE), inputs)
        assertEquals(FaceActivity.SPEAKING, inputs.activity)
        assertEquals(FaceEmotion.EXCITED, inputs.emotion)
    }
}
