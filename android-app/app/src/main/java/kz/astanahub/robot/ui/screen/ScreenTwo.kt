package kz.astanahub.robot.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kz.astanahub.robot.R
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.SubtitlePacer
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubMotion
import kz.astanahub.robot.ui.design.HubType
import kz.astanahub.robot.ui.design.cssLines

/** Пузырь после речи: последний кусок фразы (все слова сказаны) и эмодзи настроения. */
@Immutable
data class Linger(val text: String, val emoji: String)

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
    /** Полоса «нет связи» на странице приложения (прототип: с подсказкой, что дорогу покажет и без сети). */
    val offlineBar: String = "",
    val micOff: String = "",
    val exitQuestion: String = "",
    val exitSub: String = "",
    val exitYes: String = "",
    val exitNo: String = "",
    /** «Источник: %1$s» / «Источники: %1$s». */
    val sourceOne: String = "%1\$s",
    val sourceMany: String = "%1\$s",
    val back: String = "",
    val closed: String = "",
    val closedSub: String = "",
    val reopen: String = "",
    val home: String = "",
    val menu: String = "",
    val look: String = "",
    /** Подпись заглушки снимка (прототип `snapshot`). */
    val snapshot: String = "",
    val costumes: List<String> = emptyList(),
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

    /**
     * Строка «услышанное» под состоянием (прототип `.heard`): «…что говорит посетитель (без закрывающей кавычки), пока он
     * говорит; «вопрос», пока робот думает; иначе пусто.
     */
    var heard by mutableStateOf<String?>(null)

    /** Робот говорит (фаза SPEAKING): в центре низа — «Стоп». */
    var speaking by mutableStateOf(false)

    /** «Что это?»: робот смотрит, ответа ещё нет — справа карточка со снимком и точками (прототип `look`). */
    var lookWaiting by mutableStateOf(false)

    /** Нажатая плитка, пока ответа нет (прототип `.tile.sel`); null — нет. */
    var selectedTile by mutableStateOf<String?>(null)

    /** Сдвиг робота вниз (dp), пока под ним пузырь (робот и пузырь — по центру колонки); читает и слой лица. */
    val robotShift = mutableFloatStateOf(0f)

    /** Кивок экрана головы в конце речи (dp, прототип `nod`): пишет [RobotScreens], читают лицо и компаньон. */
    val nodDp = mutableFloatStateOf(0f)

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

    /** Костюм робота-компаньона (свайп по роботу / точки под ним / диагностика). */
    var costume by mutableStateOf(Costume.CLASSIC)

    /** Что делает робот-компаньон (руки, тело — как в прототипе). */
    var pose by mutableStateOf(CompanionPose.IDLE)

    /** Сдвиг головы компаньона (dp): живые глаза в экране головы двигаются вместе с ней. */
    val headBob = headBobState()

    /** Справа — приветствие и плитки (иначе разговор или частые вопросы). */
    var homeView by mutableStateOf(true)

    /** Разговор на экране — с реплик после последнего «В начало» (id > [chatFrom]); раньше — история выше. */
    var chatFrom by mutableLongStateOf(0L)

    /** После речи (прототип `fin`): последний кусок субтитров с эмодзи настроения висит в пузыре; null — нет. */
    var linger by mutableStateOf<Linger?>(null)

    /** Счётчики-сигналы: кивок экрана головы в конце речи, «сборка» робота при выборе костюма. */
    var nod by mutableIntStateOf(0)
    var assemble by mutableIntStateOf(0)

    /** Снимок «Что это?» (прототип): в «думаю» и в карточке ответа [photoItemId] (−1 — ответа ещё нет). */
    var photo by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var photoItemId by mutableLongStateOf(-1L)

    /** «Назад» в панели: есть куда вернуться (ответ, частые вопросы). */
    var canGoBack by mutableStateOf(false)

    /** Чипы мест под ответом «где» (прототип): подписи на языке экрана. */
    var placeChips by mutableStateOf<List<String>>(emptyList())

    /** К лицу можно вернуться (режим agent / live); в legacy главный экран — меню, голова робота не кнопка. */
    var faceAvailable by mutableStateOf(true)

    /** Крестик выхода есть (на роботе без пароля сотрудника — нет: выйти с экрана нельзя; mynbay). */
    var exitVisible by mutableStateOf(true)

    /** Слой «Приложение закрыто» (прототип) — 1,5 с до закрытия, «Открыть снова» — передумать. */
    var closed by mutableStateOf(false)

    /** Палец на роботе-компаньоне: отклик на touch-down рисует слой лица (глаза сжимаются). */
    val miniPressed = mutableStateOf(false)

    /** Есть полоса предупреждения под панелью (нет связи / микрофон) — основная зона ниже на её высоту. */
    val notice: String?
        get() = when {
            offline -> texts.offlineBar.ifEmpty { texts.offline }
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

    /** «Открыть снова» на слое «Приложение закрыто». */
    fun onReopen() {}

    /** «Назад» в панели (история экранов, как в прототипе). */
    fun onBack() = onFaqBack()

    /** «В начало»: приветствие и плитки. */
    fun onHome() {}

    /** «Что это?» внизу (снимок камеры). */
    fun onLook() {}

    /** Чип места под ответом «где» ([ScreenTwoState.placeChips]). */
    fun onPlace(index: Int) {}

    /** Костюм: точка под роботом или свайп (+1 / −1). */
    fun onCostume(c: Costume) {}
    fun onCostumeSwipe(step: Int) {}
}

