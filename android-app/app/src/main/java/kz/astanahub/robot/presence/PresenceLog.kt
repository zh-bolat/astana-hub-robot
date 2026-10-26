package kz.astanahub.robot.presence

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Живой лог присутствия для экрана диагностики: кого видит PersonApi (id лица, дистанция) и события трекера
 * (подошёл / id лица / ушёл), с временем. Список людей меняется каждые 0,5 с (дистанции) — строка в лог, только
 * когда сменился состав id или прошло [minGapMillis]. Запоминает id ближайшего лица — для «Следить за мной».
 * Не потокобезопасен: вызывать с одного потока (главного).
 */
class PresenceLog(
    private val maxLines: Int = MAX_LINES,
    private val minGapMillis: Long = MIN_GAP_MS,
    private val time: (Long) -> String = { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) },
) {
    private val lines = ArrayDeque<String>()
    private var lastIds: Set<Int>? = null
    private var lastAt = Long.MIN_VALUE / 2

    /** id последнего увиденного лица (≥ 0) или null — лиц не было. */
    var lastFaceId: Int? = null
        private set

    /** Новый список людей; true — в лог добавлена строка. */
    fun onPersons(list: List<SeenPerson>, now: Long): Boolean {
        list.filter { it.id >= 0 && it.distanceM > 0 }.minByOrNull { it.distanceM }?.let { lastFaceId = it.id }
        val ids = list.map { it.id }.toSet()
        if (ids == lastIds && now - lastAt < minGapMillis) return false
        lastIds = ids
        lastAt = now
        add(
            now,
            if (list.isEmpty()) "PersonApi: никого"
            else "PersonApi: " + list.sortedBy { it.distanceM }.joinToString("; ") {
                "id=${it.id} " + String.format(Locale.US, "%.2f м", it.distanceM)
            },
        )
        return true
    }

    fun onEvent(event: PresenceEvent, now: Long) {
        val text = when (event) {
            is PresenceEvent.Arrived -> {
                if (event.personId >= 0) lastFaceId = event.personId
                "→ подошёл id=${event.personId}" + if (event.greet) " (здороваемся)" else " (недавно здоровались)"
            }
            is PresenceEvent.FaceId -> {
                if (event.personId >= 0) lastFaceId = event.personId
                "→ id лица ${event.personId}"
            }
            PresenceEvent.Left -> "→ ушёл"
        }
        add(now, text)
    }

    fun text(): String = lines.joinToString("\n")

    private fun add(now: Long, text: String) {
        lines.addLast("${time(now)}  $text")
        while (lines.size > maxLines) lines.removeFirst()
    }

    companion object {
        const val MAX_LINES = 15
        const val MIN_GAP_MS = 2_000L
    }
}
