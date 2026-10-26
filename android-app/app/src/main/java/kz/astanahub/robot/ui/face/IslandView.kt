package kz.astanahub.robot.ui.face

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.astanahub.robot.R
import kz.astanahub.robot.places.Directions
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType
import kz.astanahub.robot.ui.screen.HubPill
import kz.astanahub.robot.ui.screen.PillKind
import kz.astanahub.robot.ui.screen.softShadow

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
    /** «Следуйте за мной» — под заголовком поездки. */
    val follow: String = "",
)

/**
 * «Где X» на экране лица — белая карточка прототипа внизу по центру: синяя стрелка в сторону места (от посетителя),
 * место и расстояние, «Проводить», если робот может проводить. Появляется и уходит пружиной от нижнего края;
 * касание карточки — её (жесты лица под ней не начинаются).
 */
@Composable
fun Island(
    content: IslandContent?,
    texts: IslandTexts,
    guideAvailable: Boolean,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val where = content as? IslandContent.WhereIs
    // Пока карточка уходит, содержимое уже null — показываем последнее.
    val last = remember { mutableStateOf(where) }
    SideEffect { if (where != null) last.value = where }
    val shown = where ?: last.value
    val origin = TransformOrigin(0.5f, 1f)
    AnimatedVisibility(
        visible = where != null,
        modifier = modifier,
        enter = fadeIn(HubMotion.Default.compose()) + scaleIn(HubMotion.Default.compose(), initialScale = 0.92f, transformOrigin = origin),
        exit = fadeOut(HubMotion.Default.compose()) + scaleOut(HubMotion.Default.compose(), targetScale = 0.92f, transformOrigin = origin),
    ) {
        if (shown != null) WhereCard(shown, texts, guideAvailable, onGuide)
    }
}

@Composable
private fun WhereCard(c: IslandContent.WhereIs, texts: IslandTexts, guideAvailable: Boolean, onGuide: () -> Unit) {
    val hint = c.hint
    // Посетитель спросил о другом — предложение «Проводить» снято (ViewModel сбросила место), кнопки нет.
    val guide = hint.canGuide && guideAvailable
    val rotation by animateFloatAsState(IslandGeometry.arrowRotationDeg(hint.visitorAngleRad), HubMotion.Default.compose(), label = "arrow")
    val meters = Directions.humanMeters(hint.distanceM)
    val distance = if (meters == null) texts.near else texts.meters.format(meters)
    val shape = RoundedCornerShape(36.dp)
    Row(
        Modifier
            .softShadow(shape, 20.dp, 0.25f, HubColors.Blue)
            .background(Brush.verticalGradient(listOf(Color.White, HubColors.CardBottom)), shape)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .padding(start = 24.dp, end = if (guide) 18.dp else 40.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(80.dp).background(HubColors.Badge, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.mf_guide_arrow),
                contentDescription = null,
                modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        Spacer(Modifier.width(22.dp))
        Column {
            BasicText(
                hint.place,
                style = HubType.Headline.copy(color = HubColors.Navy, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            BasicText(distance, style = HubType.Body.copy(color = HubColors.Muted), maxLines = 1)
        }
        if (guide) {
            Spacer(Modifier.width(32.dp))
            HubPill(texts.guide, onGuide, glyph = R.drawable.ic_m_guide, takeTouch = true)
        }
    }
}

/**
 * Поездка и её итог — слой прототипа поверх лица (`.guide-layer`): светлый фон, синяя стрелка, «Иду к: Кафе»,
 * «Следуйте за мной» и тёмно-синий «Стоп»; итог — «Пришли: Кафе» / «Не получилось дойти» / «Остановились», затем слой
 * уходит (сроки — [IslandModel]). Фон касания не забирает (тап по лицу работает как прежде), «Стоп» — забирает.
 */
@Composable
fun GuideLayer(content: IslandContent?, texts: IslandTexts, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val trip = content.takeIf { it is IslandContent.Trip || it is IslandContent.Outcome }
    val last = remember { mutableStateOf(trip) }
    SideEffect { if (trip != null) last.value = trip }
    val shown = trip ?: last.value
    AnimatedVisibility(
        visible = trip != null,
        modifier = modifier,
        enter = fadeIn(HubMotion.Gentle.compose()),
        exit = fadeOut(HubMotion.Default.compose()),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFFF8FAFE), Color(0xFFE3E9F4)), radius = 1400f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    (fadeIn(HubMotion.Default.compose()) + scaleIn(HubMotion.Default.compose(), initialScale = 0.96f))
                        .togetherWith(fadeOut(HubMotion.Snappy.compose()))
                },
                contentKey = { it?.javaClass },
                label = "guide",
            ) { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.mf_guide_arrow), contentDescription = null, modifier = Modifier.size(150.dp))
                    Spacer(Modifier.height(28.dp))
                    val (title, sub) = when (c) {
                        is IslandContent.Trip -> texts.tripTitle.format(c.place) to texts.follow
                        is IslandContent.Outcome -> when (c.kind) {
                            TripOutcome.ARRIVED -> texts.arrived.format(c.place) to ""
                            TripOutcome.FAILED -> texts.failed to ""
                            TripOutcome.STOPPED -> texts.stopped to ""
                        }
                        else -> "" to ""
                    }
                    BasicText(
                        title,
                        style = HubType.Display.copy(color = HubColors.Navy, textAlign = TextAlign.Center),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 1300.dp),
                    )
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        BasicText(sub, style = HubType.Body.copy(color = HubColors.Muted, textAlign = TextAlign.Center))
                    }
                    if (c is IslandContent.Trip) {
                        Spacer(Modifier.height(36.dp))
                        HubPill(texts.stopTrip, onStop, glyph = R.drawable.ic_m_stop, kind = PillKind.STOP, takeTouch = true)
                    }
                }
            }
        }
    }
}
