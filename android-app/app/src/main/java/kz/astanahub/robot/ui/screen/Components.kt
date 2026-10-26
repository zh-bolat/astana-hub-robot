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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import kz.astanahub.robot.R
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
    /** Защита от двойного нажатия; клавиатура пароля — без неё (одинаковые знаки подряд). */
    repeatGuard: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val own = remember { mutableStateOf(false) }
    val pressed = pressed ?: own
    // Двойной / дрожащий тап не запускает действие дважды: повтор в течение [REPEAT_GUARD_MS] — без действия.
    val lastClick = remember { longArrayOf(Long.MIN_VALUE / 2) }
    val clickOnce: () -> Unit = {
        val now = android.os.SystemClock.uptimeMillis()
        if (!repeatGuard || now - lastClick[0] >= REPEAT_GUARD_MS) {
            lastClick[0] = now
            onClick()
        }
    }
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
                    takeTouch -> Modifier.pressToClick(pressed, clickOnce)
                    else -> Modifier.tapUnlessDragged(pressed, clickOnce)
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
fun Glyph(@DrawableRes res: Int, size: Dp, tint: Color = HubColors.Ink, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Вид кнопки прототипа: главная синяя, «Стоп» тёмно-синяя, вторичная — белая с кромкой. */
enum class PillKind { PRIMARY, STOP, SECONDARY }

/**
 * Кнопка-пилюля прототипа (`.btn`): главная — синяя (#1E4FFF) с тенью, высота 92 dp, текст 30 sp; «Стоп» — тёмно-синяя;
 * вторичная — белая с кромкой, скругление 22 dp, высота 84 dp, текст 28 sp. Отклик — сразу на касание.
 */
@Composable
fun HubPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes glyph: Int? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    kind: PillKind = if (primary) PillKind.PRIMARY else PillKind.SECONDARY,
    takeTouch: Boolean = false,
) {
    val (bg, fg) = when (kind) {
        PillKind.PRIMARY -> HubColors.Blue to Color.White
        PillKind.STOP -> HubColors.NavyButton to Color.White
        PillKind.SECONDARY -> Color.White to HubColors.ButtonText
    }
    val secondary = kind == PillKind.SECONDARY
    val shape = if (secondary) RoundedCornerShape(22.dp) else RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        takeTouch = takeTouch,
        modifier = modifier
            .height(if (secondary) SECONDARY_HEIGHT else PILL_HEIGHT)
            .then(
                if (secondary) Modifier.softShadow(shape, 2.dp, 0.06f).background(bg, shape).border(1.dp, HubColors.Border, shape)
                else Modifier.softShadow(shape, 10.dp, if (kind == PillKind.STOP) 0.25f else 0.28f, if (kind == PillKind.STOP) HubColors.NavyButton else HubColors.Blue)
                    .background(bg, shape),
            )
            .padding(horizontal = if (secondary) 34.dp else 44.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Glyph(glyph, if (secondary) 34.dp else 36.dp, if (secondary) HubColors.Blue else fg)
                Spacer(Modifier.width(14.dp))
            }
            BasicText(
                label,
                style = (if (secondary) HubType.Button else HubType.ButtonPrimary).copy(color = fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Круглая кнопка со значком (выход, отправить): белая с кромкой, 60 dp, значок серо-синий. */
@Composable
fun CircleButton(@DrawableRes glyph: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(CIRCLE_SIZE)
            .background(Color.White, CircleShape)
            .border(1.dp, HubColors.Border, CircleShape),
    ) {
        Glyph(glyph, 34.dp, HubColors.ExitIcon)
    }
}

/** «Назад» в верхней панели: пилюля с синей стрелкой (прототип `.back`). */
@Composable
fun BackPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        modifier = modifier
            .height(60.dp)
            .background(Color.White, shape)
            .border(1.dp, HubColors.Border, shape)
            .padding(start = 16.dp, end = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_m_back, 36.dp, HubColors.Blue)
            Spacer(Modifier.width(8.dp))
            BasicText(label, style = HubType.Callout.copy(color = HubColors.Ink), maxLines = 1)
        }
    }
}

/**
 * Переключатель языка прототипа: одна белая пилюля с кромкой, синяя «капля» с тенью переезжает под выбранный язык
 * (пружина Snappy), подпись выбранного — белая. Сегмент 84 × 50 dp, отступ 4 dp.
 */
@Composable
fun LanguageSwitch(selected: Lang, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier, takeTouch: Boolean = false) {
    val items = Tiles.languages
    val index = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val thumb = animateDpAsState(SEGMENT_WIDTH * index, HubMotion.Snappy.compose(), label = "langThumb")
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .height(SEGMENT_HEIGHT + 10.dp)
            .background(Color.White, shape)
            .border(1.dp, HubColors.Border, shape)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumb.value.roundToPx(), 0) }
                .width(SEGMENT_WIDTH)
                .fillMaxHeight()
                .softShadow(shape, 8.dp, 0.3f, HubColors.Blue)
                .background(HubColors.Blue, shape),
        )
        Row {
            items.forEach { (lang, label) ->
                val color by animateColorAsState(
                    if (lang == selected) Color.White else HubColors.LangText,
                    HubMotion.Snappy.compose(),
                    label = "langLabel",
                )
                Pressable(
                    onClick = { onSelect(lang) },
                    takeTouch = takeTouch,
                    modifier = Modifier
                        .width(SEGMENT_WIDTH)
                        .fillMaxHeight(),
                ) {
                    BasicText(label, style = HubType.Callout.copy(color = color, fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }
        }
    }
}

/** Полоса предупреждения под верхней панелью (прототип `.offline`): янтарная, значок и текст. */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(NOTICE_HEIGHT)
            .background(HubColors.AmberBg)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(HubColors.AmberFg, CircleShape))
        Spacer(Modifier.width(16.dp))
        BasicText(text, style = HubType.Callout.copy(color = HubColors.AmberFg, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Мягкая тень прототипа (`box-shadow`) — тенью Compose, цветом [color]. */
fun Modifier.softShadow(shape: Shape, elevation: Dp, alpha: Float, color: Color = HubColors.Shadow): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = alpha), spotColor = color.copy(alpha = alpha))

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

val PILL_HEIGHT = 92.dp
val SECONDARY_HEIGHT = 84.dp
val CIRCLE_SIZE = 60.dp
val NOTICE_HEIGHT = 64.dp
private val SEGMENT_WIDTH = 84.dp
private val SEGMENT_HEIGHT = 50.dp
private const val DISABLED_ALPHA = 0.4f

/** Повтор нажатия той же кнопки раньше — без действия (двойной тап, дрожание пальца). */
const val REPEAT_GUARD_MS = 600L
private const val APPEAR_STEP_MS = 40L
