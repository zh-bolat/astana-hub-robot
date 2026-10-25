package kz.astanahub.robot

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kz.astanahub.robot.core.CrashReport
import kz.astanahub.robot.ui.setOnHoldListener

/**
 * «Техническая пауза»: приложение упало больше 3 раз за 5 минут (см. CrashGuard).
 * SDK робота и сеть в этом режиме не поднимаются. Через 5 минут — сам перезапуск;
 * оператор может перезапустить раньше удержанием заголовка 3 с.
 */
class TechPauseActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val guard by lazy { (application as AstanaHubApp).crashGuard }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = TextView(this).apply {
            text = getString(R.string.tech_pause_title)
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@TechPauseActivity, R.color.hub_on_background))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.tech_pause_hint)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
            setTextColor(ContextCompat.getColor(this@TechPauseActivity, R.color.hub_muted))
        }
        // Стек последнего падения — для оператора (на роботе нет ADB, экран — единственный лог).
        val crash = TextView(this).apply {
            text = guard.lastCrash()?.let { CrashReport.head(it) } ?: ""
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 48, 0, 0)
            setTextColor(ContextCompat.getColor(this@TechPauseActivity, R.color.hub_muted))
            setTextIsSelectable(true)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@TechPauseActivity, R.color.hub_background))
                isFillViewport = true
                addView(
                    LinearLayout(this@TechPauseActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(64, 64, 64, 64)
                        addView(title)
                        addView(hint)
                        addView(crash)
                    },
                )
            },
        )
        title.setOnHoldListener(HOLD_MILLIS) { guard.leavePause() }
        onBackPressedDispatcher.addCallback(this) { }
        val left = guard.pauseRemainingMillis()
        handler.postDelayed({ guard.leavePause() }, left.coerceAtLeast(MIN_WAIT_MILLIS))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val HOLD_MILLIS = 3_000L
        const val MIN_WAIT_MILLIS = 5_000L
    }
}
