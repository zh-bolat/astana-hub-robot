package kz.astanahub.robot.ui.screen

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.face.FaceController
import kz.astanahub.robot.ui.face.FaceSwipeListener
import kz.astanahub.robot.ui.face.SwipeHandle
import kz.astanahub.robot.ui.face.TravelIsland
import kz.astanahub.robot.ui.face.faceGestures
import kz.astanahub.robot.ui.face.faceSurface

/** Что экраны просят у MainActivity (кроме действий экрана 2). */
interface RobotScreensHost {
    /** Тап по лицу (разбудить / перебить / рефлекс). */
    fun onFaceTap()

    /** Удержание по лицу 3 с — PIN → админка. */
    fun onFaceHold()

    /** «Стоп» на острове поездки. */
    fun onTravelStop()

    /**
     * Жест решил открыть ([open] = true) или закрыть экран 2. MainActivity зовёт MainViewModel (`openMenu` /
     * `showFace`) и сразу возвращает, что вышло (ViewModel может не согласиться: в legacy меню — главный экран,
     * в поездке лицо обязано остаться) — переход доезжает туда без рывка.
     */
    fun onSwitch(open: Boolean): Boolean
}

/**
 * Экран 1 (лицо) и экран 2 (функции) — один переход, прогресс 0 → 1 ([ScreenSwipe]):
 * - палец ведёт переход 1:1 (свайп влево по лицу открывает, вправо по экрану 2 — закрывает), за краями — rubber-band;
 *   отпускание — пружина от текущего прогресса со скоростью пальца, решение — по знаку скорости / проекции;
 * - экран 2 въезжает справа (его левая кромка идёт за пальцем), ручка едет на этой кромке и гаснет;
 * - лицо **не исчезает**: по прогрессу (не по таймеру) сжимается в живое мини-лицо в шапке экрана 2 ([FaceMorph])
 *   и живёт там (моргает, слушает, думает, говорит); обратно — тем же путём;
 * - режим из ViewModel (меню по таймеру → лицо, поездка → лицо, legacy → меню) — пружиной от текущего прогресса.
 *
 * Слои: экран 2 → лицо (без касаний) → слой жестов экрана 1 с ручкой и островом (только когда экран 1 активен).
 */
