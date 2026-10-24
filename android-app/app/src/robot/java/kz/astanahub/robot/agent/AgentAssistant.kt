package kz.astanahub.robot.agent

import com.ainirobot.agent.AgentCore
import kz.astanahub.robot.voice.AssistantFlags
import kz.astanahub.robot.voice.FlagAssistant

/**
 * Флаги AgentOS SDK v0.4.12, которыми глушим встроенного ассистента, пока приложение на переднем плане:
 * AgentCore.isMicrophoneMuted (раздел 4.1), isEnableVoiceBar (4.5), isDisablePlan (4.12).
 * Отдельного «выключить ассистента» в SDK нет. Встроенный TTS (AgentCore.tts/ttsSync) приложение само не зовёт,
 * пока VOICE_BUILTIN_FALLBACK=false и TTS=server.
 * ПРОВЕРИТЬ НА РОБОТЕ: после mute получает ли наш AudioRecord звук; не говорит ли AgentOS сам при входе в приложение.
 */
object AgentCoreFlags : AssistantFlags {
    override var microphoneMuted: Boolean
        get() = AgentCore.isMicrophoneMuted
        set(value) { AgentCore.isMicrophoneMuted = value }

    override var voiceBar: Boolean
        get() = AgentCore.isEnableVoiceBar
        set(value) { AgentCore.isEnableVoiceBar = value }

    override var planDisabled: Boolean
        get() = AgentCore.isDisablePlan
        set(value) { AgentCore.isDisablePlan = value }
}

/** Встроенный ассистент AgentOS для [kz.astanahub.robot.voice.AssistantGuard]. */
val AgentAssistant = FlagAssistant(AgentCoreFlags, name = "AgentOS")