/**
 * Геометрия страницы приложения (прототип, dp = px сцены 1600 × 900): панели, колонки и робот-компаньон, в экран
 * головы которого садится живое лицо ([RobotScreens], [FaceMorph]).
 */
object ScreenTwoLayout {
    val TopBarHeight = 88.dp
    val BottomBarHeight = 120.dp
    val LeftWidth = 560.dp

    /** Рамка слоёв робота (540 × 580) в левой колонке — от верха основной зоны (сцена: 10, 53). */
    val RobotX = 10.dp
    val RobotY = (-35).dp

    /** Чёрный экран головы и область глаз в нём (от верха основной зоны; сцена: 144, 193 и 165, 229). */
    val ScreenX = 144.dp
    val ScreenY = 105.dp
    val ScreenW = 272.dp
    val ScreenH = 186.dp
    val EyesX = 165.dp
    val EyesY = 141.dp
    val EyesW = 230.dp
    val EyesH = 115.dp

    /** Голова целиком (касание — назад к лицу). */
    val HeadX = 124.dp
    val HeadY = 85.dp
    val HeadW = 312.dp
    val HeadH = 232.dp

    /** Под роботом: название костюма и точки (сцена 595), состояние / субтитры (сцена 644–648). */
    val DotsY = 507.dp
    val InfoY = 556.dp
}

/**
 * Экран 2 — страница приложения в виде прототипа: белый фон; сверху панель (назад, Astana Hub, язык, выход), под ней
 * при необходимости янтарная полоса «нет связи» / «микрофон»; слева робот-компаньон (в его экране живут глаза) с
 * состоянием и пузырём субтитров; справа приветствие и плитки, разговор карточками или частые вопросы; снизу —
 * «Спросить голосом» / «Стоп». [progress] — переход (0 — лицо, 1 — экран 2): правая колонка чуть отстаёт.
 */
@Composable
fun ScreenTwo(
    state: ScreenTwoState,
    actions: ScreenTwoActions,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    /** Страница доехала (не в переходе): иначе касания кнопок не срабатывают — ловят переход. */
    interactive: Boolean = true,
    /** Переход едет к странице (а не к лицу). */
    opening: () -> Boolean = { true },
) {
    Box(modifier.fillMaxSize()) {
        ScreenTwoContent(state, actions, progress, opening)
        if (!interactive) {
            Box(
                Modifier
                    .matchParentSize()
                    // Верхний слой забирает касание у кнопок под ним (они его не получат), но не потребляет его:
                    // ловля летящего перехода и свайп страницы (родитель) работают как прежде.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent()
                        }
                    },
            )
        }
    }
}

/**
 * Вид страницы во время перехода лицо ↔ приложение (прототип `morph`, 900 мс): [progress] 0 — лицо, 1 — страница,
 * [opening] — едем к странице. К лицу: правая колонка гаснет за .3 с, подписи под роботом — за .2 с, тело робота —
 * за .35 с. К странице: тело проявляется после половины пути (.35 с), правая колонка въезжает справа на 40 dp (.6 с,
 * cubic-bezier(.22, .8, .24, 1)), подписи — в конце.
 */
@Stable
class TransitionLook(private val progress: () -> Float, private val opening: () -> Boolean) {
    private fun ms() = MorphTiming.ms(progress(), opening())

    /** Правая колонка: открытие — `slideIn` с 450 мс; закрытие — гаснет за 300 мс (`ease`). */
    fun right(): Float = MorphTiming.right(ms(), opening())

    /** Сдвиг правой колонки (доля от 40 dp): въезжает справа. */
    fun rightShift(): Float = if (opening()) 1f - MorphTiming.right(ms(), true) else 0f

    /** Подпись костюма, точки, статус: во время перехода скрыты (прототип `.main.morph-top .left`). */
    fun leftOthers(): Float = MorphTiming.leftOthers(ms(), opening())

    /** Тело, корпус головы и наряд робота (прототип `.zooming`): проявляются с 450 мс, гаснут сразу. */
    fun companionBody(): Float = MorphTiming.body(ms(), opening())
}

/**
 * Время перехода лицо ↔ страница по прототипу (`morph`, 900 мс, кривая (.45, 0, .15, 1)): прогресс уже сглажен
 * кривой — по нему восстанавливаем миллисекунды и считаем слои как таймеры и CSS-переходы прототипа.
 */
object MorphTiming {
    const val DURATION_MS = 900f
    val CURVE = kz.astanahub.robot.ui.face.Bezier(0.45f, 0f, 0.15f, 1f)
    private val ENTER = kz.astanahub.robot.ui.face.Bezier(0.22f, 0.8f, 0.24f, 1f)
    private val EASE = kz.astanahub.robot.ui.face.Bezier.EASE

