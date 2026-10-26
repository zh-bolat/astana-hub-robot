package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.Emotion
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.ScreenMode

/**
 * Состояние экрана ([MainUiState] из MainViewModel) → входы лица. Чистая функция — покрыта тестом
 * (docs/ui-redesign/INTEGRATION.md, таблица «событие → лицо»).
 */
object FaceInputMapper {

    fun activity(phase: Phase): FaceActivity = when (phase) {
        Phase.IDLE -> FaceActivity.IDLE
        Phase.LISTENING -> FaceActivity.LISTENING
        Phase.THINKING -> FaceActivity.THINKING
        Phase.LOOKING -> FaceActivity.LOOKING
        Phase.SPEAKING -> FaceActivity.SPEAKING
        Phase.NAVIGATING -> FaceActivity.TRAVELING
    }

    fun emotion(e: Emotion): FaceEmotion = when (e) {
        Emotion.NEUTRAL -> FaceEmotion.NEUTRAL
        Emotion.HAPPY -> FaceEmotion.HAPPY
        Emotion.THINKING -> FaceEmotion.THINKING
        Emotion.SURPRISED -> FaceEmotion.SURPRISED
        Emotion.SAD -> FaceEmotion.SAD
        Emotion.EXCITED -> FaceEmotion.EXCITED
    }

    /** Что сделать по тапу по лицу. */
    enum class Tap { WAKE, STOP, NONE }

    /**
     * Тап по лицу: спит — разбудить; говорит — перебить (тот же путь, что «Стоп» / `interrupt` в live), но не в
     * поездке — её останавливает только кнопка «Стоп» на острове. Остальное (моргнуть / удивиться) — M2.
     */
    fun tap(state: MainUiState): Tap = when {
        state.mode == ScreenMode.SLEEP -> Tap.WAKE
        state.phase == Phase.SPEAKING && state.guidingTo == null -> Tap.STOP
        else -> Tap.NONE
    }

    fun apply(state: MainUiState, inputs: FaceInputs) {
        inputs.awake = state.mode != ScreenMode.SLEEP
        inputs.activity = activity(state.phase)
        inputs.emotion = emotion(state.emotion)
        // Промежуточный текст посетителя — он говорит прямо сейчас (пока робот не занят ответом).
        inputs.userSpeaking = !state.partialQuestion.isNullOrBlank() &&
            (state.phase == Phase.IDLE || state.phase == Phase.LISTENING)
    }
}
