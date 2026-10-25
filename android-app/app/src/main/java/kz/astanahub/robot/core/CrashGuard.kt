package kz.astanahub.robot.core

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import kotlin.system.exitProcess
import timber.log.Timber

/**
 * Глобальный обработчик падений для киоска: вместо системного диалога «приложение остановлено» —
 * перезапуск MainActivity через AlarmManager (не чаще [CrashLoopPolicy.MAX_RESTARTS] раз за 5 минут),
 * дальше — экран «техническая пауза» на [PAUSE_MILLIS] без попыток поднять SDK робота.
 * Время — elapsedRealtime (переживает смерть процесса, не зависит от часов).
 * ПРОВЕРИТЬ НА РОБОТЕ: разрешает ли прошивка старт активности из PendingIntent будильника (ограничения
 * фонового запуска Android 10+); при необходимости — назначить приложение «Startup program».
 */
class CrashGuard(
    context: Context,
    private val mainActivity: Class<out Activity>,
    private val pauseActivity: Class<out Activity>,
    private val buildLabel: String = "",
) : Thread.UncaughtExceptionHandler {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Стек последнего падения (см. [CrashReport]) — для экрана паузы и лога диагностики. */
    fun lastCrash(): String? = runCatching { CrashReport.load(app.filesDir) }.getOrNull()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /** Сейчас «техническая пауза»: SDK и сеть не поднимаем, показываем экран паузы. */
    fun inPause(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val until = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        // После перезагрузки elapsedRealtime начинается с нуля — старая отметка недействительна.
        return until > now && until - now <= PAUSE_MILLIS
    }

    fun pauseRemainingMillis(now: Long = SystemClock.elapsedRealtime()): Long =
        (prefs.getLong(KEY_PAUSED_UNTIL, 0L) - now).coerceIn(0L, PAUSE_MILLIS)

    /** Конец паузы: очистить историю и перезапустить процесс с главным экраном. */
    fun leavePause() {
        prefs.edit().remove(KEY_PAUSED_UNTIL).remove(KEY_HISTORY).commit()
        scheduleStart(mainActivity)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            Timber.e(error, "uncaught exception in %s", thread.name)
            // На роботе нет logcat — стек на диск, покажем на экране паузы и в диагностике.
            runCatching { CrashReport.save(app.filesDir, thread, error, buildLabel) }
            val now = SystemClock.elapsedRealtime()
            if (inPause(now)) return // уже на паузе — не крутим перезапуски
            val history = prefs.getString(KEY_HISTORY, "").orEmpty()
                .split(',').mapNotNull { it.trim().toLongOrNull() }
            val (decision, recent) = CrashLoopPolicy.onCrash(history, now)
            val edit = prefs.edit().putString(KEY_HISTORY, recent.joinToString(","))
            if (decision == CrashLoopPolicy.Decision.PAUSE) edit.putLong(KEY_PAUSED_UNTIL, now + PAUSE_MILLIS)
            edit.commit()
            Timber.e("crash #%d in window → %s", recent.size, decision)
            scheduleStart(if (decision == CrashLoopPolicy.Decision.RESTART) mainActivity else pauseActivity)
        } catch (_: Throwable) {
            // Обработчик падений сам падать не должен.
        } finally {
            Process.killProcess(Process.myPid())
            exitProcess(EXIT_CODE)
        }
    }

    private fun scheduleStart(activity: Class<out Activity>) {
        val intent = Intent(app, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            app, REQUEST_CODE, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS, pending)
    }

    companion object {
        const val PAUSE_MILLIS = 5 * 60_000L
        private const val RESTART_DELAY_MILLIS = 1_500L
        private const val REQUEST_CODE = 7301
        private const val EXIT_CODE = 10
        private const val PREFS = "crash_guard"
        private const val KEY_HISTORY = "history"
        private const val KEY_PAUSED_UNTIL = "paused_until"
    }
}
