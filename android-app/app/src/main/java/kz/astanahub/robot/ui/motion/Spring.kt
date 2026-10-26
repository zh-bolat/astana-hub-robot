package kz.astanahub.robot.ui.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Пружина в терминах Apple (WWDC 2018 «Designing Fluid Interfaces»): [dampingRatio] — затухание
 * (1 — без перелёта, < 1 — с отскоком), [response] — «за сколько секунд доходит» (это не длительность:
 * у пружины её нет). Масса 1: жёсткость = (2π / response)², коэффициент трения = 4π·ζ / response.
 * Тот же перевод — в Compose `spring(dampingRatio, stiffness)` (см. [kz.astanahub.robot.ui.design.HubMotion]).
 */
class SpringSpec(val dampingRatio: Float, val response: Float) {
    init {
        require(response > 0f) { "response must be > 0" }
        require(dampingRatio > 0f) { "dampingRatio must be > 0" }
    }

    /** Собственная частота ω = 2π / response, рад/с. */
    val omega: Double = 2.0 * PI / response

    /** Жёсткость при массе 1: ω². */
    val stiffness: Float get() = (omega * omega).toFloat()

    /** Коэффициент трения при массе 1: 2ζω = 4πζ / response. */
    val damping: Float get() = (2.0 * dampingRatio * omega).toFloat()

    override fun toString(): String = "Spring(ζ=$dampingRatio, response=${response}s)"

    companion object {
        /** stiffness = (2π / response)² — для Compose `spring(stiffness = …)`. */
        fun stiffnessFor(response: Float): Float {
            val w = 2.0 * PI / response
            return (w * w).toFloat()
        }
    }
}

/**
 * Точный шаг затухающего осциллятора (аналитическое решение, не Эйлер): устойчив при любом dt, детерминирован,
 * не копит ошибку при пропущенных кадрах. Без аллокаций: результат — в [SpringStep.value] / [SpringStep.velocity].
 */
object SpringMath {
    /**
     * Сдвигает пружину на [dt] секунд. [value]/[velocity] — текущее состояние, [target] — цель.
     * Результат пишется в [out] (переиспользуемый объект).
     */
    fun step(value: Float, velocity: Float, target: Float, spec: SpringSpec, dt: Float, out: SpringStep) {
        if (dt <= 0f) {
            out.value = value
            out.velocity = velocity
            return
        }
        val x0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val w = spec.omega
        val z = spec.dampingRatio.toDouble()
        val t = dt.toDouble()
        val x: Double
        val v: Double
        when {
            abs(z - 1.0) < CRITICAL_EPS -> {
                // Критическое затухание: x = e^(−ωt)·(x0 + (v0 + ωx0)·t).
                val e = exp(-w * t)
                val b = v0 + w * x0
                x = e * (x0 + b * t)
                v = e * (v0 - w * b * t)
            }
            z < 1.0 -> {
                // С отскоком: ωd = ω√(1−ζ²).
                val wd = w * sqrt(1.0 - z * z)
                val e = exp(-z * w * t)
                val c = cos(wd * t)
                val s = sin(wd * t)
                x = e * (x0 * c + (v0 + z * w * x0) / wd * s)
                v = e * (v0 * c - (w * w * x0 + z * w * v0) / wd * s)
            }
            else -> {
                // Пере-демпфированная: два экспоненциальных слагаемых.
                val r = sqrt(z * z - 1.0)
                val r1 = -w * (z - r)
                val r2 = -w * (z + r)
                val c2 = (v0 - r1 * x0) / (r2 - r1)
                val c1 = x0 - c2
                val e1 = exp(r1 * t)
                val e2 = exp(r2 * t)
                x = c1 * e1 + c2 * e2
                v = c1 * r1 * e1 + c2 * r2 * e2
            }
        }
        out.value = (target + x).toFloat()
        out.velocity = v.toFloat()
    }

    private const val CRITICAL_EPS = 1e-4
}

/** Переиспользуемый результат шага [SpringMath.step]. */
class SpringStep {
    var value = 0f
    var velocity = 0f
}

/**
 * Набор из [size] независимых пружин (по одной на параметр лица): значение, скорость, цель — в массивах,
 * шаг без аллокаций. Смена цели посреди движения сохраняет значение и скорость — морфинг прерываемый
 * и без «кирпичной стены» (apple-design §3).
 */
class SpringBank(val size: Int, specs: Array<SpringSpec>) {
    init {
        require(specs.size == size) { "specs.size ${specs.size} != size $size" }
    }

    private val specs: Array<SpringSpec> = specs.copyOf()
    val value = FloatArray(size)
    val velocity = FloatArray(size)
    val target = FloatArray(size)
    private val tmp = SpringStep()

    fun spec(i: Int): SpringSpec = specs[i]

    /** Сменить пружину параметра (например, медленнее при засыпании). Значение и скорость сохраняются. */
    fun setSpec(i: Int, spec: SpringSpec) {
        specs[i] = spec
    }

    /** Мгновенно поставить значение (без движения): первый кадр, тесты. */
    fun snap(i: Int, v: Float) {
        value[i] = v
        target[i] = v
        velocity[i] = 0f
    }

    fun snapAll(values: FloatArray) {
        for (i in 0 until size) snap(i, values[i])
    }

    /** Толчок: добавить скорость (единиц/с) — «подпрыгнуть», «встряхнуть». */
    fun impulse(i: Int, dv: Float) {
        velocity[i] += dv
    }

    fun step(dt: Float) {
        for (i in 0 until size) {
            SpringMath.step(value[i], velocity[i], target[i], specs[i], dt, tmp)
            value[i] = tmp.value
            velocity[i] = tmp.velocity
        }
    }

    /** Все пружины пришли к цели (с точностью [eps]) и почти не движутся. */
    fun atRest(eps: Float = 1e-3f): Boolean {
        for (i in 0 until size) {
            if (abs(value[i] - target[i]) > eps || abs(velocity[i]) > eps * 10f) return false
        }
        return true
    }
}
