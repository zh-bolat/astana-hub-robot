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
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithCache
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

    /** «Стоп» на слое поездки. */
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
    pin: PinPadState = remember { PinPadState() },
    pinActions: PinPadActions? = null,
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
        // Смена режима без жеста (таймер меню, начало ответа, тап по голове): как `morph` прототипа — 900 мс
        // по кривой (.45, 0, .15, 1); прерываемо касанием.
        if (reduced) progress.snapTo(target) else progress.animateTo(target, tween(MORPH_MS, easing = MORPH_EASE))
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
        val blur by animateFloatAsState(if (two.exitConfirm || pin.visible) 1f else 0f, HubMotion.Default.compose(), label = "exitBlur")
        val morph = remember { FloatArray(3) }
        val miniPress by animateFloatAsState(
            if (two.miniPressed.value) MINI_PRESS_SCALE else 1f,
            HubMotion.Press.compose(),
            label = "miniPress",
        )
        val galaxy = two.costume == Costume.GALAXY

        // Экран 2 (страница приложения) лежит под лицом: чёрный экран лица сжимается в голову робота-компаньона
        // (прототип). Закрытие — свайп вправо; за краем (rubber-band) страница едет за пальцем.
        val settled by remember { derivedStateOf { visual() >= 0.98f && !progress.isRunning } }
        ScreenTwo(
            state = two,
            actions = actions,
            progress = ::visual,
            interactive = settled,
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
            // Костюм «Космонавт»: на экране лица — космос (прототип: эллипс 900 × 600 от #0E0B2E к чёрному на 70 %
            // и 6 звёзд по 1–2 px).
            if (galaxy && t < 0.5f) {
                val a = (1f - t * 2f) * (1f - 0.6f * blur)
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val k = (right - left) / w
                val rx = SPACE_RX.dp.toPx() * k
                val ry = SPACE_RY.dp.toPx() * k
                withTransform({ scale(1f, ry / rx, Offset(cx, cy)) }) {
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            0f to Color(0xFF0E0B2E),
                            0.7f to Color.Black,
                            center = Offset(cx, cy),
                            radius = rx,
                        ),
                        topLeft = Offset(left, cy - (cy - top) * rx / ry),
                        size = Size(right - left, (bottom - top) * rx / ry),
                        alpha = a,
                    )
                }
                for (i in STARS.indices step 4) {
                    drawCircle(
                        STAR_COLORS[i / 4].copy(alpha = STARS[i + 2] * a),
                        radius = STARS[i + 3] / 2f * density.density * k.coerceAtLeast(0.3f),
                        center = Offset(left + (right - left) * STARS[i], top + (bottom - top) * STARS[i + 1]),
                    )
                }
            }
        }

        // Лицо: одна поверхность на оба экрана, преобразование — по прогрессу перехода.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Глаза в экране головы компаньона двигаются вместе с её покачиванием.
                    val bob = two.headBob.floatValue * density.density * visual().coerceIn(0f, 1f)
                    FaceMorph.compute(visual(), w, h, slotCx, slotCy + bob, slotH, morph)
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
            // Экран лица прототипа v9 — только глаза и подсказка свайпа «‹‹‹» на правом краю (без подписей и кнопок;
            // «нет связи» на лице прототип не показывает — только полоса на странице приложения).
            if (swipeAllowed && !screen.asleep && screenOne) {
                SwipeChevrons(Modifier.align(Alignment.CenterEnd).padding(end = 34.dp))
            }
            // Заставка: приветствие внизу по очереди на трёх языках (прототип `#idleLayer`).
            SleepGreeting(visible = screen.asleep && screenOne, modifier = Modifier.fillMaxSize())
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
        if (pinActions != null) PinPad(pin, pinActions)
        ClosedLayer(two, actions)
    }
}

/** Мини-лицо под пальцем (тап по нему — к лицу): сжимается, как кнопка (apple-design: отклик на touch-down). */
private const val MINI_PRESS_SCALE = 0.9f