    /** Миллисекунды от начала перехода: открытие — прогресс 0 → 1, закрытие — 1 → 0. */
    fun ms(progress: Float, opening: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        return DURATION_MS * CURVE.inverse(if (opening) p else 1f - p)
    }

    fun right(ms: Float, opening: Boolean): Float =
        // `slideIn` прототипа длится 600 мс и кончается после перехода; у нас — к его концу (без скачка в покое).
        if (opening) ENTER.transform((ms - 450f) / 450f) else 1f - EASE.transform(ms / 300f)

    fun leftOthers(ms: Float, opening: Boolean): Float =
        if (opening) ((ms - 700f) / 200f).coerceIn(0f, 1f) else 1f - (ms / 200f).coerceIn(0f, 1f)

    fun body(ms: Float, opening: Boolean): Float =
        if (opening) EASE.transform((ms - 450f) / 350f) else 1f - EASE.transform(ms / 350f)

    /**
     * Чёрный слой лица на весь экран (прототип `faceLayer`): открытие — гаснет с 90 мс за 495 мс; закрытие —
     * проявляется с 315 мс за 540 мс. Экран головы сжимается / растёт поверх него.
     */
    fun faceLayer(ms: Float, opening: Boolean): Float =
        if (opening) 1f - EASE.transform((ms - 90f) / 495f) else EASE.transform((ms - 315f) / 540f)
}

@Composable
private fun ScreenTwoContent(state: ScreenTwoState, actions: ScreenTwoActions, progress: () -> Float, opening: () -> Boolean) {
    val transition = remember { TransitionLook(progress, opening) }
    Column(Modifier.fillMaxSize().background(HubColors.Background)) {
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
            LeftColumn(state, actions, transition, Modifier.width(ScreenTwoLayout.LeftWidth).fillMaxHeight())
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = transition.right()
                        translationX = transition.rightShift() * 40.dp.toPx()
                    }
                    .padding(start = 24.dp, top = 32.dp, end = 48.dp, bottom = 24.dp),
            ) {
                val faq = state.faq
                when {
                    faq != null -> FaqPanel(state, faq, actions)
                    // Прототип `look()`: карточка-снимок сменяет и приветствие (плитка «Что это?» на главной).
                    state.homeView && !state.lookWaiting -> Home(state, actions)
                    else -> Conversation(state, actions)
                }
            }
        }
        BottomBar(state, actions, transition)
    }
}

@Composable
private fun TopBar(state: ScreenTwoState, actions: ScreenTwoActions) {
    val texts = state.texts
    Row(
        Modifier
            .fillMaxWidth()
            .height(ScreenTwoLayout.TopBarHeight)
            .background(Color.White.copy(alpha = 0.92f))
            .bottomLine(Color(0xFFE3EBF7))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // «Назад» — везде, где есть куда вернуться (прототип: всё, кроме приветствия).
        if (state.canGoBack) BackPill(texts.back, actions::onBack, Modifier.padding(end = 20.dp))
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
        // Разделитель и «×» — только когда выход есть (на роботе без пароля его нет — и черта не висит).
        if (state.exitVisible) {
            Spacer(Modifier.width(28.dp))
            Box(Modifier.width(2.dp).height(44.dp).background(HubColors.Line))
            Spacer(Modifier.width(28.dp))
            CircleButton(R.drawable.ic_m_close, actions::onExit)
        }
    }
}

private fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx())
}

/**
 * Левая колонка (прототип `.left`: содержимое по центру колонки, промежуток 18): робот-компаньон, под ним точки
 * костюмов, состояние и услышанное. Пока звучат субтитры (и ещё 8 с после речи — с эмодзи настроения) вместо них —
 * пузырь, и робот вместе с пузырём встаёт по центру колонки: сдвиг вниз на (200 − высота пузыря) / 2 − 1 dp.
 */
