package kz.astanahub.robot.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/** Удержание пальца на [view] не меньше [holdMillis] → [onHold]. Отпустили/увели палец раньше — ничего. */
@SuppressLint("ClickableViewAccessibility")
fun View.setOnHoldListener(holdMillis: Long, onHold: () -> Unit) {
    val fire = Runnable { onHold() }
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.removeCallbacks(fire)
                v.postDelayed(fire, holdMillis)
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0 && event.y >= 0 && event.x <= v.width && event.y <= v.height
                if (!inside) v.removeCallbacks(fire)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(fire)
        }
        true
    }
}