/** Слой прототипа «Приложение закрыто»: тёмный фон, заголовок, «Открыть снова». */
@Composable
private fun ClosedLayer(two: ScreenTwoState, actions: ScreenTwoActions) {
    AnimatedVisibility(two.closed, enter = fadeIn(HubMotion.Default.compose()), exit = fadeOut(HubMotion.Snappy.compose())) {
        Box(
            Modifier
                .fillMaxSize()
                // Прототип `.closed-layer`: графитовое свечение — эллипс 900 × 600 с центром на 40 % высоты.
                .drawWithCache {
                    val rx = 900.dp.toPx()
                    val ry = 600.dp.toPx()
                    val c = Offset(size.width * 0.5f, size.height * 0.4f)
                    val brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        0f to Color(0xFF2A3142),
                        0.7f to Color(0xFF151A24),
                        1f to Color(0xFF0D1017),
                        center = c,
                        radius = rx,
                    )
                    onDrawBehind {
                        drawRect(Color(0xFF0D1017))
                        withTransform({ scale(1f, ry / rx, c) }) {
                            drawRect(brush, topLeft = Offset(0f, c.y - c.y * rx / ry), size = Size(size.width, size.height * rx / ry))
                        }
                    }
                }
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    two.texts.closed,
                    style = HubType.Display.copy(
                        color = Color(0xFFE8ECF4),
                        fontSize = 72.sp,
                        lineHeight = 83.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(40.dp))
                BasicText(
                    two.texts.closedSub,
                    style = HubType.Body.copy(color = Color(0xFF9AA4B6), fontSize = 34.sp, lineHeight = 46.sp),
                )
                Spacer(Modifier.height(40.dp))
                HubPill(two.texts.reopen, actions::onReopen, primary = false, takeTouch = true)
            }
        }
    }
}

/** Звёзды «космоса» на экране лица (прототип): доли ширины / высоты, яркость, диаметр (dp). */
private val STARS = floatArrayOf(
    0.10f, 0.20f, 0.8f, 1.5f,
    0.26f, 0.72f, 0.7f, 1f,
    0.58f, 0.12f, 0.8f, 1.5f,
    0.72f, 0.84f, 0.6f, 1f,
    0.90f, 0.36f, 0.8f, 2f,
    0.40f, 0.40f, 0.5f, 1f,
)
private val STAR_COLORS = listOf(Color.White, Color.White, Color(0xFFC8D7FF), Color.White, Color.White, Color.White)

/** Космос на экране лица: эллипс свечения (прототип: 900 × 600 px). */
private const val SPACE_RX = 900
private const val SPACE_RY = 600

/** Переход лицо ↔ приложение без пальца (прототип `morph`: 900 мс, cubic-bezier(.45, 0, .15, 1)). */
private const val MORPH_MS = 900
private val MORPH_EASE = androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.15f, 1f)

/** Скругление экрана головы робота (прототип: 40 % / 48 % от экрана 272 × 186). */
private val SCREEN_RADIUS = 86.dp

/**
 * Заставка (прототип `#idleLayer`): «Здравствуйте!» (56 / 700, низ строки в 104 dp от края) и «Подойдите и спросите»
 * (34, α .55, низ в 54) по очереди на русском, казахском и английском — каждые 7 с: строки уходят вниз на 10 dp и гаснут
 * за .6 с, через 650 мс — новый язык и обратно; подзаголовок — с задержкой .12 с.
 */
@Composable
private fun SleepGreeting(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(HubMotion.Gentle.compose()), exit = fadeOut(HubMotion.Snappy.compose())) {
        var i by remember { mutableIntStateOf(0) }
        var out by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(SLEEP_LINE_MS)
                out = true
                delay(SLEEP_SWAP_MS)
                i = (i + 1) % SLEEP_LINES.size
                out = false
            }
        }
        val hi by animateFloatAsState(if (out) 0f else 1f, tween(SLEEP_FADE_MS, easing = SLEEP_EASE), label = "saverHi")
        val sub by animateFloatAsState(if (out) 0f else 1f, tween(SLEEP_FADE_MS, delayMillis = 120, easing = SLEEP_EASE), label = "saverSub")
        Box(Modifier.fillMaxSize()) {
            BasicText(
                SLEEP_LINES[i].first,
                style = HubType.Display.copy(
                    color = Color.White,
                    fontSize = 56.sp,
                    lineHeight = 64.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp)
                    .graphicsLayer {
                        alpha = hi
                        translationY = (1f - hi) * 10.dp.toPx()
                    },
            )
            BasicText(
                SLEEP_LINES[i].second,
                style = HubType.Body.copy(
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 34.sp,
                    lineHeight = 46.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 54.dp)
                    .graphicsLayer {
                        alpha = sub
                        translationY = (1f - sub) * 10.dp.toPx()
                    },
            )
        }
    }
}

private val SLEEP_LINES = listOf(
    "Здравствуйте!" to "Подойдите и спросите",
    "Сәлеметсіз бе!" to "Жақындап, сұраңыз",
    "Hello!" to "Come closer and ask",
)
private const val SLEEP_LINE_MS = 7_000L
private const val SLEEP_SWAP_MS = 650L
private const val SLEEP_FADE_MS = 600
private val SLEEP_EASE = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Размытие страницы под диалогом выхода и панелью пароля (прототип: backdrop-filter blur 10 px; API 31+). */
private const val EXIT_BLUR_DP = 10
