package kz.astanahub.robot.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlinx.coroutines.launch
import kz.astanahub.robot.R
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubType

/** Что делает робот-компаньон (прототип: `.ind.listen / .think / .speak`). */
enum class CompanionPose { IDLE, LISTEN, THINK, SPEAK }

/**
 * Робот-компаньон прототипа: подсветка, тело и руки, голова — слои из прототипа (по костюму). Движения — как в его
 * CSS: тело покачивается (4,2 с), руки качаются, слушает — руки вверх, говорит — правая машет и тело «говорит»,
 * думает — тело наклоняется; голова покачивается (3,6 с) — её сдвиг уходит в [headBob] (живые глаза в экране головы
 * двигаются вместе с ней). Смена костюма — робот «собирается» (голова и тело проявляются пружиной).
 * Свайп по роботу влево / вправо — следующий / предыдущий костюм (робот идёт за пальцем ±80 dp), как в прототипе.
 * Кадры анимации — в фазе слоя (graphicsLayer), без рекомпозиции.
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
    /** Видимость корпуса, тела и наряда на переходе лицо ↔ страница (экран головы — у слоя лица). */
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
    // «Сборка» при смене костюма.
    val assembleSignal = assemble
    val assemble = remember { Animatable(1f) }
    LaunchedEffect(assembleSignal) {
        if (reducedMotion || assembleSignal == 0) return@LaunchedEffect
        assemble.snapTo(0f)
        assemble.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
    }
    // Свайп по роботу: смещение за пальцем, отпустили — пружиной на место.
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
                        scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    total += dx
                    val dxDp = with(density) { total.toDp().value }
                    scope.launch { drag.snapTo((dxDp * 0.4f).coerceIn(-80f, 80f)) }
                }
            }
            .graphicsLayer {
                translationX = drag.value * density.density
                rotationZ = (drag.value / 80f * 6f)
                transformOrigin = TransformOrigin(0.5f, 0.85f)
                alpha = bodyAlpha()
            },
    ) {
        Image(painterResource(R.drawable.mf_bot_glow), null, Modifier.fillMaxSize().graphicsLayer { alpha = assemble.value.coerceIn(0f, 1f) })
        // Тело с руками — одна группа (покачивается целиком), руки поворачиваются у плеч.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = time.longValue
                    val a = assemble.value
                    rotationZ = figRot(poseNow, t)
                    scaleX = figScale(poseNow, t, x = true) * (0.8f + 0.2f * a)
                    scaleY = figScale(poseNow, t, x = false) * (0.8f + 0.2f * a)
                    translationY = -50f * (1f - a) * density.density
                    alpha = a.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(FIG_PX / BOX_W, FIG_PY / BOX_H)
                },
        ) {
            Image(painterResource(costume.body), null, Modifier.fillMaxSize())
            Image(
                painterResource(costume.armL),
                null,
                Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = armL(poseNow, costumeNow, time.longValue)
                    transformOrigin = TransformOrigin(ARM_L_X / BOX_W, ARM_Y / BOX_H)
                },
            )
            Image(
                painterResource(costume.armR),
                null,
                Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = armR(poseNow, time.longValue)
                    transformOrigin = TransformOrigin(ARM_R_X / BOX_W, ARM_Y / BOX_H)
                },
            )
        }
        Image(
            painterResource(costume.head),
            null,
            Modifier.fillMaxSize().graphicsLayer {
                val dy = headDy(time.longValue)
                headBob.floatValue = dy
                val a = assemble.value
                translationY = dy * density.density
                rotationZ = headRot(time.longValue)
                scaleX = 0.86f + 0.14f * a
                scaleY = 0.86f + 0.14f * a
                alpha = (a * 1.4f).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(HEAD_PX / BOX_W, HEAD_PY / BOX_H)
            },
        )
    }
}

