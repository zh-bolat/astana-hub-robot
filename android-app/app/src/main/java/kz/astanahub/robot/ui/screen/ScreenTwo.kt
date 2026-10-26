package kz.astanahub.robot.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.astanahub.robot.R
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.SubtitlePacer
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType

/** Подписи экрана 2 на текущем языке (собирает MainActivity из ресурсов ru / kk / en). */
@Immutable
data class ScreenTexts(
    val title: String = "Astana Hub",
    val greetTitle: String = "",
    val greetSub: String = "",
    val tilesTitle: String = "",
    val askVoice: String = "",
    val stop: String = "",
    val inputHint: String = "",
    val faqTitle: String = "",
    val showMore: String = "",
    val showLess: String = "",
    val guideShort: String = "",
    val offline: String = "",
    val micOff: String = "",
    val exitQuestion: String = "",
    val exitSub: String = "",
    val exitYes: String = "",
    val exitNo: String = "",
    /** «Источник: %1$s» / «Источники: %1$s». */
    val sourceOne: String = "%1\$s",
    val sourceMany: String = "%1\$s",
    val back: String = "",
)

/** Частые вопросы (аккордеон по страницам, как на прежнем экране): что раскрыто и что в нём показать. */
@Immutable
data class FaqUi(
    val items: List<QuickQuestion>,
    val openId: String?,
    /** Ответ на раскрытый вопрос; null — ещё готовится ([pending]). */
    val answer: String?,
    val pending: String,
    val guide: Boolean,
    val more: String?,
)

/** Что показывает экран 2 — пишет MainActivity из MainUiState (главный поток), Compose читает. */
@Stable
class ScreenTwoState {
    var texts by mutableStateOf(ScreenTexts())
    var lang by mutableStateOf(Lang.RU)

    /** Строка состояния под роботом: «Слушаю…», «Думаю…», «Готов помочь». */
    var status by mutableStateOf("")
    var chat by mutableStateOf<List<ChatItem>>(emptyList())

    /** Посетитель говорит прямо сейчас (промежуточный текст ASR). */
    var partial by mutableStateOf<String?>(null)

    /** Реплика робота, которая звучит сейчас, и её текущая фраза — живые субтитры (слова «зажигаются» по темпу). */
    var speakingId by mutableStateOf<Long?>(null)
    var phrase by mutableStateOf<String?>(null)

    /** Думает (ждём ответ) — точки под вопросом. */
    var thinking by mutableStateOf(false)

    /** Занят (думает / говорит / смотрит): вместо «Спросить голосом» — «Стоп», плитки приглушены. */
    var busy by mutableStateOf(false)
    var tilesEnabled by mutableStateOf(true)
    var tiles by mutableStateOf<List<Tile>>(emptyList())
    var faq by mutableStateOf<FaqUi?>(null)

    /** «Проводить: Кафе» под последним ответом (место, до которого робот может проводить); null — нет. */
    var guideLabel by mutableStateOf<String?>(null)

    /** Живой режим, where_is: направление по карте («Кафе — справа, 15 м»). */
    var wayHint by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)
    var micOff by mutableStateOf(false)

    /** Поле ввода текстом — только эмулятор (на роботе — голос). */
    var textInput by mutableStateOf(false)
    var exitConfirm by mutableStateOf(false)
    var buildLabel by mutableStateOf("")
    var diagnosticsHold by mutableStateOf(false)
    var reducedMotion by mutableStateOf(false)

    /** Костюм робота-компаньона (только вид). */
    var costume by mutableStateOf(Costume.CLASSIC)

    /** Палец на роботе-компаньоне: отклик на touch-down рисует слой лица (глаза сжимаются). */
    val miniPressed = mutableStateOf(false)

    /** Есть полоса предупреждения под панелью (нет связи / микрофон) — основная зона ниже на её высоту. */
    val notice: String?
        get() = when {
            offline -> texts.offline
            micOff -> texts.micOff
            else -> null
        }
}

