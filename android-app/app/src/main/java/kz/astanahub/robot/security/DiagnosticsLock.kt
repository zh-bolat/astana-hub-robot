package kz.astanahub.robot.security

import android.content.Context
import java.security.MessageDigest

/** Хеш PIN — тот же алгоритм, что в app/build.gradle.kts (diagPinHash). */
object DiagPin {
    const val ITERATIONS = 10_000

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        repeat(ITERATIONS - 1) { digest = md.digest(digest) }
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Как открывается экран диагностики. */
enum class DiagAccess {
    /** PIN не задан на роботе: жест ничего не делает. */
    DISABLED,

    /** PIN не задан на эмуляторе: открывается сразу. */
    OPEN,

    /** Нужен PIN. */
    PIN,
    ;

    companion object {
        fun of(pinHash: String, isRobot: Boolean): DiagAccess = when {
            pinHash.isNotEmpty() -> PIN
            isRobot -> DISABLED
            else -> OPEN
        }
    }
}

/** Счётчик неверных попыток и блокировка; хранится на диске, чтобы перезапуск не сбрасывал блокировку. */
interface LockStore {
    var failedAttempts: Int
    var lockedUntilMillis: Long
}

class PrefsLockStore(context: Context) : LockStore {
    private val prefs = context.applicationContext.getSharedPreferences("diag_lock", Context.MODE_PRIVATE)

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_ATTEMPTS, 0)
        set(value) { prefs.edit().putInt(KEY_ATTEMPTS, value).commit() }

    override var lockedUntilMillis: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_LOCKED_UNTIL, value).commit() }

    private companion object {
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until"
    }
}

/**
 * Проверка PIN: [MAX_ATTEMPTS] неверных подряд → блокировка на [LOCK_MILLIS].
 * [now] — часы реального времени (переживают перезапуск); если часы отвели назад,
 * блокировка не удлиняется больше чем на [LOCK_MILLIS] от текущего момента.
 */
class DiagPinGuard(
    private val pinHash: String,
    private val salt: String,
    private val store: LockStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class Locked(val remainingMillis: Long) : Result
    }

    /** Сколько ещё длится блокировка; 0 — не заблокировано. */
    fun lockRemainingMillis(): Long {
        val t = now()
        var until = store.lockedUntilMillis
        if (until <= t) return 0
        if (until - t > LOCK_MILLIS) {
            until = t + LOCK_MILLIS
            store.lockedUntilMillis = until
        }
        return until - t
    }

    fun check(pin: String): Result {
        val remaining = lockRemainingMillis()
        if (remaining > 0) return Result.Locked(remaining)
        if (pinHash.isEmpty()) return Result.Wrong(0)
        // Регистр не важен: в build.gradle.kts PIN тоже хешируется в нижнем регистре.
        val candidate = DiagPin.hash(pin.trim().lowercase(), salt)
        if (MessageDigest.isEqual(candidate.toByteArray(), pinHash.toByteArray())) {
            store.failedAttempts = 0
            store.lockedUntilMillis = 0
            return Result.Ok
        }
        val failed = store.failedAttempts + 1
        return if (failed >= MAX_ATTEMPTS) {
            store.failedAttempts = 0
            store.lockedUntilMillis = now() + LOCK_MILLIS
            Result.Locked(LOCK_MILLIS)
        } else {
            store.failedAttempts = failed
            Result.Wrong(MAX_ATTEMPTS - failed)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_MILLIS = 5 * 60_000L
    }
}

/**
 * Разблокировка диагностики в памяти процесса: сбрасывается через [IDLE_MILLIS] бездействия,
 * при уходе с экрана и при перезапуске приложения. [now] — монотонные часы (SystemClock.elapsedRealtime).
 */
class DiagSession(private val now: () -> Long) {
    @Volatile private var lastActivity = NEVER

    fun unlock() { lastActivity = now() }

    fun lock() { lastActivity = NEVER }

    /** Касание на экране диагностики продлевает разблокировку. */
    fun touch() { if (isUnlocked()) lastActivity = now() }

    fun isUnlocked(): Boolean {
        val last = lastActivity
        if (last == NEVER) return false
        if (now() - last >= IDLE_MILLIS) {
            lastActivity = NEVER
            return false
        }
        return true
    }

    companion object {
        const val IDLE_MILLIS = 5 * 60_000L
        private const val NEVER = Long.MIN_VALUE
    }
}

/** Всё вместе: можно ли сейчас находиться на экране диагностики. */
class DiagnosticsGate(
    val access: DiagAccess,
    val guard: DiagPinGuard,
    val session: DiagSession,
) {
    fun canEnter(): Boolean = when (access) {
        DiagAccess.DISABLED -> false
        DiagAccess.OPEN -> true
        DiagAccess.PIN -> session.isUnlocked()
    }
}