/** Точки костюмов и название под роботом (прототип `.skin-dots`): выбранная — вытянутая синяя. */
@Composable
fun CostumeDots(selected: Costume, names: List<String>, onSelect: (Costume) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            names.getOrNull(selected.ordinal) ?: selected.label,
            style = HubType.Caption.copy(color = HubColors.Muted, fontWeight = FontWeight.SemiBold),
        )
        Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Costume.entries.forEach { c ->
                val w by animateDpAsState(if (c == selected) 28.dp else 10.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "dot")
                // Зона касания крупнее точки (кнопка, а не пиксель).
                Pressable(onClick = { onSelect(c) }, modifier = Modifier.size(36.dp, 32.dp)) {
                    Box(
                        Modifier
                            .width(w)
                            .height(10.dp)
                            .background(if (c == selected) HubColors.Blue else Color(0xFFCBD6EA), RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
    }
}

// --- движения прототипа (keyframes CSS), время — мс ---

private fun cycle(t: Long, period: Int): Float = (t % period).toFloat() / period

/** Плавный переход между ключевыми кадрами (ease-in-out, как в CSS). */
private fun keys(p: Float, at: FloatArray, v: FloatArray): Float {
    for (i in 0 until at.size - 1) {
        if (p <= at[i + 1]) {
            val k = ((p - at[i]) / (at[i + 1] - at[i])).coerceIn(0f, 1f)
            val e = (1f - cos(PI.toFloat() * k)) / 2f
            return v[i] + (v[i + 1] - v[i]) * e
        }
    }
    return v.last()
}

private val SWAY_AT = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/** Тело: поворот (°) — покачивание / «говорит» / «думает» (без аллокаций в кадре). */
private fun figRot(pose: CompanionPose, t: Long): Float = when (pose) {
    CompanionPose.SPEAK -> 0.8f * keys(cycle(t, 900), HALF_AT, ONE_PULSE)
    CompanionPose.THINK -> keys(cycle(t, 2400), HALF_AT, THINK_ROT)
    else -> keys(cycle(t, 4200), SWAY_AT, SWAY_ROT)
}

/** Тело: масштаб по x / y. */
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

/** Левая рука (для зрителя): качается; слушает — вверх; казахский — держит домбру, говорит — играет. */
private fun armL(pose: CompanionPose, costume: Costume, t: Long): Float = when {
    costume == Costume.KAZAKH && pose == CompanionPose.SPEAK -> keys(cycle(t, 900), HALF_AT, STRUM)
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

private val STRUM = floatArrayOf(2f, 12f, 2f)
private val HOLD = floatArrayOf(4f, 8f, 4f)
private val UP_L = floatArrayOf(12f, 28f, 12f)
private val SWING_L = floatArrayOf(10f, 18f, 10f)
private val WAVE = floatArrayOf(-14f, -44f, -14f)
private val UP_R = floatArrayOf(-12f, -28f, -12f)
private val SWING_R = floatArrayOf(-10f, -18f, -10f)

/** Голова: сдвиг по y (dp) и наклон (°), 3,6 с. */
private fun headDy(t: Long): Float = keys(cycle(t, 3600), BOB_AT, BOB_DY)
private fun headRot(t: Long): Float = keys(cycle(t, 3600), BOB_AT, BOB_ROT)
private val BOB_AT = floatArrayOf(0f, 0.3f, 0.7f, 1f)
private val BOB_DY = floatArrayOf(0f, -5f, -3f, 0f)
private val BOB_ROT = floatArrayOf(0f, 1.2f, -1.2f, 0f)

/** Геометрия слоёв (dp, как px сцены прототипа): рамка картинки 540 × 580 от (10, 53) сцены. */
const val BOX_W = 540f
const val BOX_H = 580f
private const val FIG_PX = 270f
private const val FIG_PY = 514f
private const val ARM_L_X = 187f
private const val ARM_R_X = 353f
private const val ARM_Y = 378f
private const val HEAD_PX = 270f
private const val HEAD_PY = 328.8f
private const val SWIPE_DP = 60f

/** Создать состояние сдвига головы (для глаз в экране головы). */
fun headBobState(): MutableFloatState = mutableFloatStateOf(0f)
