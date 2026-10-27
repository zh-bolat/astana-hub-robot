package kz.astanahub.robot.ui.screen

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.astanahub.robot.ui.design.HubType

/**
 * Подсказка свайпа на правом краю экрана лица (прототип v9 `.swipe-bar`: «‹‹‹», 40 / 700, белые α .3; каждый знак
 * раз в 2,4 с вспыхивает до .9 и сдвигается на 8 влево, задержки .3 / .15 / 0 с, ease-in-out между ключами).
 */
@Composable
fun SwipeChevrons(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "chev")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(CHEV_PERIOD_MS), RepeatMode.Restart), label = "chevP")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
        for (i in 0 until 3) {
            val delay = CHEV_DELAYS[i]
            BasicText(
                "‹",
                style = HubType.Title.copy(color = Color.White, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.graphicsLayer {
                    // Ключи 0 / 60 / 100 % — .25, на 30 % — .9 и −8 по x (прототип `@keyframes chev`).
                    val q = ((p - delay) % 1f + 1f) % 1f
                    val k = when {
                        q < 0.3f -> EASE.transform(q / 0.3f)
                        q < 0.6f -> 1f - EASE.transform((q - 0.3f) / 0.3f)
                        else -> 0f
                    }
                    alpha = 0.3f * (0.25f + 0.65f * k)
                    translationX = -8.dp.toPx() * k
                },
            )
        }
    }
}

private const val CHEV_PERIOD_MS = 2_400

/** Задержки знаков слева направо (доли периода): .3 / .15 / 0 с. */
private val CHEV_DELAYS = floatArrayOf(0.3f / 2.4f, 0.15f / 2.4f, 0f)

private val EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
