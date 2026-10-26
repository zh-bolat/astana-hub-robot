package kz.astanahub.robot.ui.face

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kz.astanahub.robot.R
import kz.astanahub.robot.places.Directions
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Подписи острова на текущем языке (собирает MainActivity). Форматы — `String.format` с одним аргументом. */
@Immutable
data class IslandTexts(
    /** «Иду к: %1$s». */
    val tripTitle: String = "%1\$s",
    val stopTrip: String = "",
    val guide: String = "",
    /** «Пришли: %1$s». */
    val arrived: String = "%1\$s",
    val failed: String = "",
    val stopped: String = "",
    /** «%1$d м». */
    val meters: String = "%1\$d",
    val near: String = "",
)

/**
 * «Остров» экрана лица в духе Dynamic Island: одна чёрная пилюля внизу по центру, содержимое перетекает
 * (where_is → поездка → итог), ширина меняется пружиной, высота постоянна (96 dp).
 * - Появление — «материализуется»: scale 0.92 → 1 и прозрачность пружиной Default от нижнего края; уход — тем же
 *   путём, мягко (последнее содержимое держится, пока пилюля гаснет).
 * - Смена содержимого — `AnimatedContent`: новое проявляется (fade + scale 0.96 → 1), старое гаснет быстрее, размер
 *   — пружиной Default (ζ 1, 0.35 с), без обреза.
 * - Касание пилюли — её (кнопки «Остановить» / «Проводить» потребляют касание, сама пилюля тоже): жесты лица под
 *   ней не начинаются (ревью M2-r1, B1).
 */
@Composable
fun Island(
    content: IslandContent?,
    texts: IslandTexts,
    onStop: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Пока остров уходит, содержимое уже null — показываем последнее (запоминаем после композиции).
    val last = remember { mutableStateOf(content) }
    SideEffect { if (content != null) last.value = content }
    val shown = content ?: last.value
    val origin = TransformOrigin(0.5f, 1f)
    AnimatedVisibility(
        visible = content != null,
        modifier = modifier,
        enter = fadeIn(HubMotion.Default.compose()) +
            scaleIn(HubMotion.Default.compose(), initialScale = 0.92f, transformOrigin = origin),
        exit = fadeOut(HubMotion.Default.compose()) +
            scaleOut(HubMotion.Default.compose(), targetScale = 0.92f, transformOrigin = origin),
    ) {
        val shape = RoundedCornerShape(percent = 50)
        Box(
            Modifier
                .background(HubColors.Island, shape)
                .border(1.dp, HubColors.Hairline, shape)
                .pointerInput(Unit) {
                    // Касание пилюли — её: жест лица (удержание, тап, свайп) под ней не начинается.
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                },
        ) {
            AnimatedContent(
                targetState = shown,
                contentKey = { key(it) },
                transitionSpec = {
                    (fadeIn(HubMotion.Default.compose()) + scaleIn(HubMotion.Default.compose(), initialScale = 0.96f))
                        .togetherWith(fadeOut(HubMotion.Snappy.compose()))
                        .using(
                            SizeTransform(clip = false) { _, _ ->
                                spring(1f, HubMotion.Default.stiffness, IntSize.VisibilityThreshold)
                            },
                        )
                },
                label = "island",
            ) { c ->
                when (c) {
                    is IslandContent.WhereIs -> WhereRow(c, texts, onGuide)
                    is IslandContent.Trip -> TripRow(c, texts, onStop)
                    is IslandContent.Outcome -> OutcomeRow(c, texts)
                    null -> Spacer(Modifier.height(ISLAND_HEIGHT))
                }
            }
        }
    }
}

private fun key(c: IslandContent?): Any = when (c) {
    is IslandContent.WhereIs -> "where:${c.hint.seq}"
    is IslandContent.Trip -> "trip"
    is IslandContent.Outcome -> "outcome:${c.kind}"
    null -> "none"
}

/** «Где»: стрелка в сторону места (от посетителя), место, расстояние; «Проводить», если можно. */
@Composable
private fun WhereRow(c: IslandContent.WhereIs, texts: IslandTexts, onGuide: () -> Unit) {
    val hint = c.hint
    val rotation by animateFloatAsState(
        IslandGeometry.arrowRotationDeg(hint.visitorAngleRad),
        HubMotion.Default.compose(),
        label = "arrow",
    )
    val meters = Directions.humanMeters(hint.distanceM)
    val distance = if (meters == null) texts.near else texts.meters.format(meters)
    Row(
        Modifier
            .height(ISLAND_HEIGHT)
            .padding(start = 32.dp, end = if (hint.canGuide) 12.dp else 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_m_direction),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HubColors.White),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { rotationZ = rotation },
        )
        Spacer(Modifier.width(20.dp))
        BasicText(
            hint.place,
            style = HubType.Headline.copy(color = HubColors.Label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.width(18.dp))
        BasicText(distance, style = HubType.Headline.copy(color = HubColors.LabelSecondary), maxLines = 1)
        if (hint.canGuide) {
            Spacer(Modifier.width(32.dp))
            PillButton(label = texts.guide, onClick = onGuide)
        }
    }
}

/** Поездка: «Иду к: Кафе» + «Остановить». */
@Composable
private fun TripRow(c: IslandContent.Trip, texts: IslandTexts, onStop: () -> Unit) {
    Row(
        Modifier
            .height(ISLAND_HEIGHT)
            .padding(start = 32.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_m_guide),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HubColors.White),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(18.dp))
        BasicText(
            texts.tripTitle.format(c.place),
            style = HubType.Headline.copy(color = HubColors.Label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        Spacer(Modifier.width(32.dp))
        PillButton(label = texts.stopTrip, onClick = onStop)
    }
}

/** Итог поездки: коротко, формой (галочка / крестик / квадрат «стоп»), без цвета. */
@Composable
private fun OutcomeRow(c: IslandContent.Outcome, texts: IslandTexts) {
    val (glyph, text) = when (c.kind) {
        TripOutcome.ARRIVED -> R.drawable.ic_m_check to texts.arrived.format(c.place)
        TripOutcome.FAILED -> R.drawable.ic_m_close to texts.failed
        TripOutcome.STOPPED -> R.drawable.ic_m_stop to texts.stopped
    }
    Row(
        Modifier
            .height(ISLAND_HEIGHT)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(glyph),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HubColors.White),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(18.dp))
        BasicText(
            text,
            style = HubType.Headline.copy(color = HubColors.Label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 760.dp),
        )
    }
}

private val ISLAND_HEIGHT = 96.dp
