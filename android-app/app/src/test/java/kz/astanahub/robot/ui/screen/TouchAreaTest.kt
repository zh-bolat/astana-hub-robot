package kz.astanahub.robot.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kz.astanahub.robot.R
import kz.astanahub.robot.ui.face.PillButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Зона касания кнопок = вся видимая кнопка (ревью M3-r1, B1): концы пилюль, поля строк и плиток нажимаются, а не
 * только середина внутри отступов. Компоненты рисуются по-настоящему (Robolectric + Compose).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchAreaTest {

    @get:Rule
    val rule = createComposeRule()

    /** Пауза дольше защиты от повторного нажатия ([REPEAT_GUARD_MS]). */
    private fun gap() = org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(REPEAT_GUARD_MS + 50))

    @Test
    fun `quick double tap runs the action once, a later tap runs it again`() {
        var clicks = 0
        rule.setContent {
            Pressable(onClick = { clicks++ }, modifier = Modifier.testTag("tile").width(300.dp).height(150.dp)) {}
        }
        rule.onNodeWithTag("tile").performTouchInput { click(center) }
        rule.onNodeWithTag("tile").performTouchInput { click(center) } // дрожащий двойной тап
        rule.waitForIdle()
        assertEquals(1, clicks)
        gap()
        rule.onNodeWithTag("tile").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(2, clicks)
    }

    @Test
    fun `pill - both ends inside the padding are pressable`() {
        var clicks = 0
        rule.setContent {
            Box(Modifier.width(600.dp)) {
                HubPill("Проводить: Кафе", { clicks++ }, glyph = R.drawable.ic_m_guide, modifier = Modifier.testTag("pill"))
            }
        }
        val pill = rule.onNodeWithTag("pill")
        pill.performTouchInput { click(Offset(3f, centerY)) } // на стрелке у левого края
        gap()
        pill.performTouchInput { click(Offset(width - 3f, centerY)) } // у правого края
        gap()
        pill.performTouchInput { click(Offset(centerX, 3f)) } // у верхнего края
        rule.waitForIdle()
        assertEquals(3, clicks)
    }

    @Test
    fun `pressable with padding inside its modifier - the whole box is the touch area`() {
        var clicks = 0
        rule.setContent {
            Pressable(
                onClick = { clicks++ },
                modifier = Modifier.testTag("row").fillMaxWidth().height(76.dp).padding(horizontal = 28.dp, vertical = 22.dp),
            ) {}
        }
        rule.onNodeWithTag("row").performTouchInput { click(Offset(4f, 4f)) } // поле строки, вне отступов текста
        gap()
        rule.onNodeWithTag("row").performTouchInput { click(Offset(width - 4f, height - 4f)) }
        rule.waitForIdle()
        assertEquals(2, clicks)
    }

    @Test
    fun `circle button - edge is pressable, and it is at least 44 dp`() {
        var clicks = 0
        rule.setContent { CircleButton(R.drawable.ic_m_close, { clicks++ }, modifier = Modifier.testTag("circle")) }
        val node = rule.onNodeWithTag("circle")
        node.performTouchInput { click(Offset(width / 2f, 4f)) }
        rule.waitForIdle()
        assertEquals(1, clicks)
        val size = node.fetchSemanticsNode().size
        val density = rule.density.density
        assert(size.width / density >= 44f && size.height / density >= 44f)
    }

    @Test
    fun `island pill button over the face - edges pressable`() {
        var clicks = 0
        rule.setContent { PillButton("Остановить", { clicks++ }, modifier = Modifier.testTag("stop")) }
        rule.onNodeWithTag("stop").performTouchInput { click(Offset(3f, centerY)) }
        rule.onNodeWithTag("stop").performTouchInput { click(Offset(width - 3f, centerY)) }
        rule.waitForIdle()
        assertEquals(2, clicks)
    }

    @Test
    fun `a swipe that starts on a pill is not a press (screen 2 can be swiped from a button)`() {
        var clicks = 0
        rule.setContent {
            Box(Modifier.size(800.dp, 200.dp)) {
                HubPill("Спросить голосом", { clicks++ }, modifier = Modifier.testTag("pill"))
            }
        }
        rule.onNodeWithTag("pill").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test
    fun `pressed state is visible on touch-down`() {
        val pressed = mutableStateOf(false)
        rule.setContent {
            Pressable(onClick = {}, pressed = pressed, modifier = Modifier.testTag("p").size(100.dp)) {}
        }
        rule.onNodeWithTag("p").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(true, pressed.value)
        rule.onNodeWithTag("p").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(false, pressed.value)
    }
}