@Composable
private fun LeftColumn(state: ScreenTwoState, actions: ScreenTwoActions, transition: TransitionLook, modifier: Modifier = Modifier) {
    val live = state.phrase.takeIf { state.speakingId != null }
    val linger = state.linger
    val subs = live != null || linger != null
    var bubbleH by remember { mutableFloatStateOf(0f) }
    val target = if (subs && bubbleH > 0f) (SUBS_SPACE - bubbleH) / 2f - 1f else 0f
    val shift by animateFloatAsState(target, tween(SUB_IN_MS, easing = FastOutSlowInEasing), label = "robotShift")
    SideEffect { state.robotShift.floatValue = shift }
    Box(modifier) {
        Companion(
            costume = state.costume,
            pose = state.pose,
            headBob = state.headBob,
            onSwipeCostume = actions::onCostumeSwipe,
            reducedMotion = state.reducedMotion,
            assemble = state.assemble,
            nod = state.nodDp,
            bodyAlpha = transition::companionBody,
            modifier = Modifier.offset(ScreenTwoLayout.RobotX, ScreenTwoLayout.RobotY + shift.dp),
        )
        // Тап по экрану головы робота — к лицу (прототип: только по `.bot-head .face`, сдвиг < 12 px).
        Pressable(
            onClick = actions::onClose,
            enabled = state.faceAvailable,
            pressed = state.miniPressed,
            modifier = Modifier
                .offset(ScreenTwoLayout.ScreenX, ScreenTwoLayout.ScreenY + shift.dp)
                .size(ScreenTwoLayout.ScreenW, ScreenTwoLayout.ScreenH),
        ) {}
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = transition.leftOthers() }) {
            if (!subs) {
                Box(Modifier.fillMaxWidth().padding(top = ScreenTwoLayout.DotsY), contentAlignment = Alignment.TopCenter) {
                    CostumeDots(state.costume, state.texts.costumes, actions::onCostume)
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = ScreenTwoLayout.InfoY + 4.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Прототип `.state-label` 30 / 400 #5B6475 и `.heard` 26, межстрочный 1,35, не ниже 72, ширина ≤ 460.
                    BasicText(
                        state.status,
                        style = HubType.Body.copy(color = HubColors.Muted, fontSize = 30.sp, lineHeight = 41.sp, textAlign = TextAlign.Center),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                    BasicText(
                        state.heard.orEmpty(),
                        style = HubType.Callout.copy(color = HubColors.Muted, lineHeight = 35.1.sp, textAlign = TextAlign.Center),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 460.dp).heightIn(min = 72.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = subs,
                enter = fadeIn(tween(SUB_IN_MS, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(SUB_IN_MS, easing = FastOutSlowInEasing)) { with(it) { 8 } } +
                    scaleIn(tween(SUB_IN_MS, easing = FastOutSlowInEasing), initialScale = 0.97f),
                exit = fadeOut(tween(120)),
                modifier = Modifier.fillMaxWidth().padding(top = SUB_TOP + shift.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val density = LocalDensity.current
                    SubtitleBubble(live, linger, Modifier.onSizeChanged { bubbleH = with(density) { it.height.toDp().value } })
                }
            }
        }
    }
}

/**
 * Пузырь субтитров прототипа (`.sub`): по размеру текста (до 496 dp), белый .95, кромка 1 dp #DCE6F5, скругление 22,
 * тень 0 6 18 синяя .10, хвостик — повёрнутый квадрат 20 со скруглённой вершиной; 28 sp, межстрочный 1,35, по центру.
 * Пока звучит — кусок фразы до 7 слов, сказанные слова тёмные, остальные #9AA3B5; после речи — последний кусок целиком
 * и эмодзи настроения (появляется `moodIn`: из 0,3 и −20°).
 */
@Composable
private fun SubtitleBubble(live: String?, linger: Linger?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val style = HubType.Body.copy(color = HubColors.Ink, fontSize = 28.sp, lineHeight = 37.8.sp, textAlign = TextAlign.Center)
    Box(
        modifier
            .widthIn(max = 496.dp)
            .softShadow(shape, 9.dp, 0.10f, HubColors.Blue)
            .drawBehind { drawBubbleTail() }
            .background(Color.White.copy(alpha = 0.95f), shape)
            .border(1.dp, HubColors.SubBorder, shape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (live != null) {
            val spoken = spokenWords(live)
            val text = remember(live, spoken) { SubtitleWindow.text(live, spoken, HubColors.Faint) }
            BasicText(text, style = style)
        } else if (linger != null) {
            val text = remember(linger) {
                buildAnnotatedString {
                    append(linger.text)
                    append(' ')
                    appendInlineContent(MOOD_ID, linger.emoji)
                }
            }
            val inline = remember(linger) {
                mapOf(
                    MOOD_ID to InlineTextContent(Placeholder(1.25.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) { emoji ->
                        val a = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { a.animateTo(1f, tween(350, easing = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f))) }
                        BasicText(
                            emoji,
                            style = style,
                            modifier = Modifier.graphicsLayer {
                                val k = a.value
                                alpha = k.coerceIn(0f, 1f)
                                scaleX = 0.3f + 0.7f * k
                                scaleY = 0.3f + 0.7f * k
                                rotationZ = -20f * (1f - k)
                            },
                        )
                    },
                )
            }
            BasicText(text, style = style, inlineContent = inline)
        }
    }
}

/** Хвостик пузыря: квадрат 20 × 20 под 45° (центр на 1 dp выше кромки), вершина скруглена 6, кромка — верхние стороны. */
private fun DrawScope.drawBubbleTail() {
    val h = 10.dp.toPx() * 1.4142f
    val cx = size.width / 2f
    val cy = -1.dp.toPx()
    val r = 6.dp.toPx() * 0.7071f
    val path = Path().apply {
        moveTo(cx - h, cy)
        lineTo(cx - r, cy - h + r)
        quadraticTo(cx, cy - h, cx + r, cy - h + r)
        lineTo(cx + h, cy)
        lineTo(cx, cy + h)
        close()
    }
    drawPath(path, Color.White)
    val edge = Path().apply {
        moveTo(cx - h, cy)
        lineTo(cx - r, cy - h + r)
        quadraticTo(cx, cy - h, cx + r, cy - h + r)
        lineTo(cx + h, cy)
    }
    drawPath(edge, HubColors.SubBorder, style = Stroke(1.dp.toPx()))
}

/** Главная: приветствие и плитки 3 × 2 (прототип `goHome`). */
@Composable
private fun Home(state: ScreenTwoState, actions: ScreenTwoActions) {
    Column(Modifier.fillMaxSize()) {
        // Высоты строк — как в CSS прототипа (заголовок 59,8, пояснение 38): плитки встают на 261,8 dp.
        BasicText(state.texts.greetTitle, style = HubType.Display.copy(color = HubColors.Navy), modifier = Modifier.cssLines(HubType.Display))
        Spacer(Modifier.height(8.dp))
        BasicText(state.texts.greetSub, style = HubType.Body.copy(color = HubColors.Muted), modifier = Modifier.cssLines(HubType.Body))
        Spacer(Modifier.height(36.dp))
        TileGrid(state, actions)
    }
}

@Composable
private fun TileGrid(state: ScreenTwoState, actions: ScreenTwoActions) {
    val tiles = state.tiles
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        tiles.chunked(TILE_COLUMNS).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                chunk.forEach { tile ->
                    androidx.compose.runtime.key(tile.key) {
                        TileCard(
                            tile = tile,
                            selected = tile.key == state.selectedTile,
                            enabled = state.tilesEnabled,
                            onClick = { actions.onTile(tile) },
                            modifier = Modifier.weight(1f).height(TILE_HEIGHT),
                        )
                    }
                }
                repeat(TILE_COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Плитка прототипа: белая карточка (градиент к #FBFDFF, кромка 1 dp, мягкая тень), бейдж 60 × 60 со значком, подпись
 * 30 / 500 не больше двух строк. Нажатая, пока робот думает, — `.sel`: голубой градиент и синяя кромка.
 */
@Composable
private fun TileCard(tile: Tile, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .then(if (selected) Modifier.selectedCard(shape) else Modifier.card(shape))
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(badgeRes(tile.glyph)), contentDescription = null, modifier = Modifier.size(60.dp))
            Spacer(Modifier.width(16.dp))
            BasicText(
                tile.label,
                style = HubType.Tile.copy(color = HubColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.cssLines(HubType.Tile),
            )
        }
    }
}

/** Белая карточка прототипа: градиент к низу, кромка 1 dp, мягкая тень. */
private fun Modifier.card(shape: androidx.compose.ui.graphics.Shape): Modifier =
    softShadow(shape, 12.dp, 0.10f)
        .background(Brush.verticalGradient(listOf(Color.White, HubColors.CardBottom)), shape)
        .border(1.dp, HubColors.CardBorder, shape)

/** Выбранная плитка (`.tile.sel`): градиент #F1F5FF → #DFE8FF, кромка rgba(31, 79, 255, .35). */
private fun Modifier.selectedCard(shape: androidx.compose.ui.graphics.Shape): Modifier =
    softShadow(shape, 12.dp, 0.10f)
        .background(Brush.verticalGradient(listOf(Color(0xFFF1F5FF), Color(0xFFDFE8FF))), shape)
        .border(1.dp, Color(0x591F4FFF), shape)

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

/**
 * Ответ (прототип `renderAnswer`): справа ровно один ход — строка «вопрос» и карточка ответа (текст 40 sp, свёрнут до
 * трёх строк, «Показать полностью»), под карточкой — чипы мест. Прежние ходы — кнопкой «Назад». Пока робот слушает и
 * думает, здесь остаётся прежнее (новый ход — когда пошёл ответ); «Что это?» — сразу карточка со снимком и точками.
 */
@Composable
private fun Conversation(state: ScreenTwoState, actions: ScreenTwoActions) {
    val all = state.chat
    val from = state.chatFrom
    val answer = remember(all, from) { all.lastOrNull { it.role == ChatRole.ROBOT && it.id > from } ?: all.lastOrNull { it.role == ChatRole.ROBOT } }
    val question = remember(all, answer) {
        val i = all.indexOf(answer)
        all.getOrNull(i - 1)?.takeIf { it.role == ChatRole.VISITOR }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.lookWaiting) {
            // Прототип `look()`: карточка — снимок (пока его нет — заглушка) и точки «думаю».
            Column(
                Modifier.fillMaxWidth().card(RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                LookPhotoImage(state.photo, state.texts.snapshot)
                TypingDots()
            }
            return@Column
        }
        if (answer == null) return@Column
        if (question != null) {
            val qStyle = HubType.Body.copy(color = HubColors.Muted, lineHeight = 36.4.sp)
            BasicText("«${question.text}»", style = qStyle, modifier = Modifier.cssLines(qStyle))
            Spacer(Modifier.height(20.dp))
        }
        androidx.compose.runtime.key(answer.id) {
            AnswerCard(item = answer, texts = state.texts, state = state, actions = actions)
        }
    }
}

/**
 * Карточка ответа прототипа (`.answer`): радиус 28, поля 28 / 32, промежуток 20; текст 40 / 52 тёмный (слова
 * подсвечивает только пузырь), свёрнут до 3 строк; «Показать полностью» — ссылка-пилюля, видна, если текст не влез
 * (и пока робот говорит); полностью — до 360 dp с прокруткой. Под карточкой: источник (наше), направление (наше),
 * чипы мест.
 */
@Composable
private fun AnswerCard(item: ChatItem, texts: ScreenTexts, state: ScreenTwoState, actions: ScreenTwoActions) {
    val caption = remember(item.citations, texts) {
        Tiles.sourceCaption(item.citations, { texts.sourceOne.format(it) }, { texts.sourceMany.format(it) })
    }
    var full by remember(item.id) { mutableStateOf(false) }
    var overflow by remember(item.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .card(shape)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (item.id == state.photoItemId && state.photo != null) LookPhotoImage(state.photo, texts.snapshot)
            val style = HubType.Answer.copy(color = HubColors.Ink)
            if (full) {
                BasicText(
                    item.text,
                    style = style,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).cssLines(style),
                )
            } else {
                BasicText(
                    item.text,
                    style = style,
                    maxLines = ANSWER_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r -> overflow = r.hasVisualOverflow },
                    modifier = Modifier.cssLines(style),
                )
            }
            if (overflow || full) LinkButton(if (full) texts.showLess else texts.showMore) { full = !full }
        }
        if (caption != null) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                caption,
                style = HubType.Caption.copy(color = HubColors.Faint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp).graphicsLayer { alpha = 0.6f },
            )
        }
        val way = state.wayHint
        if (way != null) {
            Spacer(Modifier.height(24.dp))
            Chip(way, R.drawable.ic_m_guide, enabled = true, onClick = actions::onGuide, clickable = state.guideLabel != null)
        }
        if (state.placeChips.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            FlowChips(state.placeChips, actions::onPlace)
        }
    }
}

