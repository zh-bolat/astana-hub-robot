package kz.astanahub.robot.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType
import kz.astanahub.robot.ui.face.pressToClick

/**
 * Нажимаемая поверхность: scale 0.97 **сразу на touch-down** (пружина Press), действие — на отпускании внутри.
 * На экране 2 касание не забирается ([tapUnlessDragged]): свайп экрана и прокрутка могут начаться прямо с плитки или
 * кнопки, нажатие тогда отменяется (ревью M2-r2, M8). [takeTouch] = true — кнопка поверх жестов лица: касание
 * забирается сразу ([pressToClick]), жест лица под ней не начинается (ревью M2-r1, B1). Выключена — приглушена и
 * касаний не берёт.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    takeTouch: Boolean = false,
    /** Своё состояние нажатия (кто-то ещё показывает отклик — мини-лицо в шапке); null — внутреннее. */
    pressed: MutableState<Boolean>? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val own = remember { mutableStateOf(false) }
    val pressed = pressed ?: own
    val scale by animateFloatAsState(if (pressed.value) HubMotion.PRESS_SCALE else 1f, HubMotion.Press.compose(), label = "press")
    val dim by animateFloatAsState(if (enabled) 1f else DISABLED_ALPHA, HubMotion.Default.compose(), label = "enabled")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            }
            // Касание — ДО modifier вызывающего: его отступы (padding пилюли, поля строки, плитки) внутри зоны
            // касания, нажимается вся видимая кнопка (ревью M3-r1, B1). Размер узла задают size/height из modifier.
            .then(
                when {
                    !enabled -> Modifier
                    takeTouch -> Modifier.pressToClick(pressed, onClick)
                    else -> Modifier.tapUnlessDragged(pressed, onClick)
                },
            )
            .then(modifier),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Нажатие, которое уступает жестам: `down` не забирает, палец ушёл дальше порога касания или касание забрал свайп /
 * прокрутка — нажатие отменяется без действия. Действие — на отпускании внутри.
 */
fun Modifier.tapUnlessDragged(pressed: MutableState<Boolean>, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            pressed.value = true
            val slop = viewConfiguration.touchSlop
            var released: PointerInputChange? = null
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        released = change
                        break
                    }
                    if ((change.position - down.position).getDistance() > slop) break
                }
            } finally {
                pressed.value = false
            }
            val up = released ?: return@awaitEachGesture
            val inside = up.position.x in 0f..size.width.toFloat() && up.position.y in 0f..size.height.toFloat()
            if (inside) {
                up.consume()
                click()
            }
        }
    }
}

/** Монохромный значок (res/drawable/ic_m_*.xml — белая линия; [tint] — цвет). */
@Composable
fun Glyph(@DrawableRes res: Int, size: Dp, tint: Color = HubColors.Label, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * Кнопка-пилюля: [primary] — белая с чёрным текстом (главное действие), иначе — материал (белый 10 %) с кромкой.
 * Высота 80 dp (зона касания ≥ 44 dp с запасом для киоска).
 */
@Composable
fun HubPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes glyph: Int? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(percent = 50)
    val fg = if (primary) HubColors.Black else HubColors.Label
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(PILL_HEIGHT)
            .then(
                if (primary) Modifier.background(HubColors.White, shape)
                else Modifier.background(HubColors.Fill, shape).border(1.dp, HubColors.Hairline, shape),
            )
            .padding(horizontal = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Glyph(glyph, 30.dp, fg)
                Spacer(Modifier.width(14.dp))
            }
            BasicText(label, style = HubType.Callout.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Круглая кнопка со значком (закрыть, назад, отправить): 72 dp, материал с кромкой. */
@Composable
fun CircleButton(@DrawableRes glyph: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(CIRCLE_SIZE)
            .background(HubColors.Fill, CircleShape)
            .border(1.dp, HubColors.Hairline, CircleShape),
    ) {
        Glyph(glyph, 32.dp)
    }
}

/**
 * Переключатель языка RU / KZ / EN (сегменты): белая «капля» под выбранным переезжает пружиной Snappy, подписи
 * меняют цвет вместе с ней. Сегмент 88 × 64 dp.
 */
@Composable
fun LanguageSwitch(selected: Lang, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier) {
    val items = Tiles.languages
    val index = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val thumb by animateDpAsState(SEGMENT_WIDTH * index, HubMotion.Snappy.compose(), label = "langThumb")
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .height(SEGMENT_HEIGHT + 12.dp)
            .background(HubColors.Fill, shape)
            .border(1.dp, HubColors.Hairline, shape)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset(x = thumb)
                .width(SEGMENT_WIDTH)
                .fillMaxHeight()
                .background(HubColors.White, shape),
        )
        Row {
            items.forEach { (lang, label) ->
                val color by animateColorAsState(
                    if (lang == selected) HubColors.Black else HubColors.LabelSecondary,
                    HubMotion.Snappy.compose(),
                    label = "langLabel",
                )
                Pressable(
                    onClick = { onSelect(lang) },
                    modifier = Modifier
                        .width(SEGMENT_WIDTH)
                        .fillMaxHeight(),
                ) {
                    BasicText(label, style = HubType.Callout.copy(color = color), maxLines = 1)
                }
            }
        }
    }
}

/** Плашка состояния в шапке («Нет связи», «Микрофон выключен»): контур, точка, без цвета — формой. */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .height(52.dp)
            .border(1.5.dp, HubColors.LabelSecondary, shape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(HubColors.White, CircleShape))
        Spacer(Modifier.width(12.dp))
        BasicText(text, style = HubType.Caption.copy(color = HubColors.Label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Скрытый жест оператора: удержание [millis] → [onHold] (PIN → диагностика). Касание не забирает — обычные нажатия
 * и прокрутка под ним работают как прежде.
 */
fun Modifier.holdToActivate(enabled: Boolean, millis: Long, onHold: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val hold by rememberUpdatedState(onHold)
        pointerInput(millis) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var released = false
                withTimeoutOrNull(millis) {
                    waitForUpOrCancellation()
                    released = true
                }
                if (!released) hold()
            }
        }
    }

/**
 * Плавное появление элемента при первом показе: прозрачность и подъём на 16 dp пружиной Default; [order] — небольшая
 * задержка по порядку (лесенка 40 мс), чтобы набор плиток проявлялся волной, а не разом.
 */
fun Modifier.appear(order: Int = 0, reducedMotion: Boolean = false): Modifier = composed {
    val a = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (order > 0) delay(order * APPEAR_STEP_MS)
        a.animateTo(1f, HubMotion.Default.compose())
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 16.dp.toPx()
    }
}

val PILL_HEIGHT = 80.dp
val CIRCLE_SIZE = 72.dp
private val SEGMENT_WIDTH = 88.dp
private val SEGMENT_HEIGHT = 64.dp
private const val DISABLED_ALPHA = 0.4f
private const val APPEAR_STEP_MS = 40L
