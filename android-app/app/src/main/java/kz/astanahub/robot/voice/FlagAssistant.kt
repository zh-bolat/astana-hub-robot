package kz.astanahub.robot.voice

import timber.log.Timber

/** Флаги встроенного ассистента (AgentOS: AgentCore.isMicrophoneMuted / isEnableVoiceBar / isDisablePlan). */
interface AssistantFlags {
    var microphoneMuted: Boolean
    var voiceBar: Boolean
    var planDisabled: Boolean
}

/**
 * Ассистент на флагах: [takeOver] запоминает «как было» (один раз) и выставляет «молчи»: план LLM выключен,
 * микрофон — только если нужно встроенное распознавание; [release] возвращает запомненное.
 */
class FlagAssistant(private val flags: AssistantFlags, private val name: String = "assistant") : BuiltinAssistant {
    private data class Saved(val muted: Boolean, val voiceBar: Boolean, val planDisabled: Boolean)

    private var saved: Saved? = null

    @Synchronized
    override fun takeOver(listen: Boolean) {
        if (saved == null) saved = Saved(flags.microphoneMuted, flags.voiceBar, flags.planDisabled)
        flags.planDisabled = true
        flags.microphoneMuted = !listen
        flags.voiceBar = listen
        Timber.i("%s: plan disabled, mic muted=%s", name, !listen)
    }

    @Synchronized
    override fun release() {
        val s = saved ?: return
        flags.planDisabled = s.planDisabled
        flags.microphoneMuted = s.muted
        flags.voiceBar = s.voiceBar
        saved = null
        Timber.i("%s: restored (mic muted=%s, plan disabled=%s)", name, s.muted, s.planDisabled)
    }
}