/**
 * Снимок «Что это?» (прототип `.snap`): 360 × 220, скругление 20, внутренняя тень сверху; пока снимка нет — полосатая
 * заглушка со значком камеры и подписью.
 */
@Composable
private fun LookPhotoImage(photo: androidx.compose.ui.graphics.ImageBitmap?, label: String) {
    val shape = RoundedCornerShape(20.dp)
    val inner = Modifier
        .size(LOOK_PHOTO_W, LOOK_PHOTO_H)
        .clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(Color(0x1F111827), Color.Transparent), endY = 10.dp.toPx()))
        }
    if (photo != null) {
        Image(bitmap = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = inner)
    } else {
        Row(
            inner.drawBehind {
                // repeating-linear-gradient(135deg, #DDE3EE 0 18px, #E8ECF4 18px 36px)
                drawRect(Color(0xFFE8ECF4))
                val period = 36.dp.toPx() * 1.4142f
                val band = 18.dp.toPx() * 1.4142f
                var x = -size.height
                while (x < size.width + size.height) {
                    val p = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x + band, 0f)
                        lineTo(x + band - size.height, size.height)
                        lineTo(x - size.height, size.height)
                        close()
                    }
                    drawPath(p, Color(0xFFDDE3EE))
                    x += period
                }
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.ic_m_camera, 40.dp, HubColors.Muted)
            Spacer(Modifier.width(12.dp))
            BasicText(label, style = HubType.Callout.copy(color = HubColors.Muted, fontSize = 24.sp))
        }
    }
}

