package kz.astanahub.robot.security

/**
 * Как работает крестик «Выйти». PIN тот же, что у диагностики (DIAG_PIN в secrets.properties),
 * и счётчик неверных попыток общий, чтобы PIN нельзя было подобрать через окно выхода.
 */
enum class ExitMode {
    /** PIN не задан на эмуляторе: обычное подтверждение «Выйти / Остаться». */
    CONFIRM,

    /** Выйти может только сотрудник: нужен PIN. */
    PIN,

    /** PIN не задан на роботе: выйти с экрана нельзя, крестик скрыт. */
    HIDDEN,
    ;

    companion object {
        fun of(access: DiagAccess): ExitMode = when (access) {
            DiagAccess.OPEN -> CONFIRM
            DiagAccess.PIN -> PIN
            DiagAccess.DISABLED -> HIDDEN
        }
    }
}

/** Цифры, набранные на экранной клавиатуре PIN. */
class PinBuffer(private val maxLength: Int = MAX_LENGTH) {
    private val digits = StringBuilder()

    val length: Int get() = digits.length
    val value: String get() = digits.toString()

    /** Можно ли уже проверять PIN (короче [MIN_LENGTH] PIN не бывает). */
    val isComplete: Boolean get() = digits.length >= MIN_LENGTH

    /** Добавляет цифру; false, если PIN уже максимальной длины. */
    fun append(digit: Char): Boolean {
        require(digit in '0'..'9') { "not a digit: $digit" }
        if (digits.length >= maxLength) return false
        digits.append(digit)
        return true
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits.setLength(digits.length - 1)
    }

    fun clear() = digits.setLength(0)

    /** Точки вместо цифр, чтобы посетитель за спиной не увидел PIN. */
    fun masked(): String = MASK.repeat(digits.length)

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 12
        private const val MASK = "●"
    }
}
