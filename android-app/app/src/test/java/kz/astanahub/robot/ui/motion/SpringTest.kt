package kz.astanahub.robot.ui.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {

    private val dt = 1f / 60f

    private fun run(spec: SpringSpec, from: Float, to: Float, v0: Float = 0f, frames: Int, onFrame: (Int, Float, Float) -> Unit = { _, _, _ -> }): SpringStep {
        val out = SpringStep()
        var x = from
        var v = v0
        repeat(frames) { i ->
            SpringMath.step(x, v, to, spec, dt, out)
            x = out.value
            v = out.velocity
            onFrame(i, x, v)
        }
        out.value = x
        out.velocity = v
        return out
    }

    @Test
    fun `response converts to stiffness as (2pi over response) squared`() {
        val spec = SpringSpec(dampingRatio = 1f, response = 0.4f)
        val expected = (2 * PI / 0.4).pow(2).toFloat()
        assertEquals(expected, spec.stiffness, 0.01f)
        assertEquals(expected, SpringSpec.stiffnessFor(0.4f), 0.01f)
        // Трение 4πζ/response.
        assertEquals((4 * PI * 1.0 / 0.4).toFloat(), spec.damping, 0.01f)
    }

    @Test
    fun `critically damped spring never overshoots and settles`() {
        val spec = SpringSpec(1f, 0.3f)
        var max = Float.NEGATIVE_INFINITY
        val end = run(spec, from = 0f, to = 100f, frames = 120) { _, x, _ -> max = maxOf(max, x) }
        assertTrue("overshoot: $max", max <= 100f + 1e-3f)
        assertEquals(100f, end.value, 0.05f)
        assertEquals(0f, end.velocity, 0.5f)
    }

    @Test
    fun `underdamped spring overshoots by the textbook amount`() {
        val zeta = 0.5f
        val spec = SpringSpec(zeta, 0.4f)
        var max = Float.NEGATIVE_INFINITY
        run(spec, from = 0f, to = 1f, frames = 240) { _, x, _ -> max = maxOf(max, x) }
        // Перелёт ступенчатого отклика: e^(−πζ/√(1−ζ²)) ≈ 16.3 % при ζ = 0.5.
        val expected = Math.exp(-PI * zeta / Math.sqrt(1.0 - zeta * zeta)).toFloat()
        assertEquals(1f + expected, max, 0.01f)
    }

    @Test
    fun `overdamped spring is slower than critical and does not overshoot`() {
        val critical = run(SpringSpec(1f, 0.3f), 0f, 1f, frames = 12)
        val over = run(SpringSpec(1.6f, 0.3f), 0f, 1f, frames = 12)
        assertTrue(over.value < critical.value)
        var max = 0f
        run(SpringSpec(1.6f, 0.3f), 0f, 1f, frames = 300) { _, x, _ -> max = maxOf(max, x) }
        assertTrue(max <= 1f + 1e-4f)
    }

    @Test
    fun `smaller response is snappier`() {
        val fast = run(SpringSpec(1f, 0.15f), 0f, 1f, frames = 6)
        val slow = run(SpringSpec(1f, 0.6f), 0f, 1f, frames = 6)
        assertTrue(fast.value > slow.value)
    }

    @Test
    fun `retargeting keeps value and velocity - no jump`() {
        val bank = SpringBank(1, arrayOf(SpringSpec(1f, 0.3f)))
        bank.snap(0, 0f)
        bank.target[0] = 100f
        repeat(8) { bank.step(dt) }
        val valueBefore = bank.value[0]
        val velocityBefore = bank.velocity[0]
        // Прервали на полпути: новая цель в другую сторону.
        bank.target[0] = -50f
        assertEquals(valueBefore, bank.value[0], 0f)
        assertEquals(velocityBefore, bank.velocity[0], 0f)
        bank.step(dt)
        val delta = bank.value[0] - valueBefore
        // Нет скачка: за кадр значение сдвинулось на единицы, а не прыгнуло к новой цели.
        assertTrue("delta=$delta", abs(delta) < 15f)
        // Скорость перенесена: тот же кадр «с места» (v = 0) дал бы заметно меньшее значение.
        val fromRest = SpringStep()
        SpringMath.step(valueBefore, 0f, -50f, bank.spec(0), dt, fromRest)
        val carried = delta - (fromRest.value - valueBefore)
        assertTrue("carried=$carried v·dt=${velocityBefore * dt}", carried > 0.5f * velocityBefore * dt)
    }

    @Test
    fun `analytic step matches fine euler integration`() {
        val spec = SpringSpec(0.7f, 0.35f)
        val exact = run(spec, 0f, 1f, v0 = 3f, frames = 30)
        // Эйлер с шагом 0.1 мс как эталон.
        var x = 0.0
        var v = 3.0
        val k = spec.stiffness.toDouble()
        val c = spec.damping.toDouble()
        val h = 1e-4
        repeat((30 * dt / h).toInt()) {
            val a = -k * (x - 1.0) - c * v
            v += a * h
            x += v * h
        }
        assertEquals(x.toFloat(), exact.value, 0.01f)
        assertEquals(v.toFloat(), exact.velocity, 0.1f)
    }

    @Test
    fun `huge time step stays stable`() {
        val out = SpringStep()
        SpringMath.step(0f, 500f, 10f, SpringSpec(0.3f, 0.2f), 5f, out)
        assertTrue(out.value.isFinite() && out.velocity.isFinite())
        assertEquals(10f, out.value, 0.01f)
    }

    @Test
    fun `zero dt changes nothing`() {
        val out = SpringStep()
        SpringMath.step(3f, 7f, 10f, SpringSpec(1f, 0.3f), 0f, out)
        assertEquals(3f, out.value, 0f)
        assertEquals(7f, out.velocity, 0f)
    }

    @Test
    fun `impulse on a resting spring makes it move and come back`() {
        val bank = SpringBank(1, arrayOf(SpringSpec(0.55f, 0.42f)))
        bank.snap(0, 0f)
        bank.impulse(0, 1.3f)
        var peak = 0f
        repeat(120) {
            bank.step(dt)
            peak = maxOf(peak, bank.value[0])
        }
        assertTrue("peak=$peak", peak in 0.03f..0.06f)
        assertTrue(bank.atRest(1e-3f))
    }
}
