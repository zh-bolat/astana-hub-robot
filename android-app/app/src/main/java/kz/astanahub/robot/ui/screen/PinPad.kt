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
            // Прототип `.exit-layer`: rgba(20, 40, 110, .30), страница под ним размыта (blur 10 — RobotScreens).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x4D14286E))
                    .pointerInput(Unit) { detectTapGestures(onTap = { actions.onPinCancel() }) },
            )
            DialogCard {
                // Прототип `.exit-main`: заголовок 52 / 700, пояснение 30, точки 44 (разрядка .3em), ошибка 26 красная,
                // клавиатура и кнопки — через 12.
                BasicText(state.texts.title, style = DIALOG_TITLE)
                // Пояснение ближе к заголовку: так строки и клавиши совпадают со снимком прототипа.
                Spacer(Modifier.height(4.dp))
                BasicText(state.texts.sub, style = DIALOG_SUB)
                Spacer(Modifier.height(12.dp))
                BasicText(
                    "●".repeat(state.length).ifEmpty { " " },
                    style = HubType.Title.copy(color = HubColors.Ink, fontSize = 44.sp, lineHeight = 60.sp, letterSpacing = 13.2.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 60.dp),
                )
                Spacer(Modifier.height(12.dp))
                BasicText(
                    state.message.ifEmpty { " " },
                    style = HubType.Callout.copy(color = Color(0xFFC62828), textAlign = TextAlign.Center),
                    modifier = Modifier.heightIn(min = 36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    KEY_ROWS.forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c -> Key(c.toString(), enabled = !state.locked) { actions.onPinKey(c) } }
                            if (i == KEY_ROWS.lastIndex) {
                                // Прототип: «⌫» неактивна только при блокировке.
                                Key("⌫", width = 120.dp, enabled = !state.locked) { actions.onPinErase() }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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

/** Клавиша прототипа (`.pad .btn`): 76 × 72 dp, скругление 20, белая с кромкой, знак 30 / 700. */
@Composable
private fun Key(label: String, width: Dp = 76.dp, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
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
        BasicText(label, style = HubType.ButtonPrimary.copy(color = HubColors.ButtonText, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** Клавиатура прототипа: цифры и латиница (как `buildPad`). */
private val KEY_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")

/** Заголовок и пояснение карточки-диалога прототипа (`.exit-card .big` 52 / 700 #111827, `.small` 30 #5B6475). */
val DIALOG_TITLE = HubType.Title.copy(
    color = HubColors.Ink,
    fontSize = 52.sp,
    lineHeight = 59.8.sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    textAlign = TextAlign.Center,
)
val DIALOG_SUB = HubType.Body.copy(color = HubColors.Muted, fontSize = 30.sp, lineHeight = 41.sp, textAlign = TextAlign.Center)
