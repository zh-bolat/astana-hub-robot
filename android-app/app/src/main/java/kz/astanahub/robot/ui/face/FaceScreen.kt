package kz.astanahub.robot.ui.face

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Что показывает экран 1 кроме лица — пишет MainActivity из MainUiState (главный поток). */
@Stable
class FaceScreenState {
    /** Экран лица виден (режим FACE/SLEEP или идёт поездка); false — прежний экран меню (до M3). */
    var visible by mutableStateOf(true)

    /** Поездка: «Иду к: Кафе»; null — не едем. В поездке свайп к меню не засчитывается (лицо обязано быть на экране). */
    var travelTitle by mutableStateOf<String?>(null)
    var stopLabel by mutableStateOf("")

    /** Спит: ручка свайпа гаснет. */
    var asleep by mutableStateOf(false)
}

/**
 * Экран 1: только лицо на весь экран; справа — ручка свайпа; во время поездки внизу — «остров» с местом и «Стоп».
 *
 * Жесты по лицу ([FaceGestureRecognizer], параллельно с первого касания, гистерезис 10 dp):
 * - касание — сразу отклик: глаза на палец, ручка ярче;
 * - тап — [onTap] (разбудить / перебить / рефлекс — решает MainActivity по состоянию);
 * - удержание 3 с — кольцо вокруг пальца заполняется, лицо сосредотачивается → [onHold] (PIN → админка);
 * - поглаживание (вверх-вниз, по кругу, туда-сюда) — лицо жмурится и довольно улыбается, голова льнёт к руке;
 * - свайп влево — к прежнему экрану (до M3): лицо следует за пальцем (rubber-band 0.55), решение — по знаку
 *   скорости и проекции импульса, засчитанный уходит влево со скоростью пальца ([SwipeToMenu]); в поездке — только
 *   сопротивление и возврат.
 */
