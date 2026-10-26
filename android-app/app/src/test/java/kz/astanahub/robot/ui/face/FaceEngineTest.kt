package kz.astanahub.robot.ui.face

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kz.astanahub.robot.ui.face.FaceParam as P

class FaceEngineTest {

    /** Тестовый звук: уровни задаёт тест. */
    private class FakeAudio(var mic: Float = 0f, var playback: Float = -1f, var voice: Boolean = false) : FaceAudioSource {
        override fun micLevel() = mic
        override fun playbackLevel() = playback
        override fun voiceActive() = voice
    }

    private val frameNanos = 16_666_667L
    private var now = 1_000_000_000L

    private fun FaceEngine.frames(n: Int, onFrame: (FaceEngine) -> Unit = {}) {
        repeat(n) {
            now += frameNanos
            update(now)
            onFrame(this)
        }
    }

    private fun engine(audio: FaceAudioSource = FaceAudioSource.SILENT, seed: Long = 7L) =
        FaceEngine(seed = seed, audio = audio).also { it.update(now) }

    @Test
    fun `every mood settles on its expression`() {
        val e = engine()
        for (mood in FaceMood.entries) {
            e.director.forcedMood = mood
            e.frames(240) // 4 с — больше самых медленных пружин (сон: response ×3.2)
            val target = Expressions.mood(mood)
            for (p in listOf(P.EYE_W, P.EYE_H, P.SMILE_L, P.ARC, P.LID_L, P.BROW_TILT_R, P.MOUTH_W, P.MOUTH_SMILE, P.QUESTION, P.ZZZ)) {
                val want = if (target.has(p)) target[p] else Expressions.NEUTRAL[p]
                assertEquals("$mood ${P.NAMES[p]}", want, e.springValue(p), 0.05f * maxOf(1f, abs(want)))
            }
        }
    }

    @Test
    fun `morph between any two moods is continuous - no frame jumps`() {
        val limits = FloatArray(P.COUNT) { p ->
            when (p) {
                P.GAZE_X, P.GAZE_Y, P.HEAD_FOLLOW_X, P.HEAD_FOLLOW_Y -> 0.6f // саккада — быстро, но не скачком
                P.HEAD_TILT, P.BROW_TILT_L, P.BROW_TILT_R, P.LID_ANGLE -> 4f
                P.SHAKE -> 12f
                P.OPEN_L, P.OPEN_R, P.SMILE_L, P.SMILE_R, P.ARC, P.LID_L, P.LID_R, P.EYE_ASYM, P.MOUTH_ROUND,
                P.QUESTION, P.BLUSH, P.SWEAT, P.ZZZ, P.GLOW, P.BROW_ALPHA, P.MICRO, P.BREATH_RATE,
                P.SQUASH, P.POP, P.HEAD_SCALE -> 0.2f // доли 0..1: самый крутой участок пружины < 20 % за кадр
                P.TALK, P.LISTEN -> 0.25f // коэффициенты реакции на звук — быстрые пружины
                P.MOUTH_OPEN -> 0.2f // рот быстрее остального (0.2 × 72 ед. ≈ 14 ед.)
                P.BREATH -> 0.2f
                else -> 14f // единицы лица (глаза, брови, рот): < 1 % ширины экрана за кадр
            }
        }
        val e = engine()
        val prev = FloatArray(P.COUNT)
        fun check(label: String) = { eng: FaceEngine ->
            for (p in 0 until P.COUNT) {
                val v = eng.springValue(p)
                assertTrue("$label ${P.NAMES[p]}: ${prev[p]}→$v", abs(v - prev[p]) <= limits[p])
                prev[p] = v
            }
        }
        for (a in FaceMood.entries) {
            for (b in FaceMood.entries) {
                if (a == b) continue
                e.director.forcedMood = a
                e.frames(30)
                for (p in 0 until P.COUNT) prev[p] = e.springValue(p)
                e.director.forcedMood = b
                e.frames(12, check("$a→$b"))
                // Прерываем посреди морфинга — обратно к a: снова без скачков.
                e.director.forcedMood = a
                e.frames(20, check("$a→$b→$a"))
            }
        }
    }

