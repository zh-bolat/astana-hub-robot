package kz.astanahub.robot

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.astanahub.robot.databinding.ActivityDiagnosticsBinding
import kz.astanahub.robot.di.AppContainer
import kz.astanahub.robot.presence.PresenceLog
import kz.astanahub.robot.robot.HeadDiagnostics
import kz.astanahub.robot.robot.NavJournal

/**
 * Раздел «Голова» экрана диагностики: команды мотора головы и слежения напрямую через RobotApi (мимо HeadController),
 * по каждой — код возврата и все ответы колбэков (в журнал навигации с префиксом «голова» и в окно раздела);
 * живой лог PersonApi (id лица, дистанция, время). ПРОВЕРИТЬ НА РОБОТЕ: есть ли у Nova мотор головы, знаки углов.
 */
internal class HeadDiagPanel(
    private val binding: ActivityDiagnosticsBinding,
    private val container: AppContainer,
    private val scope: LifecycleCoroutineScope,
    private val lifecycle: Lifecycle,
) {
    private val diag: HeadDiagnostics? = container.headDiagnostics
    private val presenceLog = PresenceLog()

    fun bind() {
        val d = diag
        val visible = if (d == null) View.GONE else View.VISIBLE
        listOf(binding.headLabel, binding.headRow1, binding.headRow2, binding.headRow3, binding.headHint, binding.headLog,
            binding.headPresenceLabel, binding.headPresenceLog).forEach { it.visibility = visible }
        if (d == null) return

        binding.headNod.setOnClickListener {
            run("Кивок ±$NOD°") {
                d.move(REL, REL, 0, NOD, ::report)
                delay(NOD_BACK_MS)
                d.move(REL, REL, 0, -NOD, ::report)
            }
        }
        binding.headLeft.setOnClickListener { run("Влево $TURN° (h +$TURN)") { d.move(REL, REL, TURN, 0, ::report) } }
        binding.headRight.setOnClickListener { run("Вправо $TURN° (h −$TURN)") { d.move(REL, REL, -TURN, 0, ::report) } }
        binding.headUp.setOnClickListener { run("Вверх $TILT° (v −$TILT)") { d.move(REL, REL, 0, -TILT, ::report) } }
        binding.headDown.setOnClickListener { run("Вниз $TILT° (v +$TILT)") { d.move(REL, REL, 0, TILT, ::report) } }
        binding.headReset.setOnClickListener { run("Сброс") { d.reset(::report) } }
        binding.headStatus.setOnClickListener { run("Статус головы") { d.status(::report) } }
        binding.headFollow.setOnClickListener {
            run("Следить за мной") {
                report("приложение (HeadController): слежение=" + if (container.head?.isFollowing == true) "да" else "нет")
                val id = withContext(Dispatchers.Main) { presenceLog.lastFaceId } ?: d.nearestFaceId()
                if (id == null) {
                    report("id лица нет: PersonApi никого не видит — встаньте лицом к роботу в 1–2 м и повторите")
                } else {
                    d.follow(id, ::report)
                }
            }
        }
        binding.headStopFollow.setOnClickListener { run("Стоп слежения") { d.stopFollow(::report) } }
        binding.headPersons.setOnClickListener { run("Кто перед роботом") { d.persons().forEach(::report) } }

        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    NavJournal.entries.collect { entries ->
                        val head = entries.filter { it.text.startsWith(PREFIX) }.takeLast(HEAD_LINES)
                        binding.headLog.text = if (head.isEmpty()) "— команды головы и ответы SDK появятся здесь —"
                        else head.joinToString("\n") { it.format() }
                    }
                }
                val persons = container.persons
                if (persons == null) {
                    binding.headPresenceLog.text = "PersonApi в этой сборке нет"
                } else {
                    binding.headPresenceLog.text = "— ждём данных PersonApi (обновляются при изменении) —"
                    launch {
                        persons.collect { list ->
                            if (presenceLog.onPersons(list, System.currentTimeMillis())) binding.headPresenceLog.text = presenceLog.text()
                        }
                    }
                    container.presence?.let { events ->
                        launch {
                            events.collect { e ->
                                presenceLog.onEvent(e, System.currentTimeMillis())
                                binding.headPresenceLog.text = presenceLog.text()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Команда SDK — не на главном потоке (вызовы RobotApi — IPC); исключения не роняют экран. */
    private fun run(name: String, block: suspend () -> Unit) {
        report("«$name»")
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Throwable) {
                report("«$name»: ошибка ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun report(line: String) = NavJournal.log("$PREFIX (диагн.): $line")

    private companion object {
        const val PREFIX = "голова"
        const val REL = "relative"
        const val NOD = 15
        const val NOD_BACK_MS = 600L
        const val TURN = 30
        const val TILT = 10
        const val HEAD_LINES = 20
    }
}
