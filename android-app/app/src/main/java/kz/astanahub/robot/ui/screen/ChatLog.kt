package kz.astanahub.robot.ui.screen

import androidx.compose.runtime.Immutable
import kz.astanahub.robot.backend.Citation
import kz.astanahub.robot.ui.MainUiState

enum class ChatRole { VISITOR, ROBOT }

/** Реплика в чате экрана 2. [id] — стабильный ключ (LazyColumn), растёт с каждой новой репликой. */
@Immutable
data class ChatItem(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val citations: List<Citation> = emptyList(),
)

/**
 * Чат экрана 2 из состояния [MainUiState] (чистый Kotlin, тест): MainViewModel держит только текущий ход
 * (`question` / `answer`, ответ растёт по фразам), а экран показывает разговор целиком.
 *
 * Правила (сверены с путями MainViewModel — новый вопрос, ответ по фразам, «шум» с откатом, «Назад», «Домой»):
 * - новый `question` (или тот же вопрос заново: ответ сброшен в null) — реплика посетителя;
 * - `answer` растёт (новый текст продолжает прежний) — обновляется реплика робота этого хода; не продолжает
 *   (старт поездки, ошибка после части ответа) — новая реплика робота; ответ без вопроса (приветствие) — тоже;
 * - вопрос отозван (сервер счёл шумом: `question` → null, ответа не было) — реплика посетителя убирается;
 *   на экран вернули прежний ход (`question`/`answer` прошлого хода) — это откат, не новая реплика;
 * - `citations` — к реплике робота текущего хода (пустые не стирают уже показанные);
 * - новый разговор (`conversation`, новый посетитель) — чат с чистого листа;
 * - хранится не больше [maxItems] реплик (старые уходят сверху).
 */
class ChatLog(private val maxItems: Int = MAX_ITEMS) {

    private val list = ArrayList<ChatItem>()

    /** Неизменяемый снимок для экрана; новый объект — только когда чат изменился. */
    var items: List<ChatItem> = emptyList()
        private set

    private var nextId = 1L
    private var conversation = Int.MIN_VALUE
    private var prevQuestion: String? = null
    private var prevAnswer: String? = null

    /** Реплики текущего хода (id; -1 — нет). */
    private var visitorId = NONE
    private var robotId = NONE

    /** true — чат изменился. */
    fun update(s: MainUiState): Boolean {
        var changed = false
        if (s.conversation != conversation) {
            conversation = s.conversation
            if (list.isNotEmpty()) {
                list.clear()
                changed = true
            }
            visitorId = NONE
            robotId = NONE
            prevQuestion = null
            prevAnswer = null
        }
        val q = s.question
        val a = s.answer

        if (q == null && prevQuestion != null) {
            // Вопрос сняли. Ответа не было — реплику отозвали (шум); был — ход просто закрыт («Домой», свёрнут FAQ).
            if (robotId == NONE && visitorId != NONE && a == null) changed = remove(visitorId) || changed
            visitorId = NONE
        }
        val newTurn = q != null && (q != prevQuestion || (prevAnswer != null && a == null))
        if (newTurn && q != null) {
            val restored = restoredTurn(q, a)
            if (restored != null) {
                // Откат к прошлому ходу: висящие вопросы без ответа после него убираем, новых реплик нет.
                while (list.isNotEmpty() && list.last().id != restored.second) {
                    list.removeAt(list.lastIndex)
                    changed = true
                }
                visitorId = restored.first
                robotId = restored.second
            } else {
                visitorId = append(ChatRole.VISITOR, q)
                robotId = NONE
                changed = true
            }
        }
        if (a == null) {
            // Ответа нет — у хода нет реплики робота (откат всегда с ответом, его указатели уже стоят).
            robotId = NONE
        } else if (a != prevAnswer) {
            val current = indexOf(robotId)
            if (current >= 0 && a.startsWith(list[current].text.trimEnd())) {
                if (list[current].text != a) {
                    list[current] = list[current].copy(text = a)
                    changed = true
                }
            } else if (current < 0 || list[current].text != a) {
                robotId = append(ChatRole.ROBOT, a)
                changed = true
            }
        }
        if (s.citations.isNotEmpty()) {
            val i = indexOf(robotId)
            if (i >= 0 && list[i].citations != s.citations) {
                list[i] = list[i].copy(citations = s.citations)
                changed = true
            }
        }
        prevQuestion = q
        prevAnswer = a
        if (list.size > maxItems) {
            list.subList(0, list.size - maxItems).clear()
            changed = true
        }
        if (changed) items = list.toList()
        return changed
    }

    /**
     * Ход, на который вернули экран: последняя реплика робота с текстом [a], перед ней — посетитель с [q], после неё —
     * только вопросы без ответа. (visitorId, robotId) или null.
     */
    private fun restoredTurn(q: String, a: String?): Pair<Long, Long>? {
        if (a == null) return null
        var i = list.lastIndex
        while (i >= 0 && list[i].role == ChatRole.VISITOR) i--
        if (i < 1) return null
        val robot = list[i]
        val visitor = list[i - 1]
        if (robot.text != a || visitor.role != ChatRole.VISITOR || visitor.text != q) return null
        return visitor.id to robot.id
    }

    private fun append(role: ChatRole, text: String): Long {
        val id = nextId++
        list.add(ChatItem(id, role, text))
        return id
    }

    private fun remove(id: Long): Boolean {
        val i = indexOf(id)
        if (i < 0) return false
        list.removeAt(i)
        return true
    }

    private fun indexOf(id: Long): Int {
        if (id == NONE) return -1
        for (i in list.indices.reversed()) if (list[i].id == id) return i
        return -1
    }

    companion object {
        const val MAX_ITEMS = 120
        private const val NONE = -1L
    }
}
