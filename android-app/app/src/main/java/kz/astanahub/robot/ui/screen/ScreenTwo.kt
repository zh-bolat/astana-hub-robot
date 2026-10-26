package kz.astanahub.robot.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.astanahub.robot.R
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.SubtitlePacer
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubSpace
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

    /** Строка состояния под заголовком: «Слушаю…», «Думаю…», «Готов помочь». */
    var status by mutableStateOf("")
    var chat by mutableStateOf<List<ChatItem>>(emptyList())

    /** Посетитель говорит прямо сейчас (промежуточный текст ASR). */
    var partial by mutableStateOf<String?>(null)

    /** Реплика робота, которая звучит сейчас, и её текущая фраза — живые субтитры (слова «зажигаются» по темпу). */
    var speakingId by mutableStateOf<Long?>(null)
    var phrase by mutableStateOf<String?>(null)

    /** Думает (ждём ответ) — «…» под вопросом. */
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

    /** К лицу (тап по мини-лицу в шапке). */
    fun onClose()
}

/** Геометрия экрана 2, от которой зависит морфинг лица (слот мини-лица в шапке). */
object ScreenTwoLayout {
    val HeaderHeight = 128.dp
    val SidePadding = 40.dp
    val SlotWidth = 156.dp
    val SlotHeight = 88.dp

    /** Видимое мини-лицо (глаза, брови, рот) по высоте — с запасом внутри слота на движения головы. */
    val MiniFaceHeight = 76.dp
}

/**
 * Экран 2 — функции. Шапка: мини-лицо (его рисует слой лица поверх — [RobotScreens]), заголовок и состояние,
 * плашки «нет связи» / «микрофон выключен», язык, выход. Слева — чат с живыми субтитрами и подписью-источником,
 * внизу — «Спросить голосом» / «Стоп» (и поле ввода на эмуляторе). Справа — плитки или частые вопросы.
 * [progress] — переход (0 — лицо, 1 — экран 2): правая колонка и низ чуть отстают (параллакс глубины).
 */