/** Действия экрана 2 → MainViewModel (прежнее поведение кнопок старого экрана). */
interface ScreenTwoActions {
    fun onTile(tile: Tile)
    fun onLanguage(lang: Lang)
    fun onAskVoice()
    fun onStop()
    fun onSend(text: String)
    fun onGuide()
    fun onFaqToggle(item: QuickQuestion)
    fun onFaqMore()
    fun onFaqBack()
    fun onExit()
    fun onExitConfirm()
    fun onExitCancel()
    fun onDiagnostics()

    /** К лицу (тап по роботу-компаньону). */
    fun onClose()
}

/**
 * Геометрия страницы приложения (прототип, dp = px сцены 1600 × 900): панели, колонки и робот-компаньон, в экран
 * головы которого садится живое лицо ([RobotScreens], [FaceMorph]).
 */
object ScreenTwoLayout {
    val TopBarHeight = 88.dp
    val BottomBarHeight = 120.dp
    val LeftWidth = 560.dp

    /** Картинка робота в левой колонке (от верха основной зоны). */
    val RobotX = 20.dp
    val RobotY = 2.dp
    val RobotSize = 520.dp

    /** Чёрный экран головы и область глаз в нём (от верха основной зоны). */
    val ScreenX = 144.dp
    val ScreenY = 102.dp
    val ScreenW = 272.dp
    val ScreenH = 186.dp
    val EyesX = 165.dp
    val EyesY = 138.dp
    val EyesW = 230.dp
    val EyesH = 115.dp

    /** Голова целиком (касание — назад к лицу). */
    val HeadX = 124.dp
    val HeadY = 82.dp
    val HeadW = 312.dp
    val HeadH = 232.dp
}

/**
 * Экран 2 — страница приложения в виде прототипа: белый фон; сверху панель (назад, Astana Hub, язык, выход), под ней
 * при необходимости янтарная полоса «нет связи» / «микрофон»; слева робот-компаньон (в его экране живут глаза) с
 * состоянием и пузырём субтитров; справа приветствие и плитки, разговор карточками или частые вопросы; снизу —
 * «Спросить голосом» / «Стоп». [progress] — переход (0 — лицо, 1 — экран 2): правая колонка чуть отстаёт.
 */
@Composable
fun ScreenTwo(state: ScreenTwoState, actions: ScreenTwoActions, progress: () -> Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(HubColors.Background)) {
        TopBar(state, actions)
        val notice = state.notice
        AnimatedVisibility(
            visible = notice != null,
            enter = expandVertically(HubMotion.Default.compose()) + fadeIn(HubMotion.Default.compose()),
            exit = shrinkVertically(HubMotion.Default.compose()) + fadeOut(HubMotion.Snappy.compose()),
        ) {
            NoticeBar(notice ?: state.texts.offline)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LeftColumn(state, actions, Modifier.width(ScreenTwoLayout.LeftWidth).fillMaxHeight())
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .parallax(progress, 0.09f)
                    .padding(start = 24.dp, top = 32.dp, end = 48.dp, bottom = 16.dp),
            ) {
                val faq = state.faq
                when {
                    faq != null -> FaqPanel(state, faq, actions)
                    state.chat.isEmpty() && state.partial == null && !state.thinking -> Home(state, actions)
                    else -> Conversation(state, actions)
                }
            }
        }
        BottomBar(state, actions, Modifier.parallax(progress, 0.04f))
    }
}

/** Отставание элемента при переходе: пока страница открывается, он ещё чуть правее — глубина без лишних движений. */
private fun Modifier.parallax(progress: () -> Float, depth: Float): Modifier = graphicsLayer {
    translationX = (1f - progress().coerceIn(0f, 1f)) * size.width * depth * 4f
}