private val LOOK_PHOTO_W = 360.dp
private val LOOK_PHOTO_H = 220.dp

/** Ссылка-пилюля прототипа (`.link-btn`): белая, кромка #CFE0FA, высота 60, поля 28, 24 / 600 синий. */
@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .height(60.dp)
            .background(Color.White, shape)
            .border(1.dp, HubColors.ChipBorder, shape)
            .padding(horizontal = 28.dp),
    ) {
        BasicText(label, style = HubType.Callout.copy(color = HubColors.Blue, fontSize = 24.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** Чипы мест (прототип `.chips`): переносятся строками, промежуток 24. */
@Composable
private fun FlowChips(labels: List<String>, onClick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Chip(label, R.drawable.ic_m_place, enabled = true, onClick = { onClick(i) })
        }
    }
}

private val pacer = SubtitlePacer()

/** Сколько слов фразы уже «сказано» (по темпу речи); null — фраза не звучит. Пересчёт — раз в ~50 мс. */
@Composable
internal fun spokenWords(phrase: String?): Int {
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

/** «Думаю» (прототип `.dots`): три синие точки 18, промежуток 12; прозрачность .3 → 1 (40 %) → .3, сдвиг .2 с, 1,2 с. */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(TYPING_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart), label = "typingPhase")
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        val d = ((phase - i * 0.2f / 1.2f) % 1f + 1f) % 1f
                        val k = if (d < 0.4f) EASE.transform(d / 0.4f) else 1f - EASE.transform((d - 0.4f) / 0.6f)
                        alpha = 0.3f + 0.7f * k
                    }
                    .background(HubColors.Blue, CircleShape),
            )
        }
    }
}

