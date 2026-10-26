package kz.astanahub.robot.ui.face

import java.io.File

/**
 * Замер аллокаций кадра лица в отдельной JVM: в общей JVM тестов mock-агент MockK (ByteBuddy, из других
 * тестовых классов) меняет код и добавляет свои аллокации — замер там не про наш код.
 * Запускается из [FaceEngineTest]; печатает `ALLOCATED <байт> FRAMES <кадров>`.
 */
object FaceAllocationCheck {
    const val FRAMES = 5_000

    @JvmStatic
    fun main(args: Array<String>) {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        val audio = object : FaceAudioSource {
            var mic = 0.05f
            override fun micLevel() = mic
            override fun playbackLevel() = -1f
        }
        val engine = FaceEngine(audio = audio)
        val geometry = FaceGeometry()
        engine.inputs.emotion = FaceEmotion.HAPPY
        var now = 1_000_000_000L
        val activities = FaceActivity.entries.toTypedArray()
        // Тот же сценарий дважды: прогрев (JIT, загрузка классов, первые проходы веток), затем замер — по всем занятиям,
        // со сменой состояний, эмоцией, реакцией и голосом посетителя.
        fun scenario() {
            for (i in 0 until FRAMES) {
                if (i % 600 == 0) engine.inputs.activity = activities[(i / 600) % activities.size]
                if (i == 2_500) engine.cue(FaceCue.ERROR)
                // Жесты M2: касание (взгляд на палец), удержание (кольцо), поглаживание, рефлекс на тап.
                val inputs = engine.inputs
                inputs.touching = i % 900 in 100..400
                inputs.touchX = 0.5f
                inputs.touchY = -0.3f
                inputs.holdStartMs = if (i % 900 == 150) now / 1_000_000 else if (i % 900 == 400) -1L else inputs.holdStartMs
                inputs.petting = i % 1_300 in 700..900
                inputs.strokeVx = if (inputs.petting) 900f else 0f
                if (i % 450 == 0) engine.reflex()
                audio.mic = if (i % 120 < 60) 0.08f else 0f
                now += 16_666_667L
                engine.update(now)
                geometry.compute(engine.pose)
            }
        }
        scenario()
        scenario()
        val before = method.invoke(bean, id) as Long
        scenario()
        val bytes = (method.invoke(bean, id) as Long) - before
        println("ALLOCATED $bytes FRAMES $FRAMES")
    }

    /** Запустить [main] в новой JVM и вернуть число байт. */
    fun runIsolated(): Long {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = listOf(FaceEngine::class.java, FaceAllocationCheck::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(java, "-cp", classpath, FaceAllocationCheck::class.java.name)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "allocation check failed:\n$output" }
        val line = output.lineSequence().firstOrNull { it.startsWith("ALLOCATED ") } ?: error("no result:\n$output")
        return line.split(' ')[1].toLong()
    }
}
