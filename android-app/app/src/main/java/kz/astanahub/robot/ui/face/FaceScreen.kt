package kz.astanahub.robot.ui.face

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Что показывает экран 1 кроме лица — пишет MainActivity из MainUiState (главный поток). */
@Stable
class FaceScreenState {
    /**
     * Открыт экран 2 (режим MENU и не едем). false — экран 1: лицо во весь экран (FACE, SLEEP, поездка).
     * Переход между ними — жестом или пружиной ([kz.astanahub.robot.ui.screen.RobotScreens]).
     */
    var menuOpen by mutableStateOf(false)

    /** Робот везёт человека: экран 2 не открывается (лицо обязано быть на экране), ручки нет. */
    var traveling by mutableStateOf(false)

    /** Остров внизу ([IslandModel]: where_is, поездка, итог); null — острова нет. */
    var island by mutableStateOf<IslandContent?>(null)
    var islandTexts by mutableStateOf(IslandTexts())

    /** Спит: ручка свайпа гаснет. */
    var asleep by mutableStateOf(false)

    /** Удержание по лицу открывает админку (есть доступ и не едем). */
    var holdEnabled by mutableStateOf(false)
}

/** Свайп по лицу к экрану 2: смещение пальца, отпускание со скоростью (px/с), отмена (жест стал поглаживанием). */
interface FaceSwipeListener {
    fun onSwipe(dx: Float)
    fun onSwipeEnd(dx: Float, velocity: Float)
    fun onSwipeCancel()
}

/**
 * Жесты экрана 1 по лицу ([FaceGestureRecognizer], параллельно с первого касания, гистерезис 10 dp):
 * - касание — сразу отклик: глаза на палец, ручка ярче ([pressed]);
 * - тап — [onTap] (разбудить / перебить / рефлекс — решает MainActivity по состоянию);
 * - удержание 3 с — кольцо вокруг пальца заполняется, лицо сосредотачивается → [onHold] (PIN → админка);
 * - поглаживание (вверх-вниз, по кругу, туда-сюда) — лицо жмурится и довольно улыбается, голова льнёт к руке;
 * - свайп влево — [swipe] (переход к экрану 2 ведёт палец).
 *
 * Слой с этим модификатором неподвижен (координаты пальца не зависят от того, куда уехало лицо). Касание кнопки
 * внутри него ([pressToClick] потребляет `down`) жест лица не начинает (ревью M2-r1, B1).
 */
fun Modifier.faceGestures(
    engine: FaceEngine,
    holdEnabled: Boolean,
    pressed: MutableState<Boolean>,
    onTap: () -> Unit,
    onHold: () -> Unit,
    swipe: FaceSwipeListener,
): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val swipeNow by rememberUpdatedState(swipe)
    pointerInput(holdEnabled) {
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

            override fun onSwipe(dx: Float) = swipeNow.onSwipe(dx)

            override fun onSwipeEnd(dx: Float, velocity: Float) = swipeNow.onSwipeEnd(dx, velocity)

            override fun onSwipeCancel() = swipeNow.onSwipeCancel()

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
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val id = down.id
                // Касание кнопки поверх лица («Стоп» на острове) — её: кнопка потребила `down` раньше нас
                // (проход Main идёт от потомков к предкам), жест лица не начинаем.
                recognizer.down(down.position.x, down.position.y, down.uptimeMillis, onControl = down.isConsumed)
                if (recognizer.mode == FaceGestureRecognizer.Mode.NONE) return@awaitEachGesture
                while (true) {
                    // Пока палец лежит неподвижно, событий нет — время удержания идёт по таймауту.
                    val event = withTimeoutOrNull(HOLD_POLL_MS) { awaitPointerEvent() }
                    if (event == null) {
                        recognizer.tick(SystemClock.uptimeMillis())
                        continue
                    }
                    // Второй палец не мешает и не отменяет: жест ведёт первый палец.
                    val change = event.changes.firstOrNull { it.id == id }
                    if (change == null) {
                        recognizer.cancel()
                        break
                    }
                    if (!change.pressed) {
                        // Отпускание забрала кнопка («Стоп» на острове) — это её нажатие, не тап по лицу.
                        if (change.isConsumed) {
                            recognizer.cancel()
                        } else {
                            recognizer.up(change.position.x, change.position.y, change.uptimeMillis)
                        }
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
        } finally {
            // Слой жестов убрали посреди жеста (открылся экран 2, началась поездка) — лицо не остаётся «тронутым».
            recognizer.cancel()
        }
    }
}