@Composable
private fun TopBar(state: ScreenTwoState, actions: ScreenTwoActions) {
    val texts = state.texts
    Row(
        Modifier
            .fillMaxWidth()
            .height(ScreenTwoLayout.TopBarHeight)
            .background(Color.White.copy(alpha = 0.92f))
            .bottomLine(HubColors.Line)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // «Назад» — там, где есть куда вернуться внутри страницы (частые вопросы → плитки).
        AnimatedVisibility(state.faq != null, enter = fadeIn(HubMotion.Snappy.compose()), exit = fadeOut(HubMotion.Snappy.compose())) {
            BackPill(texts.back, actions::onFaqBack, Modifier.padding(end = 20.dp))
        }
        Image(
            painter = painterResource(R.drawable.mf_brand_astana_hub),
            contentDescription = texts.title,
            modifier = Modifier
                .height(60.dp)
                .width(225.dp)
                .holdToActivate(state.diagnosticsHold, DIAG_HOLD_MILLIS, actions::onDiagnostics),
        )
        Spacer(Modifier.weight(1f))
        LanguageSwitch(state.lang, actions::onLanguage)
        Spacer(Modifier.width(28.dp))
        Box(Modifier.width(2.dp).height(44.dp).background(HubColors.Line))
        Spacer(Modifier.width(28.dp))
        CircleButton(R.drawable.ic_m_close, actions::onExit)
    }
}

private fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx())
}

/**
 * Левая колонка: робот-компаньон (картинка костюма; экран головы — место живого лица) и под ним состояние и то, что
 * услышано; пока робот говорит — пузырь субтитров с хвостиком к роботу, слова фразы зажигаются по темпу речи.
 */
