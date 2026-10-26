package kz.astanahub.robot.ui.screen

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kz.astanahub.robot.ui.face.Island
import kz.astanahub.robot.ui.face.faceGestures
import kz.astanahub.robot.ui.face.faceSurface
import kz.astanahub.robot.ui.face.GuideLayer
import kz.astanahub.robot.ui.design.HubType
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.pow

/** Что экраны просят у MainActivity (кроме действий экрана 2). */
interface RobotScreensHost {
    /** Тап по лицу (разбудить / перебить / рефлекс). */
    fun onFaceTap()

    /** Удержание по лицу 3 с — PIN → админка. */
    fun onFaceHold()

    /** «Остановить» на острове поездки. */
    fun onTravelStop()

    /** «Проводить» на острове where_is. */
    fun onIslandGuide()

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
) {
    val screen = face.screen
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val progress = remember { Animatable(if (screen.menuOpen) 1f else 0f) }
    // Палец и переход ([TransitionGesture]): касание ловит летящий экран, сдвиг ведёт его от точки касания.
    // [held] — прогресс под пальцем (снимок-состояние: читается в фазе отрисовки), null — палец переход не держит.
    val gesture = remember { TransitionGesture() }
    var held by remember { mutableStateOf<Float?>(null) }
    // Накопленный сдвиг пальца при закрытии экрана 2 (draggable даёт приращения).
    val dragX = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val hostNow by rememberUpdatedState(host)
    val reduced = face.engine.reducedMotion

    fun visual(): Float = held ?: progress.value

    val target = if (screen.menuOpen) 1f else 0f
    val targetNow by rememberUpdatedState(target)

    /** Пружина к [to] от того места, где отпустили, со скоростью пальца (бросок — с лёгким отскоком). */
    fun settle(from: Float, to: Float, r: ScreenSwipe.Release?) {
        scope.launch {
            progress.snapTo(from)
            held = null
            when {
                reduced -> progress.snapTo(to)
                r == null -> progress.animateTo(to, HubMotion.Default.compose())
                else -> {
                    val spec = if (r.fling) HubMotion.Momentum.compose<Float>() else HubMotion.Default.compose()
                    progress.animateTo(to, spec, initialVelocity = r.velocity)
                }
            }
        }
    }

    LaunchedEffect(target) {
        val h = held
        if (h != null) {
            // Режим сменился, пока палец держит экран (началась поездка, таймер меню): источник истины — ViewModel.
            gesture.abort()
            settle(h, target, null)
            return@LaunchedEffect
        }
        if (progress.isRunning && progress.targetValue == target) return@LaunchedEffect
        // Смена режима без жеста: пружина от текущего прогресса и скорости (прерываемо).
        if (reduced) progress.snapTo(target) else progress.animateTo(target, HubMotion.Gentle.compose())
    }

    /** Касание: летящий экран замирает под пальцем (apple-design §3); покоящийся — не трогаем. */
    fun touchDown() {
        if (!progress.isRunning) return
        gesture.catch(progress.value)
        held = gesture.held
        scope.launch { progress.stop() }
    }

    /** Сдвиг пальца ведёт переход от прогресса в момент касания (перехват уезжающего экрана — без прыжка). */
    fun drag(dx: Float, width: Float, locked: Boolean) {
        if (gesture.held == null && progress.isRunning) scope.launch { progress.stop() }
        gesture.drag(dx, width, progress.value, locked)
        held = gesture.held
    }

    /**
     * Палец отпущен. Был сдвиг — решение жеста всегда уходит в ViewModel ([RobotScreensHost.onSwitch]): она может не
     * согласиться (поездка, legacy) — едем туда, куда она сказала. Сдвига не было (поймали и отпустили) — дальше к
     * цели ViewModel.
     */
    fun release(velocityX: Float, width: Float, allowOpen: Boolean) {
        val from = gesture.held ?: return gesture.reset()
        val r = gesture.release(velocityX, width, allowOpen)
        val to = if (r != null) {
            if (hostNow.onSwitch(r.open)) 1f else 0f
        } else {
            targetNow
        }
        settle(from, to, r)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(HubColors.Background)) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Слот лица — экран головы робота-компаньона в левой колонке (ниже, если открыта полоса «нет связи»).
        val mainTop = ScreenTwoLayout.TopBarHeight + if (two.notice != null) NOTICE_HEIGHT else 0.dp
        val slotCx: Float
        val slotCy: Float
        val slotH: Float
        val screenRect = remember { FloatArray(4) }
        val screenRadius: Float
        with(density) {
            slotCx = (ScreenTwoLayout.EyesX + ScreenTwoLayout.EyesW / 2).toPx()
            slotCy = (mainTop + ScreenTwoLayout.EyesY + ScreenTwoLayout.EyesH / 2).toPx()
            slotH = ScreenTwoLayout.EyesH.toPx()
            screenRect[0] = ScreenTwoLayout.ScreenX.toPx()
            screenRect[1] = (mainTop + ScreenTwoLayout.ScreenY).toPx()
            screenRect[2] = ScreenTwoLayout.ScreenW.toPx()
            screenRect[3] = ScreenTwoLayout.ScreenH.toPx()
            screenRadius = SCREEN_RADIUS.toPx()
        }
        val menuOpenNow by rememberUpdatedState(screen.menuOpen)
        val blur by animateFloatAsState(if (two.exitConfirm) 1f else 0f, HubMotion.Default.compose(), label = "exitBlur")
        val morph = remember { FloatArray(3) }
        val miniPress by animateFloatAsState(
            if (two.miniPressed.value) MINI_PRESS_SCALE else 1f,
            HubMotion.Press.compose(),
            label = "miniPress",
        )

        // Экран 2 (страница приложения) лежит под лицом: чёрный экран лица сжимается в голову робота-компаньона
        // (прототип). Закрытие — свайп вправо; за краем (rubber-band) страница едет за пальцем.
        ScreenTwo(
            state = two,
            actions = actions,
            progress = ::visual,
            modifier = Modifier
                .graphicsLayer {
                    translationX = if (visual() > 1f) -(visual() - 1f) * w else 0f
                    // Материал диалога выхода: экран под ним размывается (API 31+); на старых — только затемнение.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.01f) {
                        val r = blur * EXIT_BLUR_DP.dp.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Decal)
                    } else {
                        renderEffect = null
                    }
                }
                // Касание экрана 2 сразу ловит летящий переход; отпустили без сдвига — переход продолжается.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touchDown()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        if (!gesture.moved) release(0f, w, allowOpen = true)
                    }
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        dragX.floatValue += delta
                        drag(dragX.floatValue, w, locked = false)
                    },
                    orientation = Orientation.Horizontal,
                    // В поездке экран 2 не тянется (его и нет: ViewModel ведёт к лицу).
                    enabled = menuOpenNow && !two.exitConfirm && !screen.traveling,
                    onDragStarted = { dragX.floatValue = 0f },
                    onDragStopped = { velocity -> release(velocity, w, allowOpen = true) },
                ),
        )

        // Чёрный экран лица: от всего экрана к экрану головы робота (скругление растёт) — как в прототипе; в покое на
        // странице приложения его нет (экран головы — на картинке робота).
        Canvas(Modifier.fillMaxSize()) {
            val p = visual()
            if (p >= 1f) return@Canvas
            val mini = FaceMorph.miniScale(w, h, slotH)
            val pc = p.coerceIn(0f, 1f)
            val s = mini.pow(pc)
            val t = if (mini < 0.999f) ((1f - s) / (1f - mini)).coerceIn(0f, 1f) else pc
            val shift = if (p < 0f) -p * w else 0f
            val left = screenRect[0] * t + shift
            val top = screenRect[1] * t
            val right = w + (screenRect[0] + screenRect[2] - w) * t + shift
            val bottom = h + (screenRect[1] + screenRect[3] - h) * t
            val r = screenRadius * t
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(r * 1.2f, r),
                alpha = 1f - 0.6f * blur,
            )
        }

        // Лицо: одна поверхность на оба экрана, преобразование — по прогрессу перехода.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    FaceMorph.compute(visual(), w, h, slotCx, slotCy, slotH, morph)
                    scaleX = morph[0] * miniPress
                    scaleY = morph[0] * miniPress
                    translationX = morph[1]
                    translationY = morph[2]
                    transformOrigin = TransformOrigin.Center
                    // Под диалогом выхода — тоже за «стеклом».
                    alpha = 1f - 0.6f * blur
                }
                .faceSurface(face.engine, face.renderer, running),
        )

        // Экран 1: жесты по лицу, ручка, остров поездки. Пока открыт экран 2 — слоя жестов нет (касания идут экрану 2).
        val pressed = remember { mutableStateOf(false) }
        val swipeAllowed = !screen.traveling
        val swipeAllowedNow by rememberUpdatedState(swipeAllowed)
        val screenOne = !screen.menuOpen
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (screenOne) {
                        Modifier.faceGestures(
                            engine = face.engine,
                            holdEnabled = screen.holdEnabled,
                            pressed = pressed,
                            onTap = { hostNow.onFaceTap() },
                            onHold = { hostNow.onFaceHold() },
                            swipe = object : FaceSwipeListener {
                                override fun onTouchDown() = touchDown()

                                // В поездке экран 2 не откроется: он только чуть выглядывает и пружинит назад.
                                override fun onSwipe(dx: Float) = drag(dx, w, locked = !swipeAllowedNow)

                                override fun onSwipeEnd(dx: Float, velocity: Float) =
                                    release(velocity, w, allowOpen = swipeAllowedNow)

                                override fun onSwipeCancel() = release(0f, w, allowOpen = false)

                                // Касание кончилось без свайпа (тап, поглаживание): пойманный экран едет дальше к цели.
                                override fun onTouchEnd() {
                                    if (!gesture.moved) release(0f, w, allowOpen = false)
                                }
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
            // Сон: приветствие внизу по очереди на трёх языках (прототип, заставка).
            SleepGreeting(visible = screen.asleep && screenOne, modifier = Modifier.align(Alignment.BottomCenter))
            // Поездка — светлый слой прототипа поверх лица (стрелка, «Иду к…», «Стоп»), «где» — карточка внизу.
            GuideLayer(
                content = screen.island.takeIf { !screen.menuOpen },
                texts = screen.islandTexts,
                onStop = { hostNow.onTravelStop() },
            )
            Island(
                content = screen.island.takeIf { !screen.menuOpen },
                texts = screen.islandTexts,
                guideAvailable = screen.islandGuide,
                onGuide = { hostNow.onIslandGuide() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
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

/** Мини-лицо под пальцем (тап по нему — к лицу): сжимается, как кнопка (apple-design: отклик на touch-down). */
private const val MINI_PRESS_SCALE = 0.9f

/** Скругление экрана головы робота (прототип: 40 % / 48 % от экрана 272 × 186). */
private val SCREEN_RADIUS = 86.dp

/**
 * Заставка сна (прототип): «Здравствуйте! / Подойдите и спросите» внизу, по очереди на русском, казахском и английском,
 * ~7 с каждая, мягкая смена.
 */
@Composable
private fun SleepGreeting(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(HubMotion.Gentle.compose()), exit = fadeOut(HubMotion.Snappy.compose())) {
        var i by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(SLEEP_LINE_MS)
                i = (i + 1) % SLEEP_LINES.size
            }
        }
        AnimatedContent(
            targetState = i,
            transitionSpec = { fadeIn(tween(600)).togetherWith(fadeOut(tween(600))) },
            label = "sleepLine",
        ) { k ->
            Column(Modifier.padding(bottom = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(SLEEP_LINES[k].first, style = HubType.Display.copy(color = Color.White, fontSize = 56.sp))
                Spacer(Modifier.height(10.dp))
                BasicText(SLEEP_LINES[k].second, style = HubType.Callout.copy(color = Color.White.copy(alpha = 0.55f)))
            }
        }
    }
}

private val SLEEP_LINES = listOf(
    "Здравствуйте!" to "Подойдите и спросите",
    "Сәлеметсіз бе!" to "Жақындап, сұраңыз",
    "Hello!" to "Come closer and ask",
)
private const val SLEEP_LINE_MS = 7_000L

/** Размытие экрана под диалогом выхода (API 31+). */
private const val EXIT_BLUR_DP = 24
