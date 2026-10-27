package kz.astanahub.robot.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubType

/** Что делает робот-компаньон (прототип: `.ind.listen / .think / .speak`). */
enum class CompanionPose { IDLE, LISTEN, THINK, SPEAK }

/**
 * Робот-компаньон прототипа из слоёв, снятых из него же ([CompanionLayers]): сияние с тенью на полу, тело (одежда,
 * домбра) и руки с мягкой тенью фигуры, корпус головы, чёрный экран (в нём живые глаза), такия / наушники, у
 * космонавта — корона-система с летающими планетами. Движения — по последним определениям CSS прототипа (кривая
 * ease-in-out на каждом отрезке): фигура покачивается вокруг (270, 517,2), руки — у плеч (покой ±12…18°, слушает
 * ±14…30°, машет −14…−46°, казахский держит домбру 4…8° и бренчит 2…12°), голова кивает (3,6 с), корона плавает
 * (−6 dp, 3,6 с). Смена костюма (`setSkin(k, true)`): сияние и экран стоят, корпус «выпрыгивает» из 0,86 (0,5 с),
 * фигура проявляется (0,45 с, без покачивания 1 с), наряд — с задержкой 0,3 с. Свайп по роботу — следующий /
 * предыдущий костюм. Кадры — в фазе слоя (graphicsLayer), без рекомпозиции.
 */
@Composable
fun Companion(
    costume: Costume,
    pose: CompanionPose,
    headBob: MutableFloatState,
    onSwipeCostume: (Int) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    /** Сигнал «собраться» (смена / повторный выбор костюма): каждое новое значение — сборка заново. */
    assemble: Int = 0,
    /** Кивок экрана головы (dp, прототип `nod`). */
    nod: MutableFloatState? = null,
    /** Видимость сияния, корпуса, тела и наряда на переходе лицо ↔ страница (экран головы остаётся). */
    bodyAlpha: () -> Float = { 1f },
) {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { time.longValue = it - start }
    }
    val poseNow by rememberUpdatedState(pose)
    val costumeNow by rememberUpdatedState(costume)
    // «Сборка» (прототип `.ind.bot.assemble`, 1 с): время её начала по часам кадра.
    val assembleAt = remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    LaunchedEffect(assemble) {
        if (assemble != 0 && !reducedMotion) assembleAt.longValue = time.longValue
    }
    val asm = remember { Assemble() }
    // Свайп по роботу: за пальцем ±80 dp и наклон ±6° вокруг центра `#ind`; отпустили — на место.
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipe by rememberUpdatedState(onSwipeCostume)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier
            .size(BOX_W.dp, BOX_H.dp)
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val dxDp = with(density) { total.toDp().value }
                        if (abs(dxDp) > SWIPE_DP) swipe(if (dxDp < 0) 1 else -1)
                        scope.launch { drag.animateTo(0f, tween(SWIPE_BACK_MS, easing = SWIPE_BACK_EASE)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, tween(SWIPE_BACK_MS, easing = SWIPE_BACK_EASE)) } },
                ) { change, dx ->
                    change.consume()
                    total += dx
                    val dxDp = with(density) { total.toDp().value }
                    scope.launch { drag.snapTo((dxDp * 0.4f).coerceIn(-80f, 80f)) }
                }
            }
            .graphicsLayer {
                translationX = drag.value * density.density
                rotationZ = drag.value / 80f * 6f
                transformOrigin = TransformOrigin(SWIPE_PX / BOX_W, SWIPE_PY / BOX_H)
            },
    ) {
        val body = bodyAlpha
        // Сияние и тень на полу: при смене костюма стоят.
        LayerImage(CompanionLayers.glow, Modifier.graphicsLayer { alpha = body() })
        // Фигура: тени частей под ними, тело, руки (поворот у плеч), всё вместе покачивается.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = time.longValue
                val a = asm.at(t, assembleAt.longValue)
                if (a.figSway) {
                    val st = t - a.swayFrom
                    rotationZ = figRot(poseNow, st)
                    scaleX = figScale(poseNow, st, x = true)
                    scaleY = figScale(poseNow, st, x = false)
                } else {
                    rotationZ = 0f
                    scaleX = 1f
                    scaleY = 1f
                }
                alpha = a.fig * body()
                transformOrigin = TransformOrigin(FIG_PX / BOX_W, FIG_PY / BOX_H)
            },
        ) {
            val armLMod = Modifier.graphicsLayer { rotationZ = armL(poseNow, costumeNow, time.longValue) }
            val armRMod = Modifier.graphicsLayer { rotationZ = armR(poseNow, time.longValue) }
            LayerImage(CompanionLayers.bodyShadow(costume))
            LayerImage(CompanionLayers.armLShadow(costume), armLMod, pivotX = ARM_L_X, pivotY = ARM_Y)
            LayerImage(CompanionLayers.armRShadow(costume), armRMod, pivotX = ARM_R_X, pivotY = ARM_Y)
            LayerImage(CompanionLayers.body(costume))
            LayerImage(CompanionLayers.armL(costume), armLMod, pivotX = ARM_L_X, pivotY = ARM_Y)
            LayerImage(CompanionLayers.armR(costume), armRMod, pivotX = ARM_R_X, pivotY = ARM_Y)
        }
        // Голова: корпус, экран (кивает в конце речи), наряд — вместе покачиваются (3,6 с).
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = time.longValue
                val dy = headDy(t)
                headBob.floatValue = dy
                translationY = dy * density.density
                rotationZ = headRot(t)
                transformOrigin = TransformOrigin(HEAD_PX / BOX_W, HEAD_PY / BOX_H)
            },
        ) {
            LayerImage(
                CompanionLayers.shell,
                Modifier.graphicsLayer {
                    val a = asm.at(time.longValue, assembleAt.longValue)
                    scaleX = a.shellScale
                    scaleY = a.shellScale
                    alpha = a.shell * body()
                },
                pivotX = SHELL_PX,
                pivotY = SHELL_PY,
            )
            LayerImage(CompanionLayers.screen, Modifier.graphicsLayer { translationY = (nod?.floatValue ?: 0f) * density.density })
            CompanionLayers.headX(costume)?.let { x ->
                LayerImage(x, Modifier.graphicsLayer { alpha = asm.at(time.longValue, assembleAt.longValue).x * body() })
            }
        }
        // Корона-система космонавта: отдельно от головы, плавает сама; планеты летают по орбитам.
        if (costume == Costume.GALAXY) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = haloDy(time.longValue) * density.density
                    alpha = asm.at(time.longValue, assembleAt.longValue).x * body()
                },
            ) {
                LayerImage(CompanionLayers.orbit)
                Canvas(Modifier.fillMaxSize()) { drawPlanets(time.longValue) }
            }
        }
    }
}

