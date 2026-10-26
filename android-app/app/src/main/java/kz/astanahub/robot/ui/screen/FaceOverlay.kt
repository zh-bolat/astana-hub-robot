package kz.astanahub.robot.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.astanahub.robot.R
import kz.astanahub.robot.ui.design.HubColors
import kz.astanahub.robot.ui.design.HubType

/**
 * Экран лица прототипа (`.face-layer`, v8): сверху — логотип в белой пилюле, язык и выход; под глазами — состояние,
 * услышанное и крупные субтитры (слова зажигаются по темпу речи); без речи — чипы частых вопросов; снизу — «Меню»,
 * «Спросить голосом» / «Стоп». Белое на чёрном. Кнопки забирают касание (жесты лица под ними не начинаются).
 * [bottom] = false — низ занят карточкой «где» (её «Проводить» важнее).
 */
@Composable
fun FaceOverlay(state: ScreenTwoState, actions: ScreenTwoActions, onMenu: () -> Unit, bottom: Boolean, modifier: Modifier = Modifier) {
    val texts = state.texts
    Box(modifier.fillMaxSize()) {
        // Верх: логотип (удержание 3 с — наша админка), язык, выход.
        Row(
            Modifier.fillMaxWidth().height(ScreenTwoLayout.TopBarHeight).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .holdToActivate(state.diagnosticsHold, DIAG_HOLD_MILLIS, actions::onDiagnostics),
            ) {
                Image(painterResource(R.drawable.mf_brand_astana_hub), texts.title, Modifier.height(54.dp).width(220.dp))
            }
            Spacer(Modifier.weight(1f))
            LanguageSwitch(state.lang, actions::onLanguage, takeTouch = true)
            if (state.exitVisible) {
                Spacer(Modifier.width(28.dp))
                Box(Modifier.width(2.dp).height(44.dp).background(Color.White.copy(alpha = 0.25f)))
                Spacer(Modifier.width(28.dp))
                Pressable(
                    onClick = actions::onExit,
                    takeTouch = true,
                    modifier = Modifier
                        .size(CIRCLE_SIZE)
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                ) { Glyph(R.drawable.ic_m_close, 34.dp, Color.White) }
            }
        }
        // Под глазами: состояние, услышанное, субтитры или чипы.
        Column(Modifier.fillMaxWidth().padding(top = FACE_INFO_TOP), horizontalAlignment = Alignment.CenterHorizontally) {
            val phrase = state.phrase.takeIf { state.speakingId != null || state.busy }
            BasicText(
                state.status,
                style = HubType.Body.copy(color = Color.White.copy(alpha = 0.55f), fontSize = 30.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                state.partial?.let { "«$it»" }.orEmpty(),
                style = HubType.Body.copy(color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 1100.dp).heightIn(min = 38.dp),
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.widthIn(max = 1240.dp).heightIn(min = 126.dp), contentAlignment = Alignment.TopCenter) {
                if (phrase != null) {
                    val spoken = spokenWords(phrase)
                    val text = remember(phrase, spoken) { SubtitleWindow.text(phrase, spoken, Color.White.copy(alpha = 0.35f)) }
                    BasicText(
                        text,
                        style = HubType.Display.copy(fontSize = 48.sp, lineHeight = 62.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!state.busy && state.partial == null) {
                    // Прототип: чипы на 16 dp ниже верха субтитров (min-height 126, margin-top −110).
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        state.tiles.filter { it.action is TileAction.Ask }.take(3).forEach { tile ->
                            androidx.compose.runtime.key(tile.key) {
                                val shape = RoundedCornerShape(percent = 50)
                                Pressable(
                                    onClick = { actions.onTile(tile) },
                                    takeTouch = true,
                                    modifier = Modifier
                                        .height(72.dp)
                                        .background(Color.White.copy(alpha = 0.08f), shape)
                                        .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                                        .padding(horizontal = 30.dp),
                                ) {
                                    BasicText(
                                        tile.label,
                                        style = HubType.Callout.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 420.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Низ: «Меню» | «Спросить голосом» / «Стоп».
        if (bottom) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(ScreenTwoLayout.BottomBarHeight)
                    .padding(horizontal = 40.dp),
            ) {
                val shape = RoundedCornerShape(22.dp)
                Pressable(
                    onClick = onMenu,
                    takeTouch = true,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(SECONDARY_HEIGHT)
                        .background(Color.White.copy(alpha = 0.08f), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                        .padding(horizontal = 34.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_m_chevron, 34.dp, Color.White)
                        Spacer(Modifier.width(14.dp))
                        BasicText(texts.menu, style = HubType.Button.copy(color = Color.White))
                    }
                }
                Box(Modifier.align(Alignment.Center)) {
                    val pill = RoundedCornerShape(percent = 50)
                    if (state.busy) {
                        Pressable(
                            onClick = actions::onStop,
                            takeTouch = true,
                            modifier = Modifier.height(PILL_HEIGHT).background(Color.White, pill).padding(horizontal = 44.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Glyph(R.drawable.ic_m_stop, 36.dp, Color.Black)
                                Spacer(Modifier.width(14.dp))
                                BasicText(texts.stop, style = HubType.ButtonPrimary.copy(color = Color.Black))
                            }
                        }
                    } else {
                        val hearing = state.partial != null
                        Pressable(
                            onClick = actions::onAskVoice,
                            enabled = !state.micOff && !hearing,
                            takeTouch = true,
                            modifier = Modifier
                                .height(84.dp)
                                .background(Color.White.copy(alpha = 0.06f), pill)
                                .border(1.dp, Color.White.copy(alpha = 0.22f), pill)
                                .padding(start = 12.dp, end = 30.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(60.dp).background(HubColors.Blue, CircleShape), contentAlignment = Alignment.Center) {
                                    Glyph(R.drawable.ic_m_mic, 30.dp, Color.White)
                                }
                                Spacer(Modifier.width(16.dp))
                                BasicText(
                                    if (hearing) state.status else texts.faceHint,
                                    style = HubType.Callout.copy(color = Color.White.copy(alpha = 0.85f)),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                // Прототип: «Коснитесь экрана, чтобы открыть меню» — только когда касание правда ведёт в меню
                // (ответ на вопрос из приложения идёт на лице); в остальное время касание — наше (разбудить, перебить).
                if (state.tapToMenu) {
                    BasicText(
                        texts.tapHint,
                        style = HubType.Caption.copy(color = Color.White.copy(alpha = 0.45f), textAlign = TextAlign.End),
                        maxLines = 2,
                        modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 420.dp),
                    )
                }
            }
        }
    }
}

/**
 * Подсказка свайпа на правом краю экрана лица (прототип: «‹‹‹», бегут влево по очереди): свайп влево — приложение.
 */
@Composable
fun SwipeChevrons(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "chev")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "chevP")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        for (i in 0 until 3) {
            val delay = (2 - i) * 0.0625f
            BasicText(
                "‹",
                style = HubType.Title.copy(color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.graphicsLayer {
                    // 0–60–100 %: 0.25; на 30 % — 0.9 и сдвиг на 8 dp влево (как keyframes «chev»).
                    val q = ((p - delay) % 1f + 1f) % 1f
                    val k = when {
                        q < 0.3f -> q / 0.3f
                        q < 0.6f -> 1f - (q - 0.3f) / 0.3f
                        else -> 0f
                    }
                    alpha = 0.3f * (0.25f + 0.65f * k)
                    translationX = -8.dp.toPx() * k
                },
            )
        }
    }
}

/** Под глазами на экране лица (сцена: 92 + 420 + 18). */
private val FACE_INFO_TOP = 530.dp

/** Глаза на экране лица с подписями (v8): масштаб и сдвиг центра вверх (сцена: глаза 800 × 400 с центром на 302). */
const val FACE_UI_SCALE = 0.8f
val FACE_UI_SHIFT = (-148).dp
