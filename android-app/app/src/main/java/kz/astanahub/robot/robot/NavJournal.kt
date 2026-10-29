package kz.astanahub.robot.robot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Журнал навигации для экрана диагностики (на роботе без adb другого способа увидеть, что происходит, нет):
 * запрос поездки, ответ startNavigation, статусы и коды из колбэков RobotApi, итог, ошибки, решения по
 * live-действиям `guide_to`. Живёт в памяти процесса — переживает закрытие/открытие экрана диагностики.
 * Тексты посетителя сюда не пишем (только id/имена точек карты и коды).
 * Потокобезопасен: колбэки RobotApi приходят из чужих потоков.
 */
object NavJournal {
    const val MAX_ENTRIES = 400

    data class Entry(val at: Long, val text: String, val repeat: Int = 1) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(at))
            return if (repeat > 1) "$time  $text (×$repeat)" else "$time  $text"
        }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Записи по порядку (старые — первыми). */
    val entries: StateFlow<List<Entry>> = _entries

    /** Часы журнала (тесты подменяют). */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    fun log(text: String) {
        Timber.i("NAV %s", text)
        synchronized(lock) {
            val last = buffer.lastOrNull()
            if (last != null && last.text == text) {
                // Одинаковые статусы подряд (например, «объезд препятствия») — одной строкой со счётчиком.
                buffer[buffer.size - 1] = last.copy(at = clock(), repeat = last.repeat + 1)
            } else {
                buffer.addLast(Entry(clock(), text))
                while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            }
            _entries.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Тексты записей без времени (тесты). */
    fun texts(): List<String> = _entries.value.map { it.text }
}