@Composable
fun RobotScreens(
    face: FaceController,
    two: ScreenTwoState,
    actions: ScreenTwoActions,
    host: RobotScreensHost,
    holdEnabled: Boolean,
) {
    val screen = face.screen
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val progress = remember { Animatable(if (screen.menuOpen) 1f else 0f) }
    // Палец ведёт переход: прогресс под пальцем (без корутин на каждое движение); null — не тянут.
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    // Накопленный сдвиг пальца при закрытии экрана 2 (draggable даёт приращения).
    val dragX = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val hostNow by rememberUpdatedState(host)
    val reduced = face.engine.reducedMotion

    fun visual(): Float = if (dragFrom != null) dragProgress.floatValue else progress.value

    val target = if (screen.menuOpen) 1f else 0f
    LaunchedEffect(target) {
        if (dragFrom != null) return@LaunchedEffect
        if (progress.isRunning && progress.targetValue == target) return@LaunchedEffect
        // Смена режима без жеста: пружина от текущего прогресса и скорости (прерываемо).
        if (reduced) progress.snapTo(target) else progress.animateTo(target, HubMotion.Gentle.compose())
    }

    /** Отпускание: решение, ViewModel, пружина со скоростью пальца (бросок — с лёгким отскоком). */
    fun release(start: Float, velocityX: Float, width: Float, allowOpen: Boolean) {
        val p = dragProgress.floatValue
        val r = ScreenSwipe.release(p, velocityX, width, allowOpen)
        val open = if (r.open != (start >= 0.5f)) hostNow.onSwitch(r.open) else r.open
        val to = if (open) 1f else 0f
        scope.launch {
            progress.snapTo(p)
            dragFrom = null
            val spec = if (r.fling) HubMotion.Momentum.compose<Float>() else HubMotion.Default.compose()
            if (reduced) progress.snapTo(to) else progress.animateTo(to, spec, initialVelocity = r.velocity)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(HubColors.Black)) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val slotCx: Float
        val slotCy: Float
        val slotH: Float
        with(density) {
            slotCx = (ScreenTwoLayout.SidePadding + ScreenTwoLayout.SlotWidth / 2).toPx()
            slotCy = (ScreenTwoLayout.HeaderHeight / 2).toPx()
            slotH = ScreenTwoLayout.MiniFaceHeight.toPx()
        }
        val menuOpenNow by rememberUpdatedState(screen.menuOpen)
        val blur by animateFloatAsState(if (two.exitConfirm) 1f else 0f, HubMotion.Default.compose(), label = "exitBlur")

        // Экран 2: левая кромка — за пальцем; закрытие — свайп вправо (горизонтальный, чат прокручивается по вертикали).
        ScreenTwo(
            state = two,
            actions = actions,
            progress = ::visual,
            modifier = Modifier
                .graphicsLayer {
                    translationX = w * (1f - visual())
                    // Материал диалога выхода: экран под ним размывается (API 31+); на старых — только затемнение.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.01f) {
                        val r = blur * EXIT_BLUR_DP.dp.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Decal)
                    } else {
                        renderEffect = null
                    }
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val from = dragFrom ?: return@rememberDraggableState
                        dragX.floatValue += delta
                        dragProgress.floatValue = ScreenSwipe.progress(from, dragX.floatValue, w)
                    },
                    orientation = Orientation.Horizontal,
                    enabled = menuOpenNow && !two.exitConfirm,
                    onDragStarted = {
                        dragX.floatValue = 0f
                        dragProgress.floatValue = progress.value
                        dragFrom = progress.value
                        progress.stop()
                    },
                    onDragStopped = { velocity -> release(dragFrom ?: 1f, velocity, w, allowOpen = true) },
                ),
        )

        // Лицо: одна поверхность на оба экрана, преобразование — по прогрессу перехода.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = FaceMorph.transform(visual(), w, h, slotCx, slotCy, slotH)
                    scaleX = t.scale
                    scaleY = t.scale
                    translationX = t.translationX
                    translationY = t.translationY
                    transformOrigin = TransformOrigin.Center
                    // Под диалогом выхода — тоже за «стеклом».
                    alpha = 1f - 0.6f * blur
                }
                .faceSurface(face.engine, face.renderer, running),
        )

        // Экран 1: жесты по лицу, ручка, остров поездки. Пока открыт экран 2 — слоя жестов нет (касания идут экрану 2).
        val pressed = remember { mutableStateOf(false) }
        val swipeAllowed = screen.travelTitle == null
        val swipeAllowedNow by rememberUpdatedState(swipeAllowed)
        val screenOne = !screen.menuOpen
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (screenOne) {
                        Modifier.faceGestures(
                            engine = face.engine,
                            holdEnabled = holdEnabled,
                            pressed = pressed,
                            onTap = { hostNow.onFaceTap() },
                            onHold = { hostNow.onFaceHold() },
                            swipe = object : FaceSwipeListener {
                                override fun onSwipe(dx: Float) {
                                    if (dragFrom == null) {
                                        dragFrom = 0f
                                        scope.launch { progress.stop() }
                                    }
                                    val p = ScreenSwipe.progress(0f, dx, w)
                                    // В поездке экран 2 не откроется: он только чуть выглядывает и пружинит назад.
                                    dragProgress.floatValue = if (swipeAllowedNow) p else p * LOCKED_PEEK
                                }

                                override fun onSwipeEnd(dx: Float, velocity: Float) =
                                    release(0f, velocity, w, allowOpen = swipeAllowedNow)

                                override fun onSwipeCancel() = release(0f, 0f, w, allowOpen = false)
                            },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            SwipeHandle(
                progress = ::visual,
                travel = w,
                pressed = pressed.value,
                visible = swipeAllowed && !screen.asleep && screenOne,
                reducedMotion = reduced,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            )
            TravelIsland(
                title = screen.travelTitle,
                stopLabel = screen.stopLabel,
                onStop = { hostNow.onTravelStop() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }

        AnimatedVisibility(
            visible = two.exitConfirm,
            enter = fadeIn(HubMotion.Default.compose()),
            exit = fadeOut(HubMotion.Snappy.compose()),
        ) {
            ExitDialog(two.texts, actions)
        }
    }
}

/** В поездке экран 2 только выглядывает: доля прогресса под пальцем. */
private const val LOCKED_PEEK = 0.25f

/** Размытие экрана под диалогом выхода (API 31+). */
private const val EXIT_BLUR_DP = 24
