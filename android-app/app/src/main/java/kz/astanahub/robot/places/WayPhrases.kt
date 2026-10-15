package kz.astanahub.robot.places

import kz.astanahub.robot.core.Lang

/** Фразы «где / проводи» на RU/KZ/EN — генерируются локально, работают без сети. */
object WayPhrases {

    fun side(side: Side, lang: Lang): String = when (lang) {
        Lang.RU -> when (side) {
            Side.AHEAD -> "впереди, за мной"; Side.BEHIND -> "у вас за спиной"
            Side.LEFT -> "слева от вас"; Side.RIGHT -> "справа от вас"
        }
        Lang.KK -> when (side) {
            Side.AHEAD -> "алда, менің артымда"; Side.BEHIND -> "сіздің артыңызда"
            Side.LEFT -> "сіздің сол жағыңызда"; Side.RIGHT -> "сіздің оң жағыңызда"
        }
        Lang.EN -> when (side) {
            Side.AHEAD -> "straight ahead, behind me"; Side.BEHIND -> "behind you"
            Side.LEFT -> "on your left"; Side.RIGHT -> "on your right"
        }
    }

    /** «в 1 метре / в 21 метре», иначе «в N метрах». */
    fun ruMetersLocative(n: Int): String = if (n % 10 == 1 && n % 100 != 11) "$n метре" else "$n метрах"

    fun whereIs(name: String, bearing: Bearing, lang: Lang, canGuide: Boolean): String {
        val s = side(bearing.visitorSide, lang)
        val m = Directions.humanMeters(bearing.distanceM)
        val main = when (lang) {
            Lang.RU -> if (m == null) "$name совсем рядом — $s." else "$name — $s, примерно в ${ruMetersLocative(m)}."
            Lang.KK -> if (m == null) "$name өте жақын — $s." else "$name $s, шамамен $m метр жерде."
            Lang.EN -> if (m == null) "$name is very close, $s." else "$name is $s, about $m meters away."
        }
        return if (canGuide) "$main ${offerGuide(lang)}" else main
    }

    fun offerGuide(lang: Lang): String = when (lang) {
        Lang.RU -> "Могу проводить."
        Lang.KK -> "Апарып салайын ба?"
        Lang.EN -> "I can take you there."
    }

    fun whereIsNoPose(name: String, lang: Lang, canGuide: Boolean): String {
        val main = when (lang) {
            Lang.RU -> "$name есть на моей карте, но я сейчас не могу определить направление."
            Lang.KK -> "$name менің картамда бар, бірақ қазір бағытын анықтай алмаймын."
            Lang.EN -> "$name is on my map, but I can't tell the direction right now."
        }
        return if (canGuide) "$main ${offerGuide(lang)}" else main
    }

    fun notOnMap(name: String, description: String?, lang: Lang): String {
        if (!description.isNullOrBlank()) return "$name: $description"
        return when (lang) {
            Lang.RU -> "$name нет на моей карте. Спросите, пожалуйста, на ресепшене."
            Lang.KK -> "$name менің картамда жоқ. Ресепшннен сұраңызшы."
            Lang.EN -> "$name is not on my map. Please ask at the reception."
        }
    }

    fun unknownPlace(lang: Lang): String = when (lang) {
        Lang.RU -> "Я не знаю такого места. Спросите, пожалуйста, на ресепшене."
        Lang.KK -> "Мен ондай жерді білмеймін. Ресепшннен сұраңызшы."
        Lang.EN -> "I don't know that place. Please ask at the reception."
    }

    fun hereAt(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Мы сейчас у точки «$name»."
        Lang.KK -> "Біз қазір «$name» жанындамыз."
        Lang.EN -> "We are at $name."
    }

    fun nearest(name: String, bearing: Bearing, lang: Lang): String {
        val s = side(bearing.visitorSide, lang)
        val m = Directions.humanMeters(bearing.distanceM)
        return when (lang) {
            Lang.RU -> "Ближайшее место — $name, $s" + (m?.let { ", примерно в ${ruMetersLocative(it)}." } ?: ".")
            Lang.KK -> "Ең жақын жер — $name, $s" + (m?.let { ", шамамен $it метр жерде." } ?: ".")
            Lang.EN -> "The nearest place is $name, $s" + (m?.let { ", about $it meters away." } ?: ".")
        }
    }

    fun unknownPosition(lang: Lang): String = when (lang) {
        Lang.RU -> "Я сейчас не знаю, где нахожусь."
        Lang.KK -> "Қазір қай жерде тұрғанымды білмеймін."
        Lang.EN -> "I don't know where I am right now."
    }

    fun guideStart(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Идите за мной, провожу: $name."
        Lang.KK -> "Менің артымнан жүріңіз, апарамын: $name."
        Lang.EN -> "Please follow me to $name."
    }

    fun guideArrived(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Мы пришли: $name."
        Lang.KK -> "Келдік: $name."
        Lang.EN -> "Here we are: $name."
    }

    fun guideAlreadyThere(name: String, lang: Lang): String = when (lang) {
        Lang.RU -> "Мы уже здесь: $name."
        Lang.KK -> "Біз осындамыз: $name."
        Lang.EN -> "We are already here: $name."
    }

    fun guideObstacle(lang: Lang): String = when (lang) {
        Lang.RU -> "Позвольте пройти, пожалуйста."
        Lang.KK -> "Жол беріңізші."
        Lang.EN -> "Excuse me, please let me pass."
    }

    fun guideNotLocalized(lang: Lang): String = when (lang) {
        Lang.RU -> "Не могу проводить: я не знаю, где нахожусь."
        Lang.KK -> "Апара алмаймын: қай жерде тұрғанымды білмеймін."
        Lang.EN -> "I can't take you there: I don't know where I am."
    }

    fun guideFailed(lang: Lang): String = when (lang) {
        Lang.RU -> "Не получилось дойти, извините. Спросите, пожалуйста, на ресепшене."
        Lang.KK -> "Жете алмадым, кешіріңіз. Ресепшннен сұраңызшы."
        Lang.EN -> "Sorry, I couldn't get there. Please ask at the reception."
    }

    fun guideCancelled(lang: Lang): String = when (lang) {
        Lang.RU -> "Хорошо, стою."
        Lang.KK -> "Жарайды, тоқтадым."
        Lang.EN -> "OK, I've stopped."
    }
}
