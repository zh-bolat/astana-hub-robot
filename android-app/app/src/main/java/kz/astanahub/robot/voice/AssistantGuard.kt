package kz.astanahub.robot.voice

import timber.log.Timber

/**
 * Встроенный ассистент робота (RobotOS SkillApi / AgentOS), который не должен звучать и отвечать,
 * пока наше приложение на переднем плане. Реализация — во flavor-е; на эмуляторе — заглушка.
 */
interface BuiltinAssistant {
    /**
     * Приложение на переднем плане: ассистент молчит (не планирует, не отвечает, не говорит).
     * [listen] — встроенное распознавание нужно (ASR=builtin выбран явно), иначе микрофон ассистента выключен.
     * Может вызываться повторно (идемпотентно); первое значение «как было» реализация запоминает сама.
     */
    fun takeOver(listen: Boolean)

    /** Ушли в фон: вернуть, как было до первого [takeOver]. */
    fun release()
}

/**
 * Глушит [assistant], пока приложение на переднем плане ([setForeground]), и отдаёт ему микрофон, только когда
 * нужно встроенное распознавание ([setBuiltinListening] — его зовёт RoutingVoiceGateway по настройке ASR).
 * В фоне — [BuiltinAssistant.release]. Потокобезопасно.
 */
class AssistantGuard(private val assistant: BuiltinAssistant) : BuiltinMicControl {
    private var foreground = false
    private var listen = false

    /** Что применено: null — ассистент отпущен (фон), иначе значение listen. */
    private var applied: Boolean? = null

    /**
     * Экран приложения виден / нет. [force] — применить заново, даже если ничего не поменялось (onResume: AgentOS
     * сам открывает микрофон, когда приложение выходит на передний план — перекрываем это после него).
     */
    @Synchronized
    fun setForeground(visible: Boolean, force: Boolean = false) {
        foreground = visible
        apply(force)
    }

    @Synchronized
    override fun setBuiltinListening(enabled: Boolean) {
        listen = enabled
        apply(force = false)
    }

    @Synchronized
    fun isTakenOver(): Boolean = applied != null

    private fun apply(force: Boolean) {
        if (foreground) {
            if (force || applied != listen) {
                Timber.i("builtin assistant: take over (listen=%s)", listen)
                runCatching { assistant.takeOver(listen) }.onFailure { Timber.e(it, "assistant takeOver") }
                applied = listen
            }
        } else if (applied != null) {
            Timber.i("builtin assistant: release")
            runCatching { assistant.release() }.onFailure { Timber.e(it, "assistant release") }
            applied = null
        }
    }
}