@Composable
fun FaceScreen(
    engine: FaceEngine,
    renderer: FaceRenderer,
    state: FaceScreenState,
    holdEnabled: Boolean,
    onSwipeToMenu: () -> Unit,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onStop: () -> Unit,
    onShown: () -> Unit,
    onHidden: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val presence = remember { Animatable(if (state.visible) 1f else 0f) }
    val drag = remember { Animatable(0f) }
    val pressed = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val swipe by rememberUpdatedState(onSwipeToMenu)
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val visibleNow by rememberUpdatedState(state.visible)
    val swipeAllowed by rememberUpdatedState(state.travelTitle == null)

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
            .pointerInput(holdEnabled) {
                val inputs = engine.inputs
                fun touch(x: Float, y: Float) {
                    inputs.touchX = (x / size.width * 2f - 1f).coerceIn(-1f, 1f)
                    inputs.touchY = (y / size.height * 2f - 1f).coerceIn(-1f, 1f)
                }
                val listener = object : FaceGestureRecognizer.Listener {
                    override fun onDown(x: Float, y: Float) {
                        touch(x, y)
                        inputs.touching = true
                        pressed.value = true
                    }

                    override fun onMove(x: Float, y: Float) = touch(x, y)

                    override fun onTap(x: Float, y: Float) = tap()

                    override fun onHoldStart(downTime: Long) {
                        inputs.holdStartMs = downTime
                    }

                    override fun onHoldCancel() {
                        inputs.holdStartMs = -1L
                    }

                    override fun onHold() {
                        inputs.holdStartMs = -1L
                        engine.holdDone()
                        hold()
                    }

                    override fun onSwipe(dx: Float) {
                        val width = size.width.toFloat()
                        // Влево — с сопротивлением; в поездке меню не откроется — сопротивление сильнее.
                        val k = if (swipeAllowed) 1f else LOCKED_RESISTANCE
                        val shown = if (dx < 0f) -rubberBand(-dx, width) * k else rubberBand(dx, width) * LOCKED_RESISTANCE
                        scope.launch { drag.snapTo(shown) }
                    }

                    override fun onSwipeEnd(dx: Float, velocity: Float) {
                        val release = SwipeToMenu.release(
                            dx, drag.value, velocity, size.width.toFloat(), MIN_EXIT_DP.dp.toPx(), allowed = swipeAllowed,
                        )
                        if (release.commit) {
                            // Засчитан: лицо продолжает влево со скоростью пальца и гаснет — не возвращается назад.
                            swipe()
                            scope.launch {
                                drag.animateTo(release.target, HubMotion.Gentle.compose(), initialVelocity = release.velocity)
                                if (visibleNow) drag.animateTo(0f, HubMotion.Default.compose())
                            }
                        } else {
                            // Не засчитан: возврат на место с передачей скорости (лёгкий отскок — жест с импульсом).
                            scope.launch { drag.animateTo(0f, HubMotion.Momentum.compose(), initialVelocity = release.velocity) }
                        }
                    }

                    override fun onSwipeCancel() {
                        scope.launch { drag.animateTo(0f, HubMotion.Default.compose()) }
                    }

                    override fun onStroke(vx: Float, vy: Float) {
                        inputs.petting = true
                        inputs.strokeVx = vx
                        inputs.strokeVy = vy
                    }

                    override fun onStrokeEnd() {
                        inputs.petting = false
                        inputs.strokeVx = 0f
                        inputs.strokeVy = 0f
                    }

                    override fun onUp() {
                        inputs.touching = false
                        inputs.holdStartMs = -1L
                        inputs.petting = false
                        pressed.value = false
                    }
                }
                val recognizer = FaceGestureRecognizer(
                    slop = GESTURE_SLOP_DP.dp.toPx(),
                    strokeMinPath = STROKE_MIN_PATH_DP.dp.toPx(),
                    holdEnabled = holdEnabled,
                    listener = listener,
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    recognizer.down(down.position.x, down.position.y, down.uptimeMillis)
                    while (true) {
                        // Пока палец лежит неподвижно, событий нет — время удержания идёт по таймауту.
                        val event = withTimeoutOrNull(HOLD_POLL_MS) { awaitPointerEvent() }
                        if (event == null) {
                            recognizer.tick(SystemClock.uptimeMillis())
                            continue
                        }
                        // Второй палец не мешает: следим только за первым.
                        val change = event.changes.firstOrNull { it.id == id }
                        if (change == null) {
                            recognizer.cancel()
                            break
                        }
                        if (!change.pressed) {
                            recognizer.up(change.position.x, change.position.y, change.uptimeMillis)
                            break
                        }
                        recognizer.move(change.position.x, change.position.y, change.uptimeMillis)
                        recognizer.tick(change.uptimeMillis)
                        if (recognizer.mode == FaceGestureRecognizer.Mode.SWIPE ||
                            recognizer.mode == FaceGestureRecognizer.Mode.STROKE
                        ) {
                            change.consume()
                        }
                    }
                }
            },
    ) {
        SwipeHandle(
            drag = { drag.value },
            pressed = pressed.value,
            visible = state.travelTitle == null && !state.asleep,
            reducedMotion = engine.reducedMotion,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
        )
        TravelIsland(
            title = state.travelTitle,
            stopLabel = state.stopLabel,
            onStop = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}

/**
 * Ручка свайпа (как в iOS): тонкая вертикальная пилюля у правого края, ненавязчиво говорит «экран тянется влево».
 * Касание лица — ярче; свайп — ручка тянется за лицом, вытягивается и светлеет; отпустили — пружиной обратно.
 * Раз в [HINT_PERIOD_MS] слегка подаётся влево и обратно (кроме уменьшения движения). Монохром: белый с alpha.
 */
@Composable
fun SwipeHandle(
    drag: () -> Float,
    pressed: Boolean,
    visible: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, HubMotion.Default.compose(), label = "handle")
    val press by animateFloatAsState(if (pressed) 1f else 0f, HubMotion.Snappy.compose(), label = "handlePress")
    val hint = remember { Animatable(0f) }
    LaunchedEffect(visible, reducedMotion) {
        if (!visible || reducedMotion) return@LaunchedEffect
        while (true) {
            delay(HINT_PERIOD_MS)
            hint.animateTo(-1f, HubMotion.Default.compose())
            hint.animateTo(0f, HubMotion.Momentum.compose())
        }
    }
    Box(
        modifier
            .graphicsLayer {
                val pull = (-drag() / PULL_FULL_DP.dp.toPx()).coerceIn(0f, 1f)
                alpha = shown * (HANDLE_ALPHA + (HANDLE_ALPHA_PRESSED - HANDLE_ALPHA) * maxOf(press, pull))
                translationX = drag() * 0.35f + hint.value * HINT_SHIFT_DP.dp.toPx()
                scaleY = 1f + 0.25f * pull
                transformOrigin = TransformOrigin(1f, 0.5f)
            }
            .width(6.dp)
            .height(76.dp)
            .background(HubColors.White, RoundedCornerShape(percent = 50)),
    )
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

/** Гистерезис жестов (apple-design §10: ~10 px; на киоске 1 dp = 1.2 px). */
private const val GESTURE_SLOP_DP = 10
private const val STROKE_MIN_PATH_DP = 90
private const val HOLD_POLL_MS = 16L

/** В поездке свайп к меню не засчитывается: сопротивление сильнее. */
private const val LOCKED_RESISTANCE = 0.4f

private const val HANDLE_ALPHA = 0.26f
private const val HANDLE_ALPHA_PRESSED = 0.7f
private const val PULL_FULL_DP = 160
private const val HINT_SHIFT_DP = 10
private const val HINT_PERIOD_MS = 40_000L

/** Отпускание свайпа к меню: засчитан ли, куда и с какой скоростью уходит лицо. */
data class SwipeRelease(val commit: Boolean, val target: Float, val velocity: Float)

/**
 * Решение по отпусканию (чистая функция — тест): по знаку скорости (> [MIN_VELOCITY] px/с), а медленный жест — по
 * проекции импульса Apple за четверть ширины. Засчитан — цель левее точки отпускания (проекция, но не меньше
 * [minExit] px), скорость не направлена вправо: лицо продолжает движение пальца, без отката.
 */
object SwipeToMenu {
    const val MIN_VELOCITY = 700f

    fun release(
        fingerDx: Float,
        shown: Float,
        velocity: Float,
        width: Float,
        minExit: Float,
        allowed: Boolean = true,
    ): SwipeRelease {
        val projected = fingerDx + project(velocity)
        val commit = allowed &&
            (velocity < -MIN_VELOCITY || (abs(velocity) < MIN_VELOCITY && projected < -width * 0.25f))
        return if (commit) {
            SwipeRelease(true, min(shown + project(velocity), shown - minExit), min(velocity, 0f))
        } else {
            SwipeRelease(false, 0f, velocity)
        }
    }
}
