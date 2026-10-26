package kz.astanahub.robot.ui.screen

import androidx.compose.runtime.Immutable
import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.backend.QuickQuestion
import kz.astanahub.robot.core.Lang
import kz.astanahub.robot.ui.TileIcons

/** Значок плитки — монохромная линия (res/drawable/ic_m_*.xml). */
enum class TileGlyph { PLACE, EVENT, INFO, WIFI, CHAT, FAQ, LOOK }

sealed interface TileAction {
    /** Частый вопрос — как кнопкой на прежнем экране (`submitButton`): «Где кафе?», «Проводи до…» и т. п. */
    data class Ask(val text: String) : TileAction

    /** Весь список частых вопросов (аккордеон по страницам, `openFaq`). */
    data object Faq : TileAction

    /** «Что это?» — снимок с камеры (`lookAround`). */
    data object Look : TileAction
}

@Immutable
data class Tile(val key: String, val glyph: TileGlyph, val label: String, val action: TileAction)

/** Плитки экрана 2 и подписи (чистые функции — тест). Набор — как на прежнем экране приветствия. */
object Tiles {

    /** Частых вопросов плитками (остальные — в «Частые вопросы»). */
    const val QUESTION_TILES = 4

    fun home(questions: List<QuickQuestion>, faqLabel: String, lookLabel: String): List<Tile> =
        questions.take(QUESTION_TILES).map { q -> Tile("q:${q.id}", glyphFor(q.text), q.text, TileAction.Ask(q.text)) } +
            Tile("faq", TileGlyph.FAQ, faqLabel, TileAction.Faq) +
            Tile("look", TileGlyph.LOOK, lookLabel, TileAction.Look)

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
