package kz.astanahub.robot.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Зачем просим пароль сотрудника: диагностика (наша админка) или выход из приложения (крестик). */
enum class PinPurpose { DIAGNOSTICS, EXIT }

/** Подписи панели пароля (выход — на языке экрана, диагностика — по-русски). */
@Immutable
data class PinTexts(
    val title: String = "",
    val sub: String = "",
    val ok: String = "",
    val cancel: String = "",
)

/**
 * Панель пароля сотрудника в виде прототипа (`.exit-card.pin`): заголовок, пояснение, точки, клавиатура — цифры и
 * латинские буквы (пароль может быть с буквами, регистр не важен), «⌫»; кнопки: главная — «Остаться» / «Отмена»,
 * вторая — «Выйти» / «Открыть». Логика (проверка, общий счётчик попыток, блокировка, таймаут) — в MainActivity.
 */
@Stable
class PinPadState {
    var visible by mutableStateOf(false)
    var purpose by mutableStateOf(PinPurpose.DIAGNOSTICS)
    var texts by mutableStateOf(PinTexts())

    /** Сколько знаков набрано (сами знаки — в [kz.astanahub.robot.security.PinBuffer]). */
    var length by mutableStateOf(0)
    var message by mutableStateOf("")
    var locked by mutableStateOf(false)
}

interface PinPadActions {
    fun onPinKey(c: Char)
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
                BasicText(state.texts.title, style = HubType.Title.copy(color = HubColors.Navy, textAlign = TextAlign.Center))
                Spacer(Modifier.height(10.dp))
                BasicText(state.texts.sub, style = HubType.Body.copy(color = HubColors.Muted, textAlign = TextAlign.Center))
                Spacer(Modifier.height(14.dp))
                BasicText(
                    "●".repeat(state.length).ifEmpty { " " },
                    style = HubType.Title.copy(color = HubColors.Navy, fontSize = 36.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                BasicText(
                    state.message.ifEmpty { " " },
                    style = HubType.Callout.copy(color = Color(0xFFC62828), textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 36.dp),
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    KEY_ROWS.forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c -> Key(c.toString(), enabled = !state.locked) { actions.onPinKey(c) } }
                            if (i == KEY_ROWS.lastIndex) {
                                Key("⌫", width = 120.dp, enabled = !state.locked && state.length > 0) { actions.onPinErase() }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HubPill(state.texts.cancel, actions::onPinCancel, takeTouch = true)
                    HubPill(
                        state.texts.ok,
                        actions::onPinSubmit,
                        primary = false,
                        enabled = !state.locked && state.length >= kz.astanahub.robot.security.PinBuffer.MIN_LENGTH,
                        takeTouch = true,
                    )
                }
            }
        }
    }
}

/** Клавиша прототипа (`.pad .btn`): 76 × 72 dp, белая с кромкой, знак 30 sp. */
@Composable
private fun Key(label: String, width: Dp = 76.dp, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        takeTouch = true,
        repeatGuard = false,
        modifier = Modifier
            .size(width, 72.dp)
            .softShadow(shape, 2.dp, 0.06f)
            .background(Color.White, shape)
            .border(1.dp, HubColors.Border, shape),
    ) {
        BasicText(label, style = HubType.ButtonPrimary.copy(color = HubColors.ButtonText))
    }
}

/** Клавиатура прототипа: цифры и латиница (как `buildPad`). */
private val KEY_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
