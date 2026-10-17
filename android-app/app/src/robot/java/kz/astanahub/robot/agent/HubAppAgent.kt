package kz.astanahub.robot.agent

import android.app.Application
import android.os.Bundle
import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import timber.log.Timber

/**
 * Единственный AppAgent приложения (AgentOS SDK v0.4.12, раздел 1.2.4).
 *
 * ПРОВЕРИТЬ НА РОБОТЕ (спайк 2.5): при `isDisablePlan = true` встроенный LLM AgentOS не отвечает сам,
 * а распознанный текст приходит в onASRResult — его обрабатывает наше приложение через backend.
 * Если окажется, что в этом режиме ASR не доставляется, переходим на Actions (шаг 4.1).
 */
object HubAppAgent {

    private const val PERSONA =
        "Ты — робот-помощник Astana Hub, международного технопарка IT-стартапов в Астане. " +
            "Ты вежливый, дружелюбный и отвечаешь коротко: 1–3 предложения."
    private const val OBJECTIVE =
        "Помогать посетителям Astana Hub: отвечать на вопросы о хабе, подсказывать, где что находится, " +
            "и провожать до нужного места."

    @Volatile private var agent: AppAgent? = null

    fun install(app: Application, voice: AgentVoiceGateway) {
        if (agent != null) return
        agent = object : AppAgent(app) {
            override fun onCreate() {
                setPersona(PERSONA)
                setObjective(OBJECTIVE)
                setOnTranscribeListener(voice.transcribeListener)
                setOnAgentStatusChangedListener(voice.statusListener)
            }

            override fun onExecuteAction(action: Action, params: Bundle?): Boolean {
                // Actions (ask_hub_info, where_is, guide_to, look_and_describe) — шаги 4.1 / 4.5.
                // Только имя действия: параметры могут содержать текст посетителя.
                Timber.i("onExecuteAction: %s params=%d", action.name, params?.size() ?: 0)
                return false
            }
        }
        // Свой роутинг и свой LLM на backend (см. шаг 2.5 / 4.1).
        AgentCore.isDisablePlan = true
    }
}
