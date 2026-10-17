package kz.astanahub.robot.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kz.astanahub.robot.security.DiagPinGuard

/**
 * Экран ввода PIN диагностики: цифровое маскированное поле, ошибки и блокировка.
 * Служебный — только по-русски. Сам закрывается через минуту без ввода.
 */
object DiagPinDialog {
    private const val AUTO_DISMISS_MILLIS = 60_000L

    fun show(activity: Activity, guard: DiagPinGuard, onUnlocked: () -> Unit) {
        val pad = dp(activity, 24)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            isSingleLine = true
            hint = "PIN"
        }
        val status = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, pad / 2, 0, 0)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(status)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Диагностика: введите PIN")
            .setView(content)
            .setPositiveButton("Открыть", null)
            .setNegativeButton("Отмена", null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        val autoDismiss = Runnable { if (dialog.isShowing) dialog.dismiss() }

        fun showLocked(remainingMillis: Long) {
            val minutes = (remainingMillis + 59_999) / 60_000
            status.text = "Слишком много попыток. Попробуйте через $minutes мин."
            input.isEnabled = false
        }

        dialog.setOnShowListener {
            val lock = guard.lockRemainingMillis()
            if (lock > 0) showLocked(lock)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                handler.removeCallbacks(autoDismiss)
                handler.postDelayed(autoDismiss, AUTO_DISMISS_MILLIS)
                val pin = input.text?.toString().orEmpty()
                input.text = null
                when (val r = guard.check(pin)) {
                    DiagPinGuard.Result.Ok -> {
                        dialog.dismiss()
                        onUnlocked()
                    }
                    is DiagPinGuard.Result.Wrong -> status.text = "Неверный PIN. Осталось попыток: ${r.attemptsLeft}"
                    is DiagPinGuard.Result.Locked -> showLocked(r.remainingMillis)
                }
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(autoDismiss) }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MILLIS)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
