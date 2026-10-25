package kz.astanahub.robot.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Последнее падение приложения на диске: на роботе нет ADB и logcat, поэтому стек показывается на экране
 * («Техническая пауза» и лог диагностики). Хранятся два файла: последнее и предыдущее падение.
 * Без персональных данных — только исключение и стек.
 */
object CrashReport {
    const val DIR = "crash"
    const val LAST = "last_crash.txt"
    const val PREVIOUS = "prev_crash.txt"
    private const val MAX_CHARS = 16_000

    fun save(filesDir: File, thread: Thread, error: Throwable, buildLabel: String = "", now: Date = Date()) {
        val dir = File(filesDir, DIR).apply { mkdirs() }
        val last = File(dir, LAST)
        if (last.exists()) last.renameTo(File(dir, PREVIOUS))
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        last.writeText("$time  поток=${thread.name}  сборка=$buildLabel\n$stack".take(MAX_CHARS))
    }

    fun load(filesDir: File): String? =
        File(File(filesDir, DIR), LAST).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(filesDir: File) {
        File(File(filesDir, DIR), LAST).delete()
        File(File(filesDir, DIR), PREVIOUS).delete()
    }

    /** Для экрана: первые [lines] строк — заголовок, исключение и начало стека. */
    fun head(report: String, lines: Int = 30): String =
        report.lineSequence().take(lines).joinToString("\n")
}