@Composable
private fun LeftColumn(state: ScreenTwoState, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(state.costume.image),
            contentDescription = null,
            modifier = Modifier
                .offset(ScreenTwoLayout.RobotX, ScreenTwoLayout.RobotY)
                .size(ScreenTwoLayout.RobotSize),
        )
        // Тап по голове робота — к лицу (на касание глаза сжимаются: их рисует слой лица).
        Pressable(
            onClick = actions::onClose,
            pressed = state.miniPressed,
            modifier = Modifier
                .offset(ScreenTwoLayout.HeadX, ScreenTwoLayout.HeadY)
                .size(ScreenTwoLayout.HeadW, ScreenTwoLayout.HeadH),
        ) {}
        val phrase = state.phrase.takeIf { state.speakingId != null }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = BELOW_ROBOT, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = phrase != null,
                transitionSpec = {
                    (fadeIn(HubMotion.Default.compose()) + scaleIn(HubMotion.Default.compose(), initialScale = 0.97f))
                        .togetherWith(fadeOut(HubMotion.Snappy.compose()))
                },
                label = "leftInfo",
            ) { speaking ->
                if (speaking) {
                    SubtitleBubble(state.phrase.orEmpty())
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(
                            state.status,
                            style = HubType.Body.copy(color = HubColors.Muted, fontSize = HubType.Headline.fontSize, textAlign = TextAlign.Center),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val heard = state.partial
                        if (heard != null) {
                            Spacer(Modifier.height(16.dp))
                            BasicText(
                                "«$heard»",
                                style = HubType.Callout.copy(color = HubColors.Muted, textAlign = TextAlign.Center),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 460.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Пузырь субтитров прототипа: белый, кромка, мягкая синяя тень, хвостик вверх к роботу; несказанные слова — бледнее. */
@Composable
private fun SubtitleBubble(phrase: String) {
    val spoken = spokenWords(phrase)
    val text = remember(phrase, spoken) { liveText(phrase, phrase, spoken) }
    val shape = RoundedCornerShape(22.dp)
    Box(contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 12.dp)
                .width(496.dp)
                .heightIn(min = 110.dp)
                .softShadow(shape, 10.dp, 0.12f, HubColors.Blue)
                .background(Color.White, shape)
                .border(1.dp, HubColors.SubBorder, shape)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text, style = HubType.Body.copy(color = HubColors.Ink, textAlign = TextAlign.Center))
        }
        // Хвостик: повёрнутый квадрат с кромкой сверху-слева.
        Canvas(Modifier.size(28.dp).offset(y = 0.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.5f, 0f)
                lineTo(size.width, size.height * 0.5f)
                lineTo(0f, size.height * 0.5f)
                close()
            }
            drawPath(path, Color.White)
            drawLine(HubColors.SubBorder, Offset(size.width * 0.5f, 0f), Offset(0f, size.height * 0.5f), strokeWidth = 1.dp.toPx())
            drawLine(HubColors.SubBorder, Offset(size.width * 0.5f, 0f), Offset(size.width, size.height * 0.5f), strokeWidth = 1.dp.toPx())
        }
    }
}

/** Главная: приветствие и плитки 3 × 2 (как в прототипе). */
@Composable
private fun Home(state: ScreenTwoState, actions: ScreenTwoActions) {
    Column(Modifier.fillMaxSize()) {
        BasicText(state.texts.greetTitle, style = HubType.Display.copy(color = HubColors.Navy), modifier = Modifier.appear(reducedMotion = state.reducedMotion))
        Spacer(Modifier.height(8.dp))
        BasicText(state.texts.greetSub, style = HubType.Body.copy(color = HubColors.Muted))
        Spacer(Modifier.height(36.dp))
        TileGrid(state, actions)
    }
}

@Composable
private fun TileGrid(state: ScreenTwoState, actions: ScreenTwoActions) {
    val tiles = state.tiles
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        tiles.chunked(TILE_COLUMNS).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                chunk.forEachIndexed { col, tile ->
                    androidx.compose.runtime.key(tile.key) {
                        TileCard(
                            tile = tile,
                            enabled = state.tilesEnabled,
                            onClick = { actions.onTile(tile) },
                            modifier = Modifier
                                .weight(1f)
                                .height(TILE_HEIGHT)
                                .appear(order = row * TILE_COLUMNS + col, reducedMotion = state.reducedMotion),
                        )
                    }
                }
                repeat(TILE_COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Плитка прототипа: белая карточка с мягкой тенью, значок в голубом «бейдже», подпись справа. */
@Composable
private fun TileCard(tile: Tile, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .card(shape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(badgeRes(tile.glyph)), contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(16.dp))
            BasicText(
                tile.label,
                style = HubType.Tile.copy(color = HubColors.Ink),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Белая карточка прототипа: градиент к низу, кромка 1 dp, мягкая тень. */
private fun Modifier.card(shape: androidx.compose.ui.graphics.Shape): Modifier =
    softShadow(shape, 12.dp, 0.10f)
        .background(Brush.verticalGradient(listOf(Color.White, HubColors.CardBottom)), shape)
        .border(1.dp, HubColors.CardBorder, shape)

@DrawableRes
private fun badgeRes(g: TileGlyph): Int = when (g) {
    TileGlyph.PLACE -> R.drawable.mf_tile_place
    TileGlyph.EVENT -> R.drawable.mf_tile_event
    TileGlyph.INFO -> R.drawable.mf_tile_info
    TileGlyph.WIFI -> R.drawable.mf_tile_wifi
    TileGlyph.CHAT -> R.drawable.mf_tile_chat
    TileGlyph.FAQ -> R.drawable.mf_tile_help
    TileGlyph.LOOK -> R.drawable.mf_tile_camera
}

@DrawableRes
private fun glyphRes(g: TileGlyph): Int = when (g) {
    TileGlyph.PLACE -> R.drawable.ic_m_place
    TileGlyph.EVENT -> R.drawable.ic_m_event
    TileGlyph.INFO -> R.drawable.ic_m_info
    TileGlyph.WIFI -> R.drawable.ic_m_wifi
    TileGlyph.CHAT -> R.drawable.ic_m_chat
    TileGlyph.FAQ -> R.drawable.ic_m_help
    TileGlyph.LOOK -> R.drawable.ic_m_camera
}

/**
 * Разговор: вопрос посетителя строкой «…», ответ робота — белая карточка крупным текстом (прототип `.answer`), под
 * последним — источник и «Проводить». Новое снизу (растущий ответ у нижнего края). Ниже — плитки чипами: темы
 * остаются под рукой и во время разговора.
 */
@Composable
private fun Conversation(state: ScreenTwoState, actions: ScreenTwoActions) {
    val chat = state.chat
    val listState = rememberLazyListState()
    val lastId = chat.lastOrNull()?.id
    val tail = state.partial != null || state.thinking
    LaunchedEffect(lastId, tail) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    val lastRobotId = chat.lastOrNull { it.role == ChatRole.ROBOT }?.id
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
            ) {
                if (tail) {
                    item(key = "tail") {
                        Box(Modifier.animateItem(fadeInSpec = HubMotion.Default.compose(), fadeOutSpec = HubMotion.Snappy.compose())) {
                            val partial = state.partial
                            if (partial != null) QuestionLine("«$partial…»", live = true) else TypingDots()
                        }
                    }
                }
                items(chat.asReversed(), key = { it.id }, contentType = { it.role }) { item ->
                    Box(
                        Modifier.animateItem(
                            fadeInSpec = HubMotion.Default.compose(),
                            placementSpec = HubMotion.Default.compose(),
                            fadeOutSpec = HubMotion.Snappy.compose(),
                        ),
                    ) {
                        when (item.role) {
                            ChatRole.VISITOR -> QuestionLine("«${item.text}»", live = false)
                            ChatRole.ROBOT -> AnswerCard(
                                item = item,
                                speaking = item.id == state.speakingId,
                                phrase = state.phrase,
                                texts = state.texts,
                                guideLabel = state.guideLabel.takeIf { item.id == lastRobotId },
                                onGuide = actions::onGuide,
                            )
                        }
                    }
                }
            }
            // Верх списка растворяется в белом (история уходит под панель мягко).
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(CHAT_FADE)
                    .background(Brush.verticalGradient(listOf(HubColors.Background, HubColors.Background.copy(alpha = 0f)))),
            )
        }
        Spacer(Modifier.height(16.dp))
        TileChips(state, actions)
    }
}

@Composable
private fun QuestionLine(text: String, live: Boolean) {
    BasicText(
        text,
        style = HubType.Body.copy(color = HubColors.Muted),
        modifier = Modifier.graphicsLayer { alpha = if (live) 0.7f else 1f },
    )
}

/**
 * Ответ робота: карточка прототипа, текст 40 sp. Пока звучит — живые субтитры: слова текущей фразы зажигаются по темпу
 * речи ([SubtitlePacer]), ещё не сказанные — бледнее. Под ответом — источник (`citations`) и «Проводить».
 */
@Composable
private fun AnswerCard(
    item: ChatItem,
    speaking: Boolean,
    phrase: String?,
    texts: ScreenTexts,
    guideLabel: String?,
    onGuide: () -> Unit,
) {
    val active = speaking && phrase != null && item.text.endsWith(phrase)
    val spoken = spokenWords(if (active) phrase else null)
    val text: AnnotatedString = if (active) {
        remember(item.text, phrase, spoken) { liveText(item.text, phrase, spoken) }
    } else {
        AnnotatedString(item.text)
    }
    val caption = remember(item.citations, texts) {
        Tiles.sourceCaption(item.citations, { texts.sourceOne.format(it) }, { texts.sourceMany.format(it) })
    }
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .card(shape)
                .then(if (speaking) Modifier.animateContentSize(HubMotion.Default.compose()) else Modifier)
                .padding(horizontal = 32.dp, vertical = 28.dp),
        ) {
            BasicText(text, style = HubType.Answer.copy(color = HubColors.Ink))
        }
        if (caption != null) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                caption,
                style = HubType.Caption.copy(color = HubColors.Faint),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp).appear(),
            )
        }
        if (guideLabel != null) {
            Spacer(Modifier.height(18.dp))
            HubPill(guideLabel, onGuide, glyph = R.drawable.ic_m_guide, modifier = Modifier.appear())
        }
    }
}

