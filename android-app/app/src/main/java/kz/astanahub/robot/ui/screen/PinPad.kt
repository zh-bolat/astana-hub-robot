package kz.astanahub.robot.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/**
 * Ввод PIN диагностики в виде панели выхода прототипа (белая карточка, точки, клавиши) — логика наша: цифры,
 * [kz.astanahub.robot.security.DiagPinGuard] проверяет, после неверных попыток — блокировка. Состояние пишет MainActivity.
 * Служебный экран — по-русски.
 */
@Stable
class PinPadState {
    var visible by mutableStateOf(false)
    var pin by mutableStateOf("")
    var message by mutableStateOf("")
    var locked by mutableStateOf(false)
}

interface PinPadActions {
    fun onPinDigit(d: Char)
    fun onPinErase()
    fun onPinSubmit()
    fun onPinCancel()
}

@Composable
fun PinPad(state: PinPadState, actions: PinPadActions) {
    AnimatedVisibility(state.visible, enter = fadeIn(HubMotion.Default.compose()), exit = fadeOut(HubMotion.Snappy.compose())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(HubColors.Scrim)
                    .pointerInput(Unit) { detectTapGestures(onTap = { actions.onPinCancel() }) },
            )
            DialogCard {
                BasicText("Диагностика", style = HubType.Title.copy(color = HubColors.Navy, textAlign = TextAlign.Center))
                Spacer(Modifier.height(10.dp))
                BasicText("Только для сотрудников. Введите PIN.", style = HubType.Body.copy(color = HubColors.Muted, textAlign = TextAlign.Center))
                Spacer(Modifier.height(18.dp))
                BasicText(
                    "●".repeat(state.pin.length).ifEmpty { " " },
                    style = HubType.Title.copy(color = HubColors.Navy, fontSize = 40.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 52.dp),
                )
                BasicText(
                    state.message.ifEmpty { " " },
                    style = HubType.Callout.copy(color = Color(0xFFC62828), textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    "1234567890".forEach { d -> Key(d.toString(), enabled = !state.locked) { actions.onPinDigit(d) } }
                    Key("⌫", width = 120.dp, enabled = !state.locked && state.pin.isNotEmpty()) { actions.onPinErase() }
                }
                Spacer(Modifier.height(30.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HubPill("Отмена", actions::onPinCancel, primary = false)
                    HubPill("Открыть", actions::onPinSubmit, enabled = !state.locked && state.pin.length >= MIN_PIN)
                }
            }
        }
    }
}

/** Клавиша прототипа (`.pad .btn`): 76 × 72 dp, белая с кромкой, цифра 30 sp. */
@Composable
private fun Key(label: String, width: androidx.compose.ui.unit.Dp = 76.dp, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        takeTouch = true,
        modifier = Modifier
            .size(width, 72.dp)
            .softShadow(shape, 2.dp, 0.06f)
            .background(Color.White, shape)
            .border(1.dp, HubColors.Border, shape),
    ) {
        BasicText(label, style = HubType.ButtonPrimary.copy(color = HubColors.ButtonText))
    }
}

/** Короче — кнопка «Открыть» неактивна (меньше 4 цифр PIN не бывает). */
const val MIN_PIN = 4
