package kz.astanahub.robot.ui

/**
 * Целевая поза глаз (в единицах прототипа, лицо = 280): масштаб, сдвиг взгляда, «говорит» (плоский низ),
 * [smile] — глаза-полумесяцы (радость), [lid] — опущенные внешние веки (грусть), [bounce] — подпрыгивание
 * (восторг), [breath] — амплитуда «дыхания» шара, [closed] — глаза закрыты (сон).
 */
data class FacePose(
    val sx: Float = 1f,
    val sy: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val speak: Float = 0f,
    val smile: Float = 0f,
    val lid: Float = 0f,
    val bounce: Float = 0f,
    val breath: Float = 0f,
    val closed: Float = 0f,
)

/** Выражение = фаза разговора (слушает/думает/говорит/ведёт) + эмоция + сон. Чистая функция — для тестов. */
object FacePoses {
    private val THINKING_POSE = FacePose(sx = 0.92f, sy = 0.92f, dx = 12f, dy = -28f)

    fun target(phase: Phase, emotion: Emotion, sleeping: Boolean): FacePose {
        if (sleeping) return FacePose(sx = 1.05f, dy = 10f, breath = 0.03f, closed = 1f)
        return when (phase) {
            Phase.LISTENING -> FacePose(sx = 1.18f, sy = 1.22f)
            Phase.THINKING -> THINKING_POSE
            Phase.LOOKING -> FacePose(sx = 1.15f, sy = 1.15f, dy = -6f)
            Phase.NAVIGATING -> FacePose(dx = 30f).withEmotion(emotion, speaking = false)
            Phase.SPEAKING -> FacePose(speak = 1f).withEmotion(emotion, speaking = true)
            Phase.IDLE -> FacePose(breath = 0.015f).withEmotion(emotion, speaking = false)
        }
    }

    private fun FacePose.withEmotion(emotion: Emotion, speaking: Boolean): FacePose = when (emotion) {
        Emotion.NEUTRAL -> this
        Emotion.HAPPY -> copy(smile = 0.75f, sy = sy * 0.92f)
        Emotion.EXCITED -> copy(sx = 1.1f, sy = 1.1f, smile = 0.5f, bounce = 1f)
        Emotion.SURPRISED -> copy(sx = 1.2f, sy = 1.3f, speak = if (speaking) 0.3f else 0f)
        Emotion.SAD -> copy(lid = 0.6f, dy = dy + 8f, sy = sy * 0.9f)
        Emotion.THINKING -> if (speaking) copy(dx = 10f, dy = -14f) else THINKING_POSE.copy(breath = breath)
    }
}
