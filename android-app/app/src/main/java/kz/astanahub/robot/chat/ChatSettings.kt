package kz.astanahub.robot.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Режим разговора: умолчание — `CHAT_MODE` из secrets.properties (agent), переопределение — экран диагностики. */
class ChatSettings(context: Context, private val default: ChatMode) {
    private val prefs = context.applicationContext.getSharedPreferences("chat", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(ChatMode.fromCode(prefs.getString(KEY_MODE, null)) ?: default)
    val mode: StateFlow<ChatMode> = _mode

    fun set(mode: ChatMode) {
        prefs.edit().putString(KEY_MODE, mode.code).apply()
        _mode.value = mode
    }

    fun reset() {
        prefs.edit().remove(KEY_MODE).apply()
        _mode.value = default
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
