package kz.astanahub.robot.ui.face

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Что показывает экран 1 кроме лица — пишет MainActivity из MainUiState (главный поток). */
@Stable
class FaceScreenState {
    /** Экран лица виден (режим FACE/SLEEP или идёт поездка); false — прежний экран меню (до M3). */
    var visible by mutableStateOf(true)

    /** Поездка: «Иду к: Кафе»; null — не едем. */
    var travelTitle by mutableStateOf<String?>(null)
    var stopLabel by mutableStateOf("")
}

/**
 * Экран 1: только лицо на весь экран; во время поездки внизу — «остров» с местом и кнопкой «Стоп».
 * Свайп влево — к прежнему экрану с функциями (временный путь до экрана 2 в M3): лицо следует за пальцем
 * с сопротивлением (rubber-band 0.55), решение — по направлению скорости и проекции импульса; засчитанный
 * свайп лицо продолжает влево со скоростью пальца ([SwipeToMenu]). Тап — [onTap] (разбудить / перебить).
 */
@Composable
fun FaceScreen(
    engine: FaceEngine,
    renderer: FaceRenderer,
    state: FaceScreenState,
    onSwipeToMenu: () -> Unit,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onShown: () -> Unit,
    onHidden: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val presence = remember { Animatable(if (state.visible) 1f else 0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipe by rememberUpdatedState(onSwipeToMenu)
    val tap by rememberUpdatedState(onTap)
    val visibleNow by rememberUpdatedState(state.visible)

    LaunchedEffect(state.visible) {
        if (state.visible) drag.snapTo(0f)
        presence.animateTo(if (state.visible) 1f else 0f, HubMotion.Gentle.compose())
        if (state.visible) onShown() else onHidden()
    }
    val running = started && (state.visible || presence.isRunning)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = presence.value
                alpha = p
                // Уходит влево — туда же, куда вёл палец; возвращается тем же путём.
                translationX = drag.value - (1f - p) * EXIT_SHIFT_DP.dp.toPx()
            }
            .background(HubColors.Black)
            .faceSurface(engine, renderer, running)
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dx = 0f
                        tracker.resetTracking()
                    },
                    onHorizontalDrag = { change, amount ->
                        dx += amount
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val width = size.width.toFloat()
                        // Влево — с сопротивлением, вправо — край (дальше некуда): сильнее.
                        val shown = if (dx < 0f) -rubberBand(-dx, width) else rubberBand(dx, width) * 0.4f
                        scope.launch { drag.snapTo(shown) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val release = SwipeToMenu.release(dx, drag.value, v, size.width.toFloat(), MIN_EXIT_DP.dp.toPx())
                        if (release.commit) {
                            // Засчитан: лицо продолжает влево со скоростью пальца и гаснет — не возвращается назад.
                            swipe()
                            scope.launch {
                                drag.animateTo(release.target, HubMotion.Gentle.compose(), initialVelocity = release.velocity)
                                // Экран лица остался (идёт поездка — лицо обязано быть на экране): вернуться на место.
                                if (visibleNow) drag.animateTo(0f, HubMotion.Default.compose())
                            }
                        } else {
                            // Не засчитан: возврат на место с передачей скорости (лёгкий отскок — жест с импульсом).
                            scope.launch { drag.animateTo(0f, HubMotion.Momentum.compose(), initialVelocity = release.velocity) }
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, HubMotion.Default.compose()) } },
                )
            }
            // Тап отдельно от свайпа: сдвиг дальше порога касания тап отменяет (Compose разводит жесты сам).
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
    ) {
        TravelIsland(
            title = state.travelTitle,
            stopLabel = state.stopLabel,
            onStop = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}

/**
 * «Остров» поездки: место + «Стоп». Появляется «материализуясь» — scale 0.92 → 1 и прозрачность пружиной
 * от нижнего края, уходит тем же путём (AnimatedVisibility: переходы прерываемые, от текущих значений).
 * Полировка — M4.
 */
@Composable
fun TravelIsland(title: String?, stopLabel: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    // Пока остров уходит, title уже null — показываем последнее место (запоминаем после композиции).
    val last = remember { mutableStateOf(title.orEmpty()) }
    SideEffect { if (title != null) last.value = title }
    val text = title ?: last.value
    val origin = TransformOrigin(0.5f, 1f)
    AnimatedVisibility(
        visible = title != null,
        modifier = modifier,
        enter = fadeIn(HubMotion.Default.compose()) +
            scaleIn(HubMotion.Default.compose(), initialScale = 0.92f, transformOrigin = origin),
        exit = fadeOut(HubMotion.Default.compose()) +
            scaleOut(HubMotion.Default.compose(), targetScale = 0.92f, transformOrigin = origin),
    ) {
        val shape = RoundedCornerShape(percent = 50)
        Row(
            Modifier
                .background(HubColors.Island, shape)
                .border(1.dp, HubColors.Hairline, shape)
                .padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "\u2192  " + text,
                style = HubType.Headline.copy(color = HubColors.Label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.width(32.dp))
            PillButton(label = stopLabel, onClick = onStop)
        }
    }
}

/** Белая кнопка-пилюля: отклик сразу на касание (scale 0.97), действие — на отпускании. */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) HubMotion.PRESS_SCALE else 1f, HubMotion.Press.compose(), label = "press")
    val click by rememberUpdatedState(onClick)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(72.dp)
            .widthIn(min = 160.dp)
            .background(HubColors.White, RoundedCornerShape(percent = 50))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { click() },
                )
            }
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = HubType.Headline.copy(color = HubColors.Black), maxLines = 1)
    }
}

/** Сопротивление за краем (apple-design §9): чем дальше, тем меньше следует за пальцем. */
fun rubberBand(overshoot: Float, dimension: Float, constant: Float = RUBBER_BAND): Float =
    (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/** Проекция импульса Apple: куда «доедет» бросок со скоростью [velocity] (px/с). */
fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
    (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

const val RUBBER_BAND = 0.55f
private const val EXIT_SHIFT_DP = 80
private const val MIN_EXIT_DP = 120

/** Отпускание свайпа к меню: засчитан ли, куда и с какой скоростью уходит лицо. */
data class SwipeRelease(val commit: Boolean, val target: Float, val velocity: Float)

/**
 * Решение по отпусканию (чистая функция — тест): по знаку скорости (> [MIN_VELOCITY] px/с), а медленный жест — по
 * проекции импульса Apple за четверть ширины. Засчитан — цель левее точки отпускания (проекция, но не меньше
 * [minExit] px), скорость не направлена вправо: лицо продолжает движение пальца, без отката.
 */
object SwipeToMenu {
    const val MIN_VELOCITY = 700f

    fun release(fingerDx: Float, shown: Float, velocity: Float, width: Float, minExit: Float): SwipeRelease {
        val projected = fingerDx + project(velocity)
        val commit = velocity < -MIN_VELOCITY || (abs(velocity) < MIN_VELOCITY && projected < -width * 0.25f)
        return if (commit) {
            SwipeRelease(true, min(shown + project(velocity), shown - minExit), min(velocity, 0f))
        } else {
            SwipeRelease(false, 0f, velocity)
        }
    }
}