    @Test
    fun `changing the mood mid-flight keeps velocity`() {
        val e = engine()
        e.director.forcedMood = FaceMood.IDLE
        e.frames(60)
        e.director.forcedMood = FaceMood.SURPRISED
        e.frames(5)
        val v = e.velocity(P.EYE_H)
        assertTrue("eyes are growing: $v", v > 50f)
        e.director.forcedMood = FaceMood.SLEEP
        now += frameNanos
        e.update(now)
        // После смены цели скорость меняется плавно (по ускорению), а не обнуляется и не меняет знак мгновенно.
        assertTrue("velocity carried: before=$v after=${e.velocity(P.EYE_H)}", e.velocity(P.EYE_H) > v * 0.3f)
    }

    @Test
    fun `idle blinks every 2 to 6 seconds, sometimes twice`() {
        val e = engine()
        val starts = mutableListOf<Long>()
        var count = e.blinkCount
        e.frames(60 * 90) { eng ->
            if (eng.blinkCount != count) {
                count = eng.blinkCount
                starts += now / 1_000_000
            }
        }
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("blinks: ${starts.size}", starts.size in 14..60)
        assertTrue("longest gap ${gaps.max()}", gaps.max() <= FaceLife.BLINK_MAX_MS + 100)
        assertTrue("double blink present", gaps.any { it in 200L..300L })
    }

