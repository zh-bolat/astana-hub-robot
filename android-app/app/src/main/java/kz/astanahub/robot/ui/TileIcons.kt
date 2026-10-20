package kz.astanahub.robot.ui

import androidx.annotation.DrawableRes
import kz.astanahub.robot.R

/**
 * Двухцветная иконка плитки частого вопроса по его тексту: место, мероприятие, о Hub, Wi-Fi;
 * иначе — облачко вопроса. Иконки «Частые вопросы» и «Что это?» задаются отдельно.
 */
object TileIcons {

    enum class Kind(@DrawableRes val res: Int) {
        PLACE(R.drawable.ic_tile_place),
        EVENT(R.drawable.ic_tile_event),
        INFO(R.drawable.ic_tile_info),
        WIFI(R.drawable.ic_tile_wifi),
        CHAT(R.drawable.ic_tile_chat),
    }

    private val rules: List<Pair<Regex, Kind>> = listOf(
        "wi-?fi|парол|password|интернет|internet|принтер|printer|қызмет|service|сервис" to Kind.WIFI,
        "мероприят|событи|event|іс-шара|митап|meetup|хакатон|hackathon" to Kind.EVENT,
        "где|қайда|where|находит|орналас|кофе|coffee|кафе|cafe|туалет|toilet|restroom|этаж|floor|қабат" to Kind.PLACE,
        "astana hub|что такое|about|туралы|резидент|resident|стартап|startup" to Kind.INFO,
    ).map { (pattern, kind) -> Regex(pattern) to kind }

    fun kindFor(text: String): Kind {
        val lower = text.lowercase()
        return rules.firstOrNull { (re, _) -> re.containsMatchIn(lower) }?.second ?: Kind.CHAT
    }
}
