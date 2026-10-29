package kz.astanahub.robot.ui.screen

import androidx.compose.runtime.Immutable
import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.TileIcons

/** Значок плитки — монохромная линия (res/drawable/ic_m_*.xml). */
enum class TileGlyph { PLACE, EVENT, INFO, WIFI, CHAT, FAQ, LOOK }

sealed interface TileAction {
    /**
     * Вопрос серверу (`submitButton`): [text] уходит агенту, [display] — строка «вопрос» на экране (у плиток-тем
     * прототипа — название темы); null — сам вопрос.
     */
    data class Ask(val text: String, val display: String? = null) : TileAction

    /** «Где что находится» — места карты чипами, без сервера (`whereList`). */
    data object Where : TileAction

    /** Весь список частых вопросов (аккордеон по страницам, `openFaq`). */
    data object Faq : TileAction

    /** «Что это?» — снимок с камеры (`lookAround`). */
    data object Look : TileAction
}

@Immutable
data class Tile(val key: String, val glyph: TileGlyph, val label: String, val action: TileAction)

/** Подписи и вопросы плиток главной на языке экрана (тексты прототипа `T`). */
@Immutable
data class TileTexts(
    val where: String,
    val events: String,
    val eventsQuestion: String,
    val about: String,
    val aboutQuestion: String,
    val wifi: String,
    val wifiQuestion: String,
    val faq: String,
    val look: String,
)

/** Плитки экрана 2 и подписи (чистые функции — тест). */
object Tiles {

    /**
     * Шесть тем прототипа (`goHome`): «Где что находится» — места карты без сервера; «Мероприятия», «Об Astana Hub»,
     * «Wi‑Fi и сервисы» — вопросы агенту (на экране — название темы); «Частые вопросы» — вопросы с сервера по страницам;
     * «Что это?» — снимок камеры.
     */
    fun home(t: TileTexts): List<Tile> = listOf(
        Tile("where", TileGlyph.PLACE, t.where, TileAction.Where),
        Tile("events", TileGlyph.EVENT, t.events, TileAction.Ask(t.eventsQuestion, display = t.events)),
        Tile("about", TileGlyph.INFO, t.about, TileAction.Ask(t.aboutQuestion, display = t.about)),
        Tile("wifi", TileGlyph.WIFI, t.wifi, TileAction.Ask(t.wifiQuestion, display = t.wifi)),
        Tile("faq", TileGlyph.FAQ, t.faq, TileAction.Faq),
        Tile("look", TileGlyph.LOOK, t.look, TileAction.Look),
    )

    /** Значок по смыслу вопроса — те же правила, что у прежних плиток ([TileIcons]). */
    fun glyphFor(text: String): TileGlyph = when (TileIcons.kindFor(text)) {
        TileIcons.Kind.PLACE -> TileGlyph.PLACE
        TileIcons.Kind.EVENT -> TileGlyph.EVENT
        TileIcons.Kind.INFO -> TileGlyph.INFO
        TileIcons.Kind.WIFI -> TileGlyph.WIFI
        TileIcons.Kind.CHAT -> TileGlyph.CHAT
    }

    /** Переключатель языка: RU / KZ / EN (подписи — как на прежнем экране). */
    val languages: List<Pair<Lang, String>> = listOf(Lang.RU to "RU", Lang.KK to "KZ", Lang.EN to "EN")

    /**
     * Подпись-источник под ответом робота: названия без повторов, не больше [MAX_SOURCES]; null — подписи нет
     * (старый сервер не шлёт `citations`, агент не искал). [one]/[many] — «Источник: %s» / «Источники: %s».
     */
    fun sourceCaption(citations: List<Citation>, one: (String) -> String, many: (String) -> String): String? {
        val titles = citations.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_SOURCES)
        return when (titles.size) {
            0 -> null
            1 -> one(titles[0])
            else -> many(titles.joinToString(" · "))
        }
    }

    const val MAX_SOURCES = 3
}
