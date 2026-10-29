package kz.astanahub.robot.ui.face

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kz.astanahub.robot.audio.AudioLevels
import kz.astanahub.robot.presence.PresenceSimulator
import kz.astanahub.robot.ui.MainViewModel

/**
 * Отладка лица без робота и сервера — только debug-сборка flavor `emulator` (регистрирует MainActivity).
 * Команды по adb (полный список — docs/ui-redesign/DESIGN.md, «Отладка на эмуляторе»):
 *
 * ```
 * adb shell am broadcast -a kz.astanahub.robot.DEBUG_FACE -p kz.astanahub.robot --es cmd mood --es arg happy
 * ```
 *
 * - настоящие события приложения (идут обычным путём через MainViewModel и фейковый бэкенд):
 *   `ask <текст>`, `heard <текст>` (посетитель ещё говорит), `arrive`, `leave`, `sleep`, `wake` (как тап по спящему
 *   лицу), `dbltap` (как двойной тап — уснуть), `stop`, `look`,
 *   `menu`, `face`;
 * - звук: `mic on|off` (имитация голоса посетителя: VAD + громкость), `noise on|off` (гул зала без речи),
 *   `play on|off` (громкость речи робота);
 * - лицо напрямую: `mood <состояние|auto>`, `emotion <эмоция|auto>`, `cue <реакция>`;
 * - `state` — текущее состояние лица в logcat (тег FaceDebug).
 */
class FaceDebug(
    private val face: FaceController,
    private val viewModel: MainViewModel,
    private val levels: AudioLevels,
    private val presence: PresenceSimulator?,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase() ?: return
            val arg = intent.getStringExtra("arg")?.trim()
            val result = runCatching { handle(cmd, arg) }.getOrElse { "error: ${it.message}" }
            Log.i(TAG, "$cmd ${arg.orEmpty()} → $result")
        }
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun handle(cmd: String, arg: String?): String {
        when (cmd) {
            "mood" -> face.force(if (arg.isAuto()) null else FaceMood.fromName(arg) ?: return "unknown mood $arg")
            "emotion" -> face.forceEmotion(if (arg.isAuto()) null else FaceEmotion.fromName(arg) ?: return "unknown emotion $arg")
            "cue" -> face.onCue(FaceCue.fromName(arg) ?: return "unknown cue $arg")
            "mic" -> levels.debugMic = arg.isOn()
            "noise" -> levels.debugNoise = arg.isOn()
            "play" -> levels.debugPlayback = arg.isOn()
            "ask" -> viewModel.submit(arg ?: return "ask needs text")
            "heard" -> viewModel.debugPartial(arg ?: return "heard needs text")
            "arrive" -> presence?.arrive() ?: return "no presence simulator"
            "leave" -> presence?.leave() ?: return "no presence simulator"
            "sleep" -> viewModel.sleepNow()
            "wake" -> viewModel.wakeByTap()
            "dbltap" -> viewModel.sleepByTap()
            "stop" -> viewModel.stop()
            "look" -> viewModel.lookAround()
            "menu" -> viewModel.openMenu()
            "face" -> viewModel.showFace()
            "state" -> Unit
            else -> return "unknown command"
        }
        return face.describe()
    }

    private fun String?.isAuto() = this == null || equals("auto", ignoreCase = true) || equals("off", ignoreCase = true)

    private fun String?.isOn() = this != null && (equals("on", ignoreCase = true) || this == "1" || equals("true", ignoreCase = true))

    companion object {
        const val ACTION = "kz.astanahub.robot.DEBUG_FACE"
        const val TAG = "FaceDebug"
    }
}