private fun liveText(full: String, phrase: String, spoken: Int): AnnotatedString = buildAnnotatedString {
    append(full.substring(0, full.length - phrase.length))
    val words = phrase.split(' ')
    var count = 0
    words.forEachIndexed { i, w ->
        if (i > 0) append(' ')
        if (w.isEmpty()) return@forEachIndexed
        if (count < spoken) append(w) else withStyle(SpanStyle(color = HubColors.Faint)) { append(w) }
        count++
    }
}

private val pacer = SubtitlePacer()

/** Сколько слов фразы уже «сказано» (по темпу речи); null — фраза не звучит. Пересчёт — раз в ~50 мс. */
@Composable
private fun spokenWords(phrase: String?): Int {
    val plan = remember(phrase) { phrase?.let(pacer::plan).orEmpty() }
    var spoken by remember(phrase) { mutableIntStateOf(0) }
    LaunchedEffect(plan) {
        if (plan.isEmpty()) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (spoken < plan.size) {
            val t = withFrameMillis { it } - start
            val n = plan.indexOfLast { it.startMillis <= t } + 1
            if (n != spoken) spoken = n
            delay(SPOKEN_TICK_MS)
        }
    }
    return spoken
}

/** «Думаю»: три синие точки с бегущей волной прозрачности (прототип `.dots`). */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(TYPING_PERIOD_MS), RepeatMode.Restart), label = "typingPhase")
    Row(Modifier.padding(vertical = 16.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        val d = ((phase - i * 0.18f) % 1f + 1f) % 1f
                        alpha = 0.3f + 0.7f * (1f - kotlin.math.abs(d * 2f - 1f))
                    }
                    .background(HubColors.Blue, CircleShape),
            )
        }
    }
}

