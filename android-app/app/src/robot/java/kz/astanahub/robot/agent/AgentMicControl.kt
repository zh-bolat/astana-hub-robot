package kz.astanahub.robot.agent

import com.ainirobot.agent.AgentCore
import kz.astanahub.robot.voice.BuiltinMicControl
import timber.log.Timber

/**
 * Выключение встроенного прослушивания AgentOS на время серверного ASR.
 * В AgentOS SDK v0.4.12 из подходящего есть только AgentCore.isMicrophoneMuted (раздел 4.1)
 * и isEnableVoiceBar (4.5); отдельного «pause ASR» нет (проверено javap по AgentCore).
 * ПРОВЕРИТЬ НА РОБОТЕ: после mute получает ли наш AudioRecord звук (или микрофон держит системный сервис).
 */
object AgentMicControl : BuiltinMicControl {
    override fun setBuiltinListening(enabled: Boolean) {
        runCatching {
            AgentCore.isMicrophoneMuted = !enabled
            AgentCore.isEnableVoiceBar = enabled
        }.onSuccess {
            Timber.i("AgentOS mic muted=%s voiceBar=%s", !enabled, enabled)
        }.onFailure { Timber.e(it, "AgentOS mic control failed") }
    }
}