/**
 * Ручка свайпа (как в iOS): тонкая вертикальная пилюля у правого края, ненавязчиво говорит «экран тянется влево».
 * Касание лица — ярче; переход к экрану 2 — ручка едет на кромке входящего экрана ([progress] × [travel] px),
 * вытягивается, светлеет и гаснет; отпустили — пружиной обратно. Раз в [HINT_PERIOD_MS] слегка подаётся влево и
 * обратно (кроме уменьшения движения). Монохром: белый с alpha.
 */
@Composable
fun SwipeHandle(
    progress: () -> Float,
    travel: Float,
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
                val p = progress().coerceAtLeast(0f)
                val pull = (p * travel / PULL_FULL_DP.dp.toPx()).coerceIn(0f, 1f)
                val fade = (1f - p * 2.5f).coerceIn(0f, 1f)
                alpha = shown * fade * (HANDLE_ALPHA + (HANDLE_ALPHA_PRESSED - HANDLE_ALPHA) * maxOf(press, pull))
                translationX = -p * travel + hint.value * HINT_SHIFT_DP.dp.toPx()
                scaleY = 1f + 0.25f * pull
                transformOrigin = TransformOrigin(1f, 0.5f)
            }
            .width(7.dp)
            .height(84.dp)
            .background(HubColors.White, RoundedCornerShape(percent = 50)),
    )
}

/**
 * Белая кнопка-пилюля: отклик сразу на касание (scale 0.97), действие — на отпускании.
 * Касание **потребляется сразу** ([pressToClick]): жесты лица под кнопкой (удержание → админка, тап, свайп) не
 * начинаются — удержание пальца на «Стоп» не откроет админку и не потеряет нажатие (ревью M2-r1, B1).
 */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed.value) HubMotion.PRESS_SCALE else 1f, HubMotion.Press.compose(), label = "press")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(72.dp)
            .widthIn(min = 160.dp)
            .background(HubColors.White, RoundedCornerShape(percent = 50))
            .pressToClick(pressed, onClick)
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = HubType.Headline.copy(color = HubColors.Black), maxLines = 1)
    }
}

/**
 * Нажатие кнопки поверх лица или экрана 2: `down` потребляется сразу (родительские жесты — лицо, свайп экрана —
 * видят его потреблённым и не начинаются), [pressed] — на touch-down (для scale 0.97), [onClick] — на отпускании
 * внутри кнопки; палец увели за край или касание забрал свайп — нажатие отменяется без действия.
 */
fun Modifier.pressToClick(pressed: MutableState<Boolean>, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            pressed.value = true
            val up = try {
                waitForUpOrCancellation()
            } finally {
                pressed.value = false
            }
            if (up != null) {
                up.consume()
                click()
            }
        }
    }
}

/** Сопротивление за краем (apple-design §9): чем дальше, тем меньше следует за пальцем. */
fun rubberBand(overshoot: Float, dimension: Float, constant: Float = RUBBER_BAND): Float =
    (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/** Проекция импульса Apple: куда «доедет» бросок со скоростью [velocity] (px/с). */
fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
    (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

const val RUBBER_BAND = 0.55f

/** Гистерезис жестов (apple-design §10: ~10 px; на киоске 1 dp = 1.2 px). */
private const val GESTURE_SLOP_DP = 10
private const val STROKE_MIN_PATH_DP = 90

/** Пока палец лежит неподвижно, время удержания проверяется раз в 50 мс (кольцо появляется через 400 мс). */
private const val HOLD_POLL_MS = 50L

/** Ручка в покое заметна с 1–1.5 м (ревью M2-r1, M6), но тише лица; касание — ярче. */
private const val HANDLE_ALPHA = 0.36f
private const val HANDLE_ALPHA_PRESSED = 0.8f
private const val PULL_FULL_DP = 160
private const val HINT_SHIFT_DP = 10
private const val HINT_PERIOD_MS = 40_000L