/** Плитки чипами (прототип `.chip`) — во время разговора: синий текст, голубая заливка, значок слева. */
@Composable
private fun TileChips(state: ScreenTwoState, actions: ScreenTwoActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.tiles.forEach { tile ->
            androidx.compose.runtime.key(tile.key) {
                Chip(tile.label, glyphRes(tile.glyph), enabled = state.tilesEnabled, onClick = { actions.onTile(tile) })
            }
        }
    }
}

@Composable
private fun Chip(label: String, @DrawableRes glyph: Int, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(72.dp)
            .background(HubColors.ChipBg, shape)
            .border(1.dp, HubColors.ChipBorder, shape)
            .padding(horizontal = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, 30.dp, HubColors.BlueText)
            Spacer(Modifier.width(10.dp))
            BasicText(
                label,
                style = HubType.Callout.copy(color = HubColors.BlueText, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Частые вопросы (прототип: заголовок и белые строки-аккордеон, раскрытая — синяя кромка): раскрыт не больше одного,
 * в раскрытом — ответ (или «Думаю…»), «Показать полностью» и «Проводить»; внизу — «Ещё вопросы (1/3)». «Назад» — в
 * верхней панели.
 */
@Composable
private fun FaqPanel(state: ScreenTwoState, faq: FaqUi, actions: ScreenTwoActions) {
    val texts = state.texts
    Column(Modifier.fillMaxSize()) {
        BasicText(texts.faqTitle, style = HubType.Title.copy(color = HubColors.Navy), maxLines = 1)
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            faq.items.forEachIndexed { i, item ->
                androidx.compose.runtime.key(item.id) {
                    FaqRow(
                        item = item,
                        open = item.id == faq.openId,
                        answer = faq.answer.takeIf { item.id == faq.openId },
                        pending = faq.pending,
                        guide = faq.guide && item.id == faq.openId,
                        texts = texts,
                        actions = actions,
                        modifier = Modifier.appear(order = i, reducedMotion = state.reducedMotion),
                    )
                }
            }
        }
        val more = faq.more
        if (more != null) {
            Spacer(Modifier.height(16.dp))
            HubPill(more, actions::onFaqMore, primary = false, glyph = R.drawable.ic_m_chevron)
        }
    }
}

@Composable
private fun FaqRow(
    item: QuickQuestion,
    open: Boolean,
    answer: String?,
    pending: String,
    guide: Boolean,
    texts: ScreenTexts,
    actions: ScreenTwoActions,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val chevron by animateFloatAsState(if (open) 180f else 0f, HubMotion.Default.compose(), label = "chevron")
    var expanded by remember(answer) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(shape, if (open) 14.dp else 10.dp, if (open) 0.14f else 0.08f, if (open) HubColors.Blue else HubColors.Shadow)
            .background(
                if (open) Brush.verticalGradient(listOf(Color.White, HubColors.OpenRow)) else Brush.verticalGradient(listOf(Color.White, HubColors.CardBottom)),
                shape,
            )
            .border(if (open) 3.dp else 1.dp, if (open) HubColors.Blue else HubColors.CardBorder, shape)
            .animateContentSize(HubMotion.Default.compose()),
    ) {
        Pressable(
            onClick = { actions.onFaqToggle(item) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(item.text, style = HubType.Tile.copy(color = HubColors.Ink), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Glyph(R.drawable.ic_m_chevron, 34.dp, HubColors.Blue, modifier = Modifier.graphicsLayer { rotationZ = chevron })
            }
        }
        if (open) {
            Column(Modifier.padding(start = 32.dp, end = 32.dp, bottom = 26.dp)) {
                if (answer == null) {
                    TypingDots()
                } else {
                    BasicText(
                        answer,
                        style = HubType.Body.copy(color = HubColors.Ink),
                        maxLines = if (expanded) Int.MAX_VALUE else FAQ_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (answer != null && (answer.length > FAQ_LONG_CHARS || guide)) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (guide) HubPill(texts.guideShort, actions::onGuide, glyph = R.drawable.ic_m_guide)
                        if (answer.length > FAQ_LONG_CHARS) {
                            HubPill(if (expanded) texts.showLess else texts.showMore, { expanded = !expanded }, primary = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Нижняя панель: в центре «Спросить голосом» (прототип: белая пилюля с синим микрофоном) или «Стоп» (тёмно-синяя);
 * слева — подсказка направления (where_is), справа — поле ввода (только эмулятор) и подпись сборки.
 */
@Composable
private fun BottomBar(state: ScreenTwoState, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    val texts = state.texts
    Box(
        modifier
            .fillMaxWidth()
            .height(ScreenTwoLayout.BottomBarHeight)
            .padding(horizontal = 40.dp),
    ) {
        AnimatedVisibility(
            visible = state.wayHint != null,
            enter = fadeIn(HubMotion.Default.compose()),
            exit = fadeOut(HubMotion.Snappy.compose()),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val shape = RoundedCornerShape(percent = 50)
            Row(
                Modifier
                    .height(72.dp)
                    .background(HubColors.ChipBg, shape)
                    .border(1.dp, HubColors.ChipBorder, shape)
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(R.drawable.ic_m_guide, 30.dp, HubColors.BlueText)
                Spacer(Modifier.width(12.dp))
                BasicText(
                    state.wayHint.orEmpty(),
                    style = HubType.Callout.copy(color = HubColors.BlueText, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }
        }
        AnimatedContent(
            targetState = state.busy,
            transitionSpec = {
                (fadeIn(HubMotion.Default.compose()) + scaleIn(HubMotion.Default.compose(), initialScale = 0.94f))
                    .togetherWith(fadeOut(HubMotion.Snappy.compose()) + scaleOut(HubMotion.Snappy.compose(), targetScale = 0.94f))
                    .using(SizeTransform(clip = false) { _, _ -> spring(1f, HubMotion.Default.stiffness, IntSize.VisibilityThreshold) })
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.Center),
            label = "askStop",
        ) { busy ->
            if (busy) {
                HubPill(texts.stop, actions::onStop, glyph = R.drawable.ic_m_stop, kind = PillKind.STOP)
            } else {
                AskVoice(texts.askVoice, actions::onAskVoice)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.End) {
            if (state.textInput) {
                TextInput(texts.inputHint, enabled = !state.thinking, onSend = actions::onSend, modifier = Modifier.width(470.dp))
            }
        }
        BasicText(
            state.buildLabel,
            style = HubType.Caption.copy(color = HubColors.Faint, fontSize = HubType.Caption.fontSize * 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp),
        )
    }
}

/** «Спросить голосом» в виде прототипа (`.hint`): белая пилюля с кромкой, синий круг с микрофоном. */
@Composable
private fun AskVoice(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .height(84.dp)
            .softShadow(shape, 6.dp, 0.08f)
            .background(Color.White, shape)
            .border(1.dp, HubColors.Border, shape)
            .padding(start = 12.dp, end = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(60.dp).softShadow(CircleShape, 8.dp, 0.3f, HubColors.Blue).background(HubColors.Blue, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(R.drawable.ic_m_mic, 30.dp, Color.White)
            }
            Spacer(Modifier.width(16.dp))
            BasicText(label, style = HubType.Callout.copy(color = HubColors.LangText), maxLines = 1)
        }
    }
}

@Composable
private fun TextInput(hint: String, enabled: Boolean, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(percent = 50)
    fun send() {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            text = ""
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(72.dp)
                .background(Color.White, shape)
                .border(1.dp, HubColors.Border, shape)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                BasicText(hint, style = HubType.Callout.copy(color = HubColors.Faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = HubType.Callout.copy(color = HubColors.Ink),
                cursorBrush = SolidColor(HubColors.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
        CircleButton(R.drawable.ic_m_send, { send() }, enabled = enabled && text.isNotBlank())
    }
}

/**
 * Подтверждение выхода (крестик в панели) — карточка прототипа по центру: заголовок, пояснение, «Остаться» (главная)
 * и «Выйти»; касание мимо — «Остаться».
 */
@Composable
fun ExitDialog(texts: ScreenTexts, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(HubColors.Scrim)
                .pointerInput(Unit) { detectTapGestures(onTap = { actions.onExitCancel() }) },
        )
        DialogCard {
            BasicText(texts.exitQuestion, style = HubType.Title.copy(color = HubColors.Navy, textAlign = TextAlign.Center))
            Spacer(Modifier.height(12.dp))
            BasicText(texts.exitSub, style = HubType.Body.copy(color = HubColors.Muted, textAlign = TextAlign.Center))
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HubPill(texts.exitNo, actions::onExitCancel)
                HubPill(texts.exitYes, actions::onExitConfirm, primary = false)
            }
        }
    }
}

/** Белая карточка-диалог прототипа (`.exit-card`): скругление 36 dp, мягкая тень, поля 40 × 48 dp. */
@Composable
fun DialogCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier
            .widthIn(max = 920.dp)
            .softShadow(shape, 24.dp, 0.18f)
            .background(Color.White, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private val TILE_GAP = 24.dp
private val TILE_HEIGHT = 156.dp
private const val TILE_COLUMNS = 3
private val CHAT_FADE = 32.dp

/** Под роботом (от верха основной зоны): состояние / субтитры. */
private val BELOW_ROBOT = 518.dp
private const val FAQ_LINES = 4
private const val FAQ_LONG_CHARS = 180
private const val SPOKEN_TICK_MS = 50L
private const val TYPING_PERIOD_MS = 1_200
const val DIAG_HOLD_MILLIS = 3_000L