    @Test
    fun `blink closes the eyes fully and briefly`() {
        assertEquals(0f, FaceLife.blinkCurve(-1f), 0f)
        assertEquals(1f, FaceLife.blinkCurve(FaceLife.BLINK_CLOSE_MS + 5f), 0f)
        assertEquals(0f, FaceLife.blinkCurve(FaceLife.BLINK_MS), 0f)
        assertTrue(FaceLife.BLINK_MS <= 250f)
        var prev = 0f
        // Закрытие монотонно.
        for (ms in 0..70 step 5) {
            val v = FaceLife.blinkCurve(ms.toFloat())
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test
    fun `no blinking while asleep or petted`() {
        for (mood in listOf(FaceMood.SLEEP, FaceMood.PETTED, FaceMood.YAWN)) {
            val e = engine()
            e.director.forcedMood = mood
            e.frames(10)
            val before = e.blinkCount
            e.frames(60 * 20) { assertEquals(0f, it.blink, 0f) }
            assertEquals("$mood", before, e.blinkCount)
        }
    }

    @Test
    fun `same seed and inputs give the same face`() {
        val a = engine(seed = 42)
        val b = engine(seed = 42)
        val script = listOf(FaceActivity.IDLE, FaceActivity.THINKING, FaceActivity.SPEAKING, FaceActivity.TRAVELING)
        for (activity in script) {
            a.inputs.activity = activity
            b.inputs.activity = activity
            repeat(120) {
                now += frameNanos
                a.update(now)
                b.update(now)
            }
        }
        assertArrayEquals(a.pose.v, b.pose.v, 0f)
    }

    @Test
    fun `speaking mouth follows measured playback level`() {
        val audio = FakeAudio(playback = 0f)
        val e = engine(audio)
        e.inputs.activity = FaceActivity.SPEAKING
        e.frames(60)
        val closed = e.pose[P.MOUTH_OPEN]
        audio.playback = 0.2f
        e.frames(10)
        val open = e.pose[P.MOUTH_OPEN]
        assertTrue("open=$open closed=$closed", open > closed + 0.4f)
        audio.playback = 0f
        e.frames(30)
        assertTrue(e.pose[P.MOUTH_OPEN] < closed + 0.05f)
    }

    @Test
    fun `jittery speech level moves the mouth without frame jumps`() {
        val audio = FakeAudio(playback = 0f)
        val e = engine(audio)
        e.inputs.activity = FaceActivity.SPEAKING
        e.frames(60)
        val rnd = Random(9)
        var prev = e.pose[P.MOUTH_OPEN]
        var maxStep = 0f
        e.frames(600) {
            audio.playback = rnd.nextFloat() * 0.25f
            maxStep = maxOf(maxStep, abs(it.pose[P.MOUTH_OPEN] - prev))
            prev = it.pose[P.MOUTH_OPEN]
        }
        assertTrue("max mouth step per frame $maxStep", maxStep < 0.25f)
    }

    @Test
    fun `surprise while speaking keeps the mouth to the speech, in silence opens an o`() {
        val e = engine(FakeAudio(playback = 0f))
        e.inputs.emotion = FaceEmotion.SURPRISED
        e.inputs.activity = FaceActivity.SPEAKING
        e.frames(90)
        assertEquals(0f, e.target(P.MOUTH_OPEN), 1e-4f)
        e.inputs.activity = FaceActivity.IDLE
        e.frames(90)
        assertTrue(e.target(P.MOUTH_OPEN) > 0.2f)
        assertTrue(e.pose[P.MOUTH_OPEN] > 0.2f)
    }

    @Test
    fun `thinking emotion at rest looks up like thinking`() {
        val e = engine()
        e.inputs.emotion = FaceEmotion.THINKING
        var up = 0
        e.frames(600) { if (it.pose[P.GAZE_Y] < -0.3f) up++ }
        assertTrue("frames looking up: $up of 600", up > 450)
    }

    @Test
    fun `speaking without a measured level moves the mouth synthetically, silence keeps it closed`() {
        val e = engine(FakeAudio(playback = -1f))
        e.inputs.activity = FaceActivity.SPEAKING
        e.frames(30)
        var min = 1f
        var max = 0f
        e.frames(180) {
            min = minOf(min, it.pose[P.MOUTH_OPEN])
            max = maxOf(max, it.pose[P.MOUTH_OPEN])
        }
        assertTrue("range $min..$max", max - min > 0.3f)

        e.inputs.activity = FaceActivity.IDLE
        e.frames(60)
        e.frames(120) { assertTrue(it.pose[P.MOUTH_OPEN] < 0.02f) }
    }

    @Test
    fun `listening face perks up with the visitor's voice`() {
        val audio = FakeAudio(mic = 0f)
        val e = engine(audio)
        e.inputs.activity = FaceActivity.LISTENING
        e.inputs.userSpeaking = true
        e.frames(90)
        assertEquals(FaceMood.LISTENING, e.director.mood)
        val quiet = e.pose[P.BROW_L]
        audio.mic = 0.08f
        e.frames(20)
        assertTrue("brows ${e.pose[P.BROW_L]} vs $quiet", e.pose[P.BROW_L] > quiet + 4f)
    }

    @Test
    fun `vad voice alone switches idle to listening`() {
        val audio = FakeAudio(mic = 0.08f, voice = true)
        val e = engine(audio)
        e.frames(30)
        assertEquals(FaceMood.LISTENING, e.director.mood)
        audio.mic = 0f
        audio.voice = false
        e.frames(90)
        assertEquals(FaceMood.IDLE, e.director.mood)
    }

    @Test
    fun `steady hall noise without vad keeps the face idle and calm`() {
        // −30 дБFS гул атриума: громко, но VAD молчит.
        val audio = FakeAudio(mic = 0.03f, voice = false)
        val e = engine(audio)
        e.frames(60 * 10) { assertEquals(FaceMood.IDLE, it.director.mood) }
        // Огибающая микрофона вычла фон: реакции на гул нет.
        assertTrue("listen envelope ${e.pose.listen}", e.pose.listen < 0.05f)
    }

    @Test
    fun `face does not depend on uptime - 30 days later it moves exactly the same`() {
        // Робот-киоск работает неделями: время лица не должно терять точность (float от аптайма).
        val day = 86_400_000_000_000L
        val early = FaceEngine(seed = 3)
        val late = FaceEngine(seed = 3)
        var t1 = 1_000_000_000L
        var t2 = 30 * day
        early.update(t1)
        late.update(t2)
        var maxDiff = 0f
        repeat(60 * 20) {
            t1 += frameNanos
            t2 += frameNanos
            early.update(t1)
            late.update(t2)
            for (p in 0 until P.COUNT) maxDiff = maxOf(maxDiff, abs(early.pose[p] - late.pose[p]))
            maxDiff = maxOf(maxDiff, abs(early.pose.wobble - late.pose.wobble), abs(early.pose.zPhase - late.pose.zPhase))
        }
        assertTrue("max difference $maxDiff", maxDiff < 1e-3f)
    }

    @Test
    fun `sleep closes the eyes and breathes slowly`() {
        val e = engine()
        e.inputs.awake = false
        e.frames(60 * 8)
        assertEquals(FaceMood.SLEEP, e.director.mood)
        assertTrue(e.pose[P.OPEN_L] < 0.02f && e.pose[P.OPEN_R] < 0.02f)
        assertEquals(0.15f, e.springValue(P.BREATH_RATE), 0.02f)
        assertTrue(e.pose[P.ZZZ] > 0.9f)
    }

    @Test
    fun `happy entry bounces the body, reduced motion does not`() {
        val e = engine()
        e.frames(30)
        e.cue(FaceCue.ARRIVED)
        var peak = 0f
        e.frames(30) { peak = maxOf(peak, abs(it.pose[P.SQUASH])) }
        assertTrue("squash peak $peak", peak > 0.02f)

        val calm = engine()
        calm.reducedMotion = true
        calm.frames(30)
        calm.cue(FaceCue.ARRIVED)
        calm.frames(30) { assertEquals(0f, it.pose[P.SQUASH], 1e-4f) }
    }

    @Test
    fun `error shakes the head no`() {
        val e = engine()
        e.frames(30)
        e.cue(FaceCue.ERROR)
        var sign = 0
        var flips = 0
        e.frames(60) {
            val s = it.springValue(P.SHAKE)
            val now = if (s > 1f) 1 else if (s < -1f) -1 else 0
            if (now != 0 && sign != 0 && now != sign) flips++
            if (now != 0) sign = now
        }
        assertTrue("shake swings: $flips", flips >= 1)
    }

    @Test
    fun `tap reflex blinks or startles, never spams`() {
        val e = engine()
        e.frames(120)
        val first = e.reflex()
        // Тап сразу следом — без реакции.
        e.frames(5)
        assertEquals(FaceEngine.Reflex.NONE, e.reflex())
        // Серия частых тапов (по 400 мс) за 6 с: вздрогнуть — не чаще раза в 3 с, остальное — моргания.
        var startles = if (first == FaceEngine.Reflex.STARTLE) 1 else 0
        var blinks = if (first == FaceEngine.Reflex.BLINK) 1 else 0
        repeat(15) {
            e.frames(24)
            when (e.reflex()) {
                FaceEngine.Reflex.STARTLE -> startles++
                FaceEngine.Reflex.BLINK -> blinks++
                FaceEngine.Reflex.NONE -> Unit
            }
        }
        assertTrue("startles in ~6 s: $startles", startles <= 3)
        assertTrue("reacts: blinks=$blinks startles=$startles", blinks + startles >= 8)
    }

    @Test
    fun `every tap in silence gets a visible reaction - a blink in progress counts, right after one blinks again`() {
        // Ревью M2-r1, M1: тап сразу после моргания оставался без реакции (reflex NONE).
        for (seed in 1L..6L) {
            val e = engine(seed = seed)
            e.frames(60)
            repeat(40) { i ->
                e.frames(22 + (i % 5)) // тапы раз в ~370–430 мс — чаще моргают сами
                assertTrue("seed $seed tap $i", e.reflex() != FaceEngine.Reflex.NONE)
            }
        }
    }

    @Test
    fun `petted while speaking - the happy mouth still moves with the speech`() {
        // Ревью M2-r1, M3: у PETTED рот молчал во время речи.
        val e = engine()
        e.inputs.activity = FaceActivity.SPEAKING
        e.inputs.petting = true
        e.frames(60)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        e.frames(180) {
            lo = minOf(lo, it.pose[P.MOUTH_OPEN])
            hi = maxOf(hi, it.pose[P.MOUTH_OPEN])
        }
        assertEquals(FaceMood.PETTED, e.director.mood)
        assertTrue("mouth moves with speech while petted: ${hi - lo}", hi - lo > 0.15f)
        e.inputs.petting = false
    }

    @Test
    fun `no tap reflex while asleep or petted`() {
        val e = engine()
        e.director.forcedMood = FaceMood.SLEEP
        e.frames(30)
        assertEquals(FaceEngine.Reflex.NONE, e.reflex())
    }

    @Test
    fun `eyes go to the finger at once on touch`() {
        val e = engine()
        e.frames(60)
        e.inputs.touching = true
        e.inputs.touchX = 0.8f
        e.inputs.touchY = -0.5f
        e.frames(15) // 250 мс — саккада 0.16 с
        assertTrue("gaze ${e.pose[P.GAZE_X]}", e.pose[P.GAZE_X] > 0.6f)
        assertTrue(e.pose[P.GAZE_Y] < -0.3f)
        e.inputs.touching = false
    }

    @Test
    fun `petting closes the eyes happily and the head leans with the hand`() {
        val e = engine()
        e.frames(30)
        e.inputs.petting = true
        e.inputs.strokeVx = 1_200f
        e.frames(60)
        assertEquals(FaceMood.PETTED, e.director.mood)
        assertTrue(e.pose[P.OPEN_L] < 0.1f)
        assertTrue("leans: ${e.springValue(P.HEAD_TILT)}", e.springValue(P.HEAD_TILT) > Expressions.mood(FaceMood.PETTED)[P.HEAD_TILT] + 2f)
        e.inputs.petting = false
        e.inputs.strokeVx = 0f
        // Отпустили — ещё немного довольный (хвост), потом обычное лицо.
        e.frames(20)
        assertEquals(FaceMood.PETTED, e.director.mood)
        e.frames(60)
        assertEquals(FaceMood.IDLE, e.director.mood)
    }

    @Test
    fun `hold ring appears after 400 ms and fills by 3 s`() {
        val e = engine()
        e.frames(30)
        val startMs = now / 1_000_000
        e.inputs.touching = true
        e.inputs.holdStartMs = startMs
        e.frames(12) // 200 мс
        assertEquals(0f, e.pose.holdProgress, 0f)
        assertTrue(e.pose.holdAlpha < 0.05f)
        e.frames(60) // ~1.2 с
        assertTrue(e.pose.holdAlpha > 0.8f)
        assertTrue(e.pose.holdProgress in 0.2f..0.5f)
        e.frames(110) // > 3 с
        assertEquals(1f, e.pose.holdProgress, 1e-3f)
        // Сорвался — кольцо гаснет.
        e.inputs.holdStartMs = -1L
        e.frames(60)
        assertTrue(e.pose.holdAlpha < 0.05f)
    }

    @Test
    fun `random inputs and frame times never produce NaN`() {
        val rnd = Random(3)
        val audio = FakeAudio()
        val e = engine(audio)
        repeat(20_000) {
            if (rnd.nextFloat() < 0.02f) e.inputs.activity = FaceActivity.entries.random(rnd)
            if (rnd.nextFloat() < 0.01f) e.inputs.emotion = FaceEmotion.entries.random(rnd)
            if (rnd.nextFloat() < 0.005f) e.inputs.awake = !e.inputs.awake
            if (rnd.nextFloat() < 0.005f) e.cue(FaceCue.entries.random(rnd))
            e.inputs.userSpeaking = rnd.nextFloat() < 0.1f
            e.inputs.petting = rnd.nextFloat() < 0.01f
            audio.mic = rnd.nextFloat() * 0.3f
            audio.playback = if (rnd.nextBoolean()) -1f else rnd.nextFloat() * 0.3f
            now += when (rnd.nextInt(10)) {
                0 -> 0L
                1 -> 500_000_000L // пауза приложения
                else -> frameNanos
            }
            e.update(now)
            for (p in 0 until P.COUNT) assertTrue("${P.NAMES[p]}", e.pose[p].isFinite())
        }
    }

    @Test
    fun `frame loop does not allocate`() {
        // Отдельная JVM (см. FaceAllocationCheck): 5000 кадров по всем занятиям со сменой состояний — ни одной аллокации
        // в движке и геометрии; допуск — на служебные объекты самого замера.
        val bytes = FaceAllocationCheck.runIsolated()
        println("face frame loop: $bytes bytes allocated in ${FaceAllocationCheck.FRAMES} frames")
        assertTrue("allocated $bytes bytes in ${FaceAllocationCheck.FRAMES} frames", bytes < 1_024)
    }
}