/** Слой на своём месте рамки; [pivotX]/[pivotY] — точка поворота в рамке (dp), если слой вращается. */
@Composable
private fun LayerImage(layer: Layer, modifier: Modifier = Modifier, pivotX: Float = Float.NaN, pivotY: Float = Float.NaN) {
    val origin = if (pivotX.isNaN()) null else TransformOrigin((pivotX - layer.x) / layer.w, (pivotY - layer.y) / layer.h)
    Image(
        painterResource(layer.res),
        contentDescription = null,
        modifier = Modifier
            .offset(layer.x.dp, layer.y.dp)
            .size(layer.w.dp, layer.h.dp)
            .then(if (origin != null) Modifier.graphicsLayer { transformOrigin = origin } else Modifier)
            .then(modifier),
    )
}

/**
 * Точки костюмов и название под роботом (прототип `.skin-dots`): название 20 / 600 #5B6475, под ним через 6 —
 * точки 10 × 10 через 10 (выбранная — 28 и синяя; ширина и цвет — .3 с, cubic-bezier(.3, 1.3, .5, 1)). Касание —
 * по ближайшей точке, зона выше и ниже ряда невидимо расширена (ряд остаётся 10 dp).
 */
@Composable
fun CostumeDots(selected: Costume, names: List<String>, onSelect: (Costume) -> Unit, modifier: Modifier = Modifier) {
    val pick by rememberUpdatedState(onSelect)
    val selectedNow by rememberUpdatedState(selected)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            names.getOrNull(selected.ordinal) ?: selected.label,
            style = HubType.Caption.copy(color = HubColors.Muted, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .layout { m, c ->
                    // Зона касания 44 dp по высоте, в разметке — 10 dp.
                    val touch = 44.dp.roundToPx()
                    val row = 10.dp.roundToPx()
                    val p = m.measure(c.copy(minHeight = touch, maxHeight = touch))
                    layout(p.width, row) { p.place(0, (row - touch) / 2) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        // Ближайшая точка по x: шаг 20 dp (10 + 10), выбранная шире.
                        var best = 0
                        var bestD = Float.MAX_VALUE
                        var x = 0f
                        Costume.entries.forEachIndexed { i, c ->
                            val w = (if (c == selectedNow) 28.dp else 10.dp).toPx()
                            val d = abs(o.x - (x + w / 2f))
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                            x += w + 10.dp.toPx()
                        }
                        pick(Costume.entries[best])
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Costume.entries.forEach { c ->
                val on = c == selected
                val w by animateDpAsState(if (on) 28.dp else 10.dp, tween(300, easing = DOT_EASE), label = "dotW")
                val color by animateColorAsState(if (on) HubColors.Blue else Color(0xFFCBD6EA), tween(300), label = "dotC")
                Box(Modifier.width(w).height(10.dp).background(color, RoundedCornerShape(5.dp)))
            }
        }
    }
}

// --- сборка (прототип `.ind.bot.assemble`) ---

/**
 * Значения сборки в момент кадра: корпус `shellIn` (.5 с, cubic-bezier(.3, 1.3, .5, 1): прозрачность и масштаб из
 * .86), фигура `partIn` (.45 с ease-out, только прозрачность; 1 с без покачивания — после него покачивание начинается
 * заново), наряд `partIn` (.5 с, задержка .3 с).
 */
private class Assemble {
    var fig = 1f
    var shell = 1f
    var shellScale = 1f
    var x = 1f
    var figSway = true
    var swayFrom = 0L

    fun at(t: Long, start: Long): Assemble {
        val e = t - start
        if (e < 0 || e >= ASSEMBLE_MS) {
            fig = 1f
            shell = 1f
            shellScale = 1f
            x = 1f
            figSway = true
            swayFrom = if (e >= ASSEMBLE_MS) start + ASSEMBLE_MS else 0L
            return this
        }
        val s = SHELL_EASE.transform((e / 500f).coerceIn(0f, 1f))
        shell = s.coerceIn(0f, 1f)
        shellScale = 0.86f + 0.14f * s
        fig = EASE_OUT.transform((e / 450f).coerceIn(0f, 1f))
        x = EASE_OUT.transform(((e - 300f) / 500f).coerceIn(0f, 1f))
        figSway = false
        return this
    }
}

// --- движения прототипа (keyframes CSS), время — мс ---

private fun cycle(t: Long, period: Int): Float = (t % period).toFloat() / period

/** Ключевые кадры CSS: на каждом отрезке — ease-in-out (cubic-bezier(.42, 0, .58, 1)). */
private fun keys(p: Float, at: FloatArray, v: FloatArray): Float {
    for (i in 0 until at.size - 1) {
        if (p <= at[i + 1]) {
            val k = ((p - at[i]) / (at[i + 1] - at[i])).coerceIn(0f, 1f)
            return v[i] + (v[i + 1] - v[i]) * EASE_IN_OUT.transform(k)
        }
    }
    return v.last()
}

/** То же для `alternate` (туда-обратно за два периода). */
private fun alternate(t: Long, period: Int, from: Float, to: Float): Float {
    val n = t / period
    var p = (t % period).toFloat() / period
    if (n % 2 == 1L) p = 1f - p
    return from + (to - from) * EASE_IN_OUT.transform(p)
}

private val SWAY_AT = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/** Фигура: поворот (°) — покачивание `figSway` / «говорит» `figTalk` / «думает» `figThink`. */
private fun figRot(pose: CompanionPose, t: Long): Float = when (pose) {
    CompanionPose.SPEAK -> 0.8f * keys(cycle(t, 900), HALF_AT, ONE_PULSE)
    CompanionPose.THINK -> keys(cycle(t, 2400), HALF_AT, THINK_ROT)
    else -> keys(cycle(t, 4200), SWAY_AT, SWAY_ROT)
}

/** Фигура: масштаб по x / y. */
private fun figScale(pose: CompanionPose, t: Long, x: Boolean): Float = when (pose) {
    CompanionPose.SPEAK -> {
        val k = keys(cycle(t, 900), HALF_AT, ONE_PULSE)
        if (x) 1f + 0.015f * k else 1f - 0.01f * k
    }
    CompanionPose.THINK -> 1f
    else -> {
        val k = keys(cycle(t, 4200), SWAY_AT, SWAY_MID)
        if (x) 1f + 0.012f * k else 1f + 0.018f * k
    }
}

private val HALF_AT = floatArrayOf(0f, 0.5f, 1f)
private val ONE_PULSE = floatArrayOf(0f, 1f, 0f)
private val THINK_ROT = floatArrayOf(-1.8f, 1.8f, -1.8f)
private val SWAY_ROT = floatArrayOf(0f, 1.4f, 0f, -1.4f, 0f)
private val SWAY_MID = floatArrayOf(0f, 0f, 1f, 0f, 0f)

/** Левая рука (для зрителя): качается; слушает — вверх; казахский — держит домбру, говорит — бренчит. */
private fun armL(pose: CompanionPose, costume: Costume, t: Long): Float = when {
    costume == Costume.KAZAKH && pose == CompanionPose.SPEAK -> alternate(t, 450, 2f, 12f)
    costume == Costume.KAZAKH -> keys(cycle(t, 4200), HALF_AT, HOLD)
    pose == CompanionPose.LISTEN -> keys(cycle(t, 1600), HALF_AT, UP_L)
    else -> keys(cycle(t, 4200), HALF_AT, SWING_L)
}

/** Правая рука: качается; слушает — вверх; говорит — машет. */
private fun armR(pose: CompanionPose, t: Long): Float = when (pose) {
    CompanionPose.SPEAK -> keys(cycle(t, 1100), HALF_AT, WAVE)
    CompanionPose.LISTEN -> keys(cycle(t, 1600), HALF_AT, UP_R)
    else -> keys(cycle(t, 4200), HALF_AT, SWING_R)
}

private val HOLD = floatArrayOf(4f, 8f, 4f)
private val UP_L = floatArrayOf(14f, 30f, 14f)
private val SWING_L = floatArrayOf(12f, 18f, 12f)
private val WAVE = floatArrayOf(-14f, -46f, -14f)
private val UP_R = floatArrayOf(-14f, -30f, -14f)
private val SWING_R = floatArrayOf(-12f, -18f, -12f)

/** Голова: сдвиг по y (dp) и наклон (°), 3,6 с. */
private fun headDy(t: Long): Float = keys(cycle(t, 3600), BOB_AT, BOB_DY)
private fun headRot(t: Long): Float = keys(cycle(t, 3600), BOB_AT, BOB_ROT)
private val BOB_AT = floatArrayOf(0f, 0.3f, 0.7f, 1f)
private val BOB_DY = floatArrayOf(0f, -5f, -3f, 0f)
private val BOB_ROT = floatArrayOf(0f, 1.2f, -1.2f, 0f)

/** Корона-система: `haloFloat` 0 → −6 → 0 dp за 3,6 с. */
private fun haloDy(t: Long): Float = keys(cycle(t, 3600), HALF_AT, HALO_DY)
private val HALO_DY = floatArrayOf(0f, -6f, 0f)

// --- планеты короны (прототип: SMIL animateMotion по эллипсам, равномерно по длине дуги) ---

/** Планета: полуоси орбиты, период (мс), сдвиг начала (мс, `begin` с минусом), радиус, цвета градиента. */
private class Planet(val a: Float, val b: Float, val period: Int, val offset: Int, val r: Float, val light: Color, val dark: Color) {
    /** Доля длины дуги → угол: таблица (считается один раз). Старт — левая точка, по часовой стрелке на экране. */
    private val angles = FloatArray(ARC_SAMPLES + 1)

    init {
        val len = FloatArray(ARC_SAMPLES + 1)
        var px = -a
        var py = 0f
        for (i in 1..ARC_SAMPLES) {
            val th = PI_F + 2f * PI_F * i / ARC_SAMPLES
            val x = a * cos(th)
            val y = b * sin(th)
            len[i] = len[i - 1] + sqrt((x - px) * (x - px) + (y - py) * (y - py))
            px = x
            py = y
        }
        val total = len[ARC_SAMPLES]
        var j = 0
        for (i in 0..ARC_SAMPLES) {
            val s = total * i / ARC_SAMPLES
            while (j < ARC_SAMPLES - 1 && len[j + 1] < s) j++
            val f = if (len[j + 1] > len[j]) ((s - len[j]) / (len[j + 1] - len[j])).coerceIn(0f, 1f) else 0f
            angles[i] = PI_F + 2f * PI_F * (j + f) / ARC_SAMPLES
        }
    }

    fun angle(t: Long): Float {
        val f = ((t + offset) % period).toFloat() / period * ARC_SAMPLES
        val i = f.toInt().coerceIn(0, ARC_SAMPLES - 1)
        return angles[i] + (angles[i + 1] - angles[i]) * (f - i)
    }
}

private val PLANETS = arrayOf(
    Planet(34f, 9f, 4_000, 0, 3.2f, Color(0xFFE8E2D8), Color(0xFF8C8478)),
    Planet(52f, 13f, 6_500, 2_000, 4.2f, Color(0xFFFFE0A8), Color(0xFFD08A3A)),
    Planet(72f, 18f, 9_000, 5_000, 5.2f, Color(0xFF9FE0FF), Color(0xFF1C6BD6)),
    Planet(92f, 23f, 12_000, 3_000, 4.2f, Color(0xFFFFB08A), Color(0xFFC2452B)),
    Planet(118f, 30f, 16_000, 9_000, 8.5f, Color(0xFFF6D9B0), Color(0xFFB5783F)),
    Planet(146f, 37f, 21_000, 14_000, 7f, Color(0xFFFFF0B8), Color(0xFFD9A441)),
)

/**
 * Планеты в поле короны 340 × 120 (центр орбит — 170, 60): элемент `.sk-orbit` 230 × 82 в (155, 76) рамки, поворот −3°
 * вокруг своего центра, поле вписано (масштаб 230 / 340, по вертикали по центру).
 */
private fun DrawScope.drawPlanets(t: Long) {
    val u = density
    val k = ORBIT_W / 340f
    translate(ORBIT_X * u, ORBIT_Y * u) {
        rotate(-3f, pivot = Offset(ORBIT_W / 2f * u, ORBIT_H / 2f * u)) {
            translate(0f, (ORBIT_H - 120f * k) / 2f * u) {
                scale(k * u, k * u, pivot = Offset.Zero) {
                    PLANETS.forEachIndexed { i, p ->
                        val th = p.angle(t)
                        val cx = 170f + p.a * cos(th)
                        val cy = 60f + p.b * sin(th)
                        // radialGradient cx 35 % cy 30 %: центр сдвинут вверх-влево, радиус = радиус планеты.
                        drawCircle(
                            Brush.radialGradient(listOf(p.light, p.dark), center = Offset(cx - 0.3f * p.r, cy - 0.4f * p.r), radius = p.r),
                            radius = p.r,
                            center = Offset(cx, cy),
                        )
                        if (i == 2) drawCircle(Color(0xFFE8EEF6), radius = 1.6f, center = Offset(cx + 8f, cy - 3f))
                        if (i == 5) {
                            rotate(-14f, pivot = Offset(cx, cy)) {
                                drawOval(
                                    Color(0xFFF3DFA6),
                                    topLeft = Offset(cx - 14f, cy - 3.85f),
                                    size = androidx.compose.ui.geometry.Size(28f, 7.7f),
                                    style = Stroke(1.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val ARC_SAMPLES = 256
private const val PI_F = Math.PI.toFloat()

/** Корона-система: место элемента в рамке (dp). */
private const val ORBIT_X = 155f
private const val ORBIT_Y = 76f
private const val ORBIT_W = 230f
private const val ORBIT_H = 82f

/** Геометрия слоёв (dp, как px сцены прототипа): рамка 540 × 640 от `#ind` − (100, 120). */
const val BOX_W = 540f
const val BOX_H = 640f

/** Поворот фигуры (50 % 92 % `.bot-fig`), руки у плеч, кивок головы, центр корпуса, центр `#ind` (свайп). */
private const val FIG_PX = 270f
private const val FIG_PY = 517.2f
private const val ARM_L_X = 187f
private const val ARM_R_X = 353f
private const val ARM_Y = 378f
private const val HEAD_PX = 270f
private const val HEAD_PY = 328.8f
private const val SHELL_PX = 270f
private const val SHELL_PY = 236f
private const val SWIPE_PX = 270f
private const val SWIPE_PY = 315f
private const val SWIPE_DP = 60f
private const val SWIPE_BACK_MS = 350
private const val ASSEMBLE_MS = 1_000L

private val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val SHELL_EASE = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)
private val DOT_EASE = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)
private val SWIPE_BACK_EASE = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/** Создать состояние сдвига головы (для глаз в экране головы). */
fun headBobState(): MutableFloatState = mutableFloatStateOf(0f)