private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Чип прототипа (`.chip`): 72, поля 28, промежуток 10, #F5F9FF, кромка #CFE0FA, 26 / 600 #1A3BB0, значок 28. */
@Composable
private fun Chip(label: String, @DrawableRes glyph: Int, enabled: Boolean, onClick: () -> Unit, clickable: Boolean = true) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        enabled = enabled && clickable,
        modifier = Modifier
            .height(72.dp)
            .background(HubColors.ChipBg, shape)
            .border(1.dp, HubColors.ChipBorder, shape)
            .padding(horizontal = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, 28.dp, HubColors.Blue)
            Spacer(Modifier.width(10.dp))
            BasicText(
                label,
                style = HubType.Callout.copy(color = HubColors.BlueText, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Частые вопросы (прототип `renderFaq`): заголовок 44 / 700, строки-аккордеон по 104 dp (раскрыт не больше одного):
 * вопрос 30 sp до двух строк (раскрытый — жирный), шеврон 40; в раскрытом — ответ 32 sp до двух строк (или точки
 * «думаю»), «Проводить» чипом и «Показать полностью» ссылкой-пилюлей. Внизу — «Ещё вопросы (1/3)».
 */
@Composable
private fun FaqPanel(state: ScreenTwoState, faq: FaqUi, actions: ScreenTwoActions) {
    val texts = state.texts
    Column(Modifier.fillMaxSize()) {
        val titleStyle = HubType.Title.copy(color = HubColors.Ink, fontWeight = FontWeight.Bold, lineHeight = 60.sp)
        BasicText(texts.faqTitle, style = titleStyle, maxLines = 1, modifier = Modifier.cssLines(titleStyle))
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            faq.items.forEach { item ->
                androidx.compose.runtime.key(item.id) {
                    FaqRow(
                        item = item,
                        open = item.id == faq.openId,
                        answer = faq.answer.takeIf { item.id == faq.openId },
                        guide = faq.guide && item.id == faq.openId,
                        texts = texts,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun FaqRow(
    item: QuickQuestion,
    open: Boolean,
    answer: String?,
    guide: Boolean,
    texts: ScreenTexts,
    actions: ScreenTwoActions,
) {
    val shape = RoundedCornerShape(20.dp)
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(250, easing = FastOutSlowInEasing), label = "chevron")
    var expanded by remember(answer) { mutableStateOf(false) }
    var overflow by remember(answer) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (open) {
                    Modifier
                        .softShadow(shape, 12.dp, 0.10f)
                        .background(Brush.verticalGradient(listOf(Color.White, HubColors.OpenRow)), shape)
                        .border(1.5.dp, HubColors.Blue, shape)
                } else {
                    Modifier.card(shape)
                },
            ),
    ) {
        Pressable(
            onClick = { actions.onFaqToggle(item) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .padding(horizontal = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    item.text,
                    style = HubType.Tile.copy(color = HubColors.Ink, fontWeight = if (open) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).cssLines(HubType.Tile),
                )
                Spacer(Modifier.width(24.dp))
                Glyph(
                    R.drawable.ic_m_chevron,
                    40.dp,
                    if (open) HubColors.Blue else HubColors.ExitIcon,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
            }
        }
        if (open) {
            Column(
                Modifier.padding(start = 32.dp, end = 32.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (answer == null) {
                    TypingDots()
                } else {
                    val answerStyle = HubType.Body.copy(color = HubColors.Ink, fontSize = 32.sp, lineHeight = 41.6.sp)
                    BasicText(
                        answer,
                        style = answerStyle,
                        modifier = Modifier.cssLines(answerStyle),
                        maxLines = if (expanded) Int.MAX_VALUE else FAQ_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { r -> if (!expanded) overflow = r.hasVisualOverflow },
                    )
                    if (guide || overflow || expanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (guide) Chip(texts.guideShort, R.drawable.ic_m_guide, enabled = true, onClick = actions::onGuide)
                            if (overflow || expanded) LinkButton(if (expanded) texts.showLess else texts.showMore) { expanded = !expanded }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Нижняя панель прототипа: на главной — подсказка «Просто скажите вопрос, я слушаю» по центру (нажатие — фраза через
 * серверный STT); пока посетитель говорит и пока робот смотрит — «В начало» и «Стоп» (к приветствию); на ответе —
 * «В начало», в центре «Стоп» (говорит) или «Проводить: X» (есть место), справа «Что это?»; в частых вопросах —
 * «В начало» и «Стоп» / «Ещё вопросы (n/m)». Поле ввода и подпись сборки — только эмулятор.
 */
@Composable
private fun BottomBar(state: ScreenTwoState, actions: ScreenTwoActions, transition: TransitionLook, modifier: Modifier = Modifier) {
    val texts = state.texts
    val faq = state.faq
    val hearing = state.partial != null
    val home = state.homeView && faq == null && !hearing && !state.lookWaiting
    Box(
        modifier
            .fillMaxWidth()
            .height(ScreenTwoLayout.BottomBarHeight)
            .graphicsLayer { alpha = transition.right() }
            .padding(horizontal = 40.dp),
    ) {
        if (!home) {
            HubPill(texts.home, actions::onHome, glyph = R.drawable.ic_m_home, primary = false, modifier = Modifier.align(Alignment.CenterStart))
        }
        val center = when {
            home -> BottomCenter.HINT
            hearing || state.lookWaiting -> BottomCenter.STOP_HOME
            state.speaking -> BottomCenter.STOP
            faq != null -> BottomCenter.MORE
            state.guideLabel != null -> BottomCenter.GUIDE
            else -> BottomCenter.NONE
        }
        Box(Modifier.align(Alignment.Center)) {
            when (center) {
                BottomCenter.HINT -> AskVoice(texts.askVoice, actions::onAskVoice, enabled = !state.micOff)
                BottomCenter.STOP -> HubPill(texts.stop, actions::onStop, glyph = R.drawable.ic_m_stop, kind = PillKind.STOP)
                BottomCenter.STOP_HOME -> HubPill(texts.stop, { actions.onStop(); actions.onHome() }, glyph = R.drawable.ic_m_stop, kind = PillKind.STOP)
                BottomCenter.MORE -> HubPill(faq?.more.orEmpty(), actions::onFaqMore, glyph = R.drawable.ic_m_chevron, primary = false)
                BottomCenter.GUIDE -> HubPill(state.guideLabel.orEmpty(), actions::onGuide, glyph = R.drawable.ic_m_guide)
                BottomCenter.NONE -> Unit
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            // Прототип: «Что это?» на странице ответа всегда (и пока робот говорит).
            val look = !home && faq == null && !hearing && !state.lookWaiting
            if (state.textInput) {
                TextInput(texts.inputHint, enabled = !state.thinking, onSend = actions::onSend, modifier = Modifier.width(if (look) 280.dp else 400.dp))
            }
            if (look) HubPill(texts.look, actions::onLook, glyph = R.drawable.ic_m_camera, primary = false, modifier = Modifier.padding(start = 16.dp))
        }
        if (state.buildLabel.isNotEmpty()) {
            BasicText(
                state.buildLabel,
                style = HubType.Caption.copy(color = HubColors.Faint, fontSize = HubType.Caption.fontSize * 0.8f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp),
            )
        }
    }
}

private enum class BottomCenter { HINT, STOP, STOP_HOME, MORE, GUIDE, NONE }

/**
 * Подсказка-кнопка прототипа (`.hint`): пилюля 84, белая .75, кромка #D6E2F5, синий круг 60 со значком 28 и кольцом
 * `micPulse` (0 → 14 dp, .35 → 0 за 70 % от 2,6 с), подпись 26 / 400.
 */
@Composable
private fun AskVoice(label: String, onClick: () -> Unit, enabled: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(84.dp)
            .background(Color.White.copy(alpha = 0.75f), shape)
            .border(1.dp, HubColors.Border, shape)
            .padding(start = 12.dp, end = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicDot()
            Spacer(Modifier.width(16.dp))
            BasicText(label, style = HubType.Callout.copy(color = HubColors.LangText), maxLines = 1)
        }
    }
}

/** Синий круг с микрофоном и расходящимся кольцом (прототип `micPulse`, 2,6 с ease-out). */
@Composable
private fun MicDot() {
    val t = rememberInfiniteTransition(label = "mic")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(MIC_PULSE_MS, easing = LinearEasing), RepeatMode.Restart), label = "micPulse")
    Box(
        Modifier
            .size(60.dp)
            .drawBehind {
                val k = (p / 0.7f).coerceAtMost(1f)
                val e = PULSE_EASE.transform(k)
                if (p < 0.7f) {
                    drawCircle(
                        HubColors.Blue.copy(alpha = 0.35f * (1f - e)),
                        radius = size.minDimension / 2f + 14.dp.toPx() * e,
                    )
                }
            }
            .background(HubColors.Blue, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(R.drawable.ic_m_mic, 28.dp, Color.White)
    }
}

private val PULSE_EASE = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private const val MIC_PULSE_MS = 2_600

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
                .background(Color(0x4D14286E))
                .pointerInput(Unit) { detectTapGestures(onTap = { actions.onExitCancel() }) },
        )
        DialogCard {
            BasicText(texts.exitQuestion, style = DIALOG_TITLE)
            Spacer(Modifier.height(12.dp))
            BasicText(texts.exitSub, style = DIALOG_SUB)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HubPill(texts.exitNo, actions::onExitCancel)
                HubPill(texts.exitYes, actions::onExitConfirm, primary = false)
            }
        }
    }
}

/**
 * Карточка-диалог прототипа (`.exit-card`): скругление 28, белый градиент к #FBFDFF, кромка 1 dp, лёгкая тень, поля
 * 40 × 48; ширина — по содержимому (клавиатура пароля — 850 dp).
 */
@Composable
fun DialogCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .softShadow(shape, 12.dp, 0.10f)
            .background(Brush.verticalGradient(listOf(Color.White, HubColors.CardBottom)), shape)
            .border(1.dp, HubColors.CardBorder, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private val TILE_GAP = 24.dp
private const val ANSWER_LINES = 3
private val TILE_HEIGHT = 156.dp
private const val TILE_COLUMNS = 3

/** Пузырь под роботом: верх от основной зоны (робот 85 + 390 + промежуток 18) и место «робот + пузырь» (dp). */
private val SUB_TOP = 493.dp
private const val SUBS_SPACE = 200f
private const val SUB_IN_MS = 200
private const val MOOD_ID = "mood"
private const val FAQ_LINES = 2
private const val SPOKEN_TICK_MS = 50L
private const val TYPING_PERIOD_MS = 1_200
const val DIAG_HOLD_MILLIS = 3_000L
