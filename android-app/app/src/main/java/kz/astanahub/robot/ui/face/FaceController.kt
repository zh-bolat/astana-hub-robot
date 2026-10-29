package kz.astanahub.robot.ui.face

import kz.astanahub.robot.ui.MainUiState

/**
 * Лицо экрана 1: движок + рисовальщик + состояние экрана. Живёт в MainActivity; входы — из MainUiState
 * ([onState]) и реакций MainViewModel.faceCues ([onCue]), звук — из [kz.astanahub.robot.audio.AudioLevels].
 */
class FaceController(audio: FaceAudioSource, reducedMotion: Boolean) {
    val engine = FaceEngine(audio = audio).also { it.reducedMotion = reducedMotion }
    val renderer = FaceRenderer()
    val screen = FaceScreenState()

    fun onState(state: MainUiState) = FaceInputMapper.apply(state, engine.inputs)

    fun onCue(c: FaceCue) = engine.cue(c)

    /** Отладка: принудительное состояние / эмоция (null — снова по событиям). */
    fun force(mood: FaceMood?) {
        engine.director.forcedMood = mood
    }

    fun forceEmotion(emotion: FaceEmotion?) {
        engine.director.forcedEmotion = emotion
    }

    fun describe(): String =
        "mood=${engine.director.mood} emotion=${engine.director.emotion} activity=${engine.inputs.activity} " +
            "awake=${engine.inputs.awake} voice=${engine.director.voiceActive} talk=%.2f listen=%.2f".format(
                engine.pose.talk, engine.pose.listen,
            )
}