@Composable
fun ScreenTwo(state: ScreenTwoState, actions: ScreenTwoActions, progress: () -> Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(HubColors.Black)) {
        Header(state, actions)
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = ScreenTwoLayout.SidePadding, end = ScreenTwoLayout.SidePadding, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.weight(1.45f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { Chat(state, actions) }
                Spacer(Modifier.height(20.dp))
                Composer(state, actions, Modifier.parallax(progress, 0.04f))
            }
            Column(Modifier.weight(1f).fillMaxHeight().parallax(progress, 0.09f)) {
                val faq = state.faq
                if (faq == null) {
                    BasicText(state.texts.tilesTitle, style = HubType.Callout.copy(color = HubColors.LabelSecondary))
                    Spacer(Modifier.height(16.dp))
                    TileGrid(state, actions, Modifier.weight(1f))
                } else {
                    FaqPanel(state, faq, actions, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                BasicText(
                    state.buildLabel,
                    style = HubType.Caption.copy(color = HubColors.LabelTertiary),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** Отставание элемента при переходе: пока экран въезжает, он ещё чуть правее — глубина без лишних движений. */
private fun Modifier.parallax(progress: () -> Float, depth: Float): Modifier = graphicsLayer {
    translationX = (1f - progress().coerceIn(0f, 1f)) * size.width * depth * 4f
}

@Composable
private fun Header(state: ScreenTwoState, actions: ScreenTwoActions) {
    val texts = state.texts
    Row(
        Modifier
            .fillMaxWidth()
            .height(ScreenTwoLayout.HeaderHeight)
            .padding(horizontal = ScreenTwoLayout.SidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Место мини-лица: само лицо рисует слой лица поверх экрана; тап — назад к лицу.
        Pressable(
            onClick = actions::onClose,
            modifier = Modifier.size(ScreenTwoLayout.SlotWidth, ScreenTwoLayout.SlotHeight),
        ) {}
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                texts.title,
                style = HubType.Headline.copy(color = HubColors.Label),
                maxLines = 1,
                modifier = Modifier.holdToActivate(state.diagnosticsHold, DIAG_HOLD_MILLIS, actions::onDiagnostics),
            )
            BasicText(
                state.status,
                style = HubType.Callout.copy(color = HubColors.LabelSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(state.offline, enter = fadeIn(HubMotion.Default.compose()), exit = fadeOut(HubMotion.Default.compose())) {
            StatusBadge(texts.offline, Modifier.padding(end = 16.dp).widthIn(max = 420.dp))
        }
        AnimatedVisibility(state.micOff, enter = fadeIn(HubMotion.Default.compose()), exit = fadeOut(HubMotion.Default.compose())) {
            StatusBadge(texts.micOff, Modifier.padding(end = 16.dp).widthIn(max = 420.dp))
        }
        LanguageSwitch(state.lang, actions::onLanguage)
        Spacer(Modifier.width(20.dp))
        CircleButton(R.drawable.ic_m_close, actions::onExit)
    }
}

/**
 * Чат: снизу новое (обратный порядок — растущий ответ держится у нижнего края, история уходит вверх). Новая
 * реплика проявляется (fade + сдвиг пружиной), список плавно доезжает к ней. Пустой чат — приветствие.
 */
@Composable
private fun Chat(state: ScreenTwoState, actions: ScreenTwoActions) {
    val chat = state.chat
    val listState = rememberLazyListState()
    val lastId = chat.lastOrNull()?.id
    val tail = state.partial != null || state.thinking
    LaunchedEffect(lastId, tail) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    val lastRobotId = chat.lastOrNull { it.role == ChatRole.ROBOT }?.id
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = chat.isEmpty() && !tail,
            enter = fadeIn(HubMotion.Gentle.compose()),
            exit = fadeOut(HubMotion.Snappy.compose()),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                BasicText(state.texts.greetTitle, style = HubType.Display.copy(color = HubColors.Label))
                Spacer(Modifier.height(16.dp))
                BasicText(state.texts.greetSub, style = HubType.Body.copy(color = HubColors.LabelSecondary))
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
        ) {
            if (tail) {
                item(key = "tail") {
                    Box(Modifier.animateItem(fadeInSpec = HubMotion.Default.compose(), fadeOutSpec = HubMotion.Snappy.compose())) {
                        val partial = state.partial
                        if (partial != null) VisitorBubble("$partial…", live = true) else TypingDots()
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
                        ChatRole.VISITOR -> VisitorBubble(item.text, live = false)
                        ChatRole.ROBOT -> RobotLine(
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
    }
}

@Composable
private fun VisitorBubble(text: String, live: Boolean) {
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = 720.dp)
                .graphicsLayer { alpha = if (live) 0.6f else 1f }
                .background(HubColors.Fill, shape)
                .border(1.dp, HubColors.Hairline, shape)
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            BasicText(text, style = HubType.Body.copy(color = HubColors.Label))
        }
    }
}

/**
 * Реплика робота: крупный белый текст без «пузыря». Пока она звучит — живые субтитры: слова текущей фразы
 * зажигаются по темпу речи ([SubtitlePacer]), ещё не сказанные — третичным (40 %). Под ответом — подпись-источник
 * (`citations`) и «Проводить», если робот может проводить до места из ответа.
 */
@Composable
private fun RobotLine(
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
    Column(Modifier.fillMaxWidth().padding(end = 80.dp).animateContentSize(HubMotion.Default.compose())) {
        BasicText(text, style = HubType.Body.copy(color = HubColors.Label))
        if (caption != null) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                caption,
                style = HubType.Caption.copy(color = HubColors.LabelTertiary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.appear(),
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
        if (count < spoken) append(w) else withStyle(SpanStyle(color = HubColors.LabelTertiary)) { append(w) }
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

/** «Думаю»: три точки с бегущей волной прозрачности (не крутилка). */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(TYPING_PERIOD_MS), RepeatMode.Restart), label = "typingPhase")
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        val d = ((phase - i * 0.18f) % 1f + 1f) % 1f
                        alpha = 0.3f + 0.7f * (1f - kotlin.math.abs(d * 2f - 1f))
                    }
                    .background(HubColors.White, CircleShape),
            )
        }
    }
}

/** Низ чата: «Спросить голосом» (занят — «Стоп»), подсказка направления (where_is) и поле ввода на эмуляторе. */
@Composable
private fun Composer(state: ScreenTwoState, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    val texts = state.texts
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = state.wayHint != null,
            enter = fadeIn(HubMotion.Default.compose()) + expandVertically(HubMotion.Default.compose()),
            exit = fadeOut(HubMotion.Snappy.compose()) + shrinkVertically(HubMotion.Default.compose()),
        ) {
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_m_guide, 30.dp)
                Spacer(Modifier.width(14.dp))
                BasicText(state.wayHint.orEmpty(), style = HubType.Headline.copy(color = HubColors.Label), maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                HubPill(texts.stop, actions::onStop, glyph = R.drawable.ic_m_stop)
            } else {
                HubPill(texts.askVoice, actions::onAskVoice, glyph = R.drawable.ic_m_mic)
            }
            if (state.textInput) {
                Spacer(Modifier.width(16.dp))
                TextInput(texts.inputHint, enabled = !state.thinking, onSend = actions::onSend, modifier = Modifier.weight(1f))
            }
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
                .height(PILL_HEIGHT)
                .background(HubColors.Fill, shape)
                .border(1.dp, HubColors.Hairline, shape)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                BasicText(hint, style = HubType.Callout.copy(color = HubColors.LabelTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = HubType.Callout.copy(color = HubColors.Label),
                cursorBrush = SolidColor(HubColors.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
        CircleButton(R.drawable.ic_m_send, { send() }, enabled = enabled && text.isNotBlank())
    }
}

/** Плитки 2 × 3: частые вопросы, «Частые вопросы» (весь список), «Что это?». Проявляются волной. */
@Composable
private fun TileGrid(state: ScreenTwoState, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    val tiles = state.tiles
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                pair.forEachIndexed { col, tile ->
                    androidx.compose.runtime.key(tile.key) {
                        TileCard(
                            tile = tile,
                            enabled = state.tilesEnabled,
                            onClick = { actions.onTile(tile) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .appear(order = row * 2 + col, reducedMotion = state.reducedMotion),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        repeat(((MAX_TILE_ROWS * 2 - tiles.size) / 2).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun TileCard(tile: Tile, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .background(HubColors.Fill, shape)
            .border(1.dp, HubColors.Hairline, shape)
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Glyph(glyphRes(tile.glyph), 40.dp)
            Spacer(Modifier.weight(1f))
            BasicText(
                tile.label,
                style = HubType.Callout.copy(color = HubColors.Label),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
 * Частые вопросы (как прежний аккордеон): назад, заголовок, вопросы страницы — раскрыт не больше одного; в раскрытом
 * — ответ (или «Думаю…»), «Показать полностью» и «Проводить»; внизу — «Ещё вопросы (1/3)».
 */
@Composable
private fun FaqPanel(state: ScreenTwoState, faq: FaqUi, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    val texts = state.texts
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleButton(R.drawable.ic_m_back, actions::onFaqBack)
            Spacer(Modifier.width(20.dp))
            BasicText(texts.faqTitle, style = HubType.Headline.copy(color = HubColors.Label), maxLines = 1)
        }
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
    val shape = RoundedCornerShape(28.dp)
    val chevron by animateFloatAsState(if (open) 180f else 0f, HubMotion.Default.compose(), label = "chevron")
    var expanded by remember(answer) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(if (open) HubColors.FillPressed else HubColors.Fill, shape)
            .border(1.dp, HubColors.Hairline, shape)
            .animateContentSize(HubMotion.Default.compose()),
    ) {
        Pressable(
            onClick = { actions.onFaqToggle(item) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(item.text, style = HubType.Callout.copy(color = HubColors.Label), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Glyph(R.drawable.ic_m_chevron, 30.dp, modifier = Modifier.graphicsLayer { rotationZ = chevron })
            }
        }
        if (open) {
            Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
                BasicText(
                    answer ?: pending,
                    style = HubType.Body.copy(color = if (answer == null) HubColors.LabelTertiary else HubColors.Label),
                    maxLines = if (expanded) Int.MAX_VALUE else FAQ_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (answer != null && (answer.length > FAQ_LONG_CHARS || guide)) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (answer.length > FAQ_LONG_CHARS) {
                            HubPill(if (expanded) texts.showLess else texts.showMore, { expanded = !expanded }, primary = false)
                        }
                        if (guide) HubPill(texts.guideShort, actions::onGuide, glyph = R.drawable.ic_m_guide)
                    }
                }
            }
        }
    }
}

/** Подтверждение выхода (крестик в шапке): материал по центру; касание мимо — «Остаться». */
@Composable
fun ExitDialog(texts: ScreenTexts, actions: ScreenTwoActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Pressable(onClick = actions::onExitCancel, modifier = Modifier.fillMaxSize().background(HubColors.Scrim)) {}
        val shape = RoundedCornerShape(44.dp)
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .background(HubColors.Island, shape)
                .border(1.dp, HubColors.Hairline, shape)
                .padding(48.dp),
        ) {
            BasicText(texts.exitQuestion, style = HubType.Title.copy(color = HubColors.Label))
            Spacer(Modifier.height(HubSpace.S))
            BasicText(texts.exitSub, style = HubType.Body.copy(color = HubColors.LabelSecondary))
            Spacer(Modifier.height(HubSpace.L))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HubPill(texts.exitNo, actions::onExitCancel, primary = false)
                HubPill(texts.exitYes, actions::onExitConfirm)
            }
        }
    }
}

private val TILE_GAP = 16.dp
private val TILE_RADIUS = 32.dp
private const val MAX_TILE_ROWS = 3
private const val FAQ_LINES = 4
private const val FAQ_LONG_CHARS = 180
private const val SPOKEN_TICK_MS = 50L
private const val TYPING_PERIOD_MS = 1_200
const val DIAG_HOLD_MILLIS = 3_000L
