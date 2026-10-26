package kz.astanahub.robot.ui.screen

import kz.astanahub.robot.backend.Place
import kz.astanahub.robot.ui.MainUiState
import kz.astanahub.robot.ui.Phase
import kz.astanahub.robot.ui.Screen
import kz.astanahub.robot.ui.ScreenMode

/**
 * Состояние MainViewModel → что показывают экраны (чистые функции, тест). Правила — как у прежнего экрана
 * (`MainActivity.render*` до M3): когда «Стоп», когда «Проводить», что в частых вопросах.
 */
object ScreenTwoMapper {

    /** Экран 2 открыт: режим MENU и не едем (в поездке лицо обязано быть на экране). */
    fun menuOpen(s: MainUiState): Boolean = s.mode == ScreenMode.MENU && s.guidingTo == null

    /** Занят: думает / смотрит / говорит — «Стоп» вместо «Спросить голосом». */
    fun busy(s: MainUiState): Boolean = s.processing || s.phase == Phase.SPEAKING

    /** Реплика робота, которая звучит сейчас (живые субтитры), — последняя реплика робота, пока он говорит. */
    fun speakingId(s: MainUiState, chat: List<ChatItem>): Long? =
        if (s.phase == Phase.SPEAKING) chat.lastOrNull { it.role == ChatRole.ROBOT }?.id else null

    /** Промежуточный текст посетителя (говорит прямо сейчас). */
    fun partial(s: MainUiState): String? = s.partialQuestion?.takeIf { it.isNotBlank() }

    /** Место для «Проводить: …» под ответом: не занят, не едем, не в частых вопросах (там своя кнопка). */
    fun guideOffer(s: MainUiState): Place? =
        s.guideTarget?.takeIf { !busy(s) && s.guidingTo == null && s.screen != Screen.FAQ }

    /**
     * Частые вопросы (только на их экране): раскрытый вопрос, его ответ (если ответ именно на него), «Проводить»,
     * «Ещё вопросы (1/3)» — если страниц больше одной. [status] — подпись фазы («Думаю…»).
     */
    fun faq(s: MainUiState, status: (Phase) -> String, more: (page: Int, count: Int) -> String): FaqUi? {
        if (s.screen != Screen.FAQ) return null
        val openItem = s.faqPageItems.firstOrNull { it.id == s.faqOpenId }
        val answer = s.answer.takeIf { openItem != null && s.question == openItem.text.trim() }
        return FaqUi(
            items = s.faqPageItems,
            openId = s.faqOpenId,
            answer = answer,
            pending = status(if (s.processing) s.phase else Phase.THINKING),
            guide = answer != null && s.guideTarget != null && s.guidingTo == null,
            more = if (s.faqPageCount > 1) more(s.faqPage + 1, s.faqPageCount) else null,
        )
    }
}
