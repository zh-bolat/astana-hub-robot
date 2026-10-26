package kz.astanahub.robot.ui.design

import androidx.compose.animation.core.SpringSpec as ComposeSpring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kz.astanahub.robot.R
import kz.astanahub.robot.ui.motion.SpringSpec

/**
 * Дизайн-токены (docs/ui-redesign/DESIGN.md). Вид — прототип `docs/design/robot-mvp.html` (финальная версия каскада):
 * экран лица — чёрный с голубыми светящимися глазами; страницы приложения — белые, синий акцент, тёмно-синие
 * заголовки, белые карточки с мягкой тенью, шрифт Nunito. Значения — вычисленные стили прототипа (px сцены 1600 ×
 * 900 = dp киоска).
 */
object HubColors {
    /** Экран лица. */
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val EyeBlue = Color(0xFF3AA8F5)

    /** Страница приложения. */
    val Background = Color(0xFFFFFFFF)
    val Navy = Color(0xFF0E1A3D)
    val Ink = Color(0xFF111827)
    val Muted = Color(0xFF5B6475)
    val Faint = Color(0xFF9AA3B5)
    val Line = Color(0xFFDDE3EE)
    val Border = Color(0xFFD6E2F5)
    val Blue = Color(0xFF1E4FFF)
    val BlueText = Color(0xFF1A3BB0)
    val NavyButton = Color(0xFF0F1E5C)
    val ButtonText = Color(0xFF1B2440)
    val LangText = Color(0xFF33405C)
    val ExitIcon = Color(0xFF5B6B8C)
    val ChipBg = Color(0xFFF5F9FF)
    val ChipBorder = Color(0xFFCFE0FA)
    val Badge = Color(0xFFEEF4FF)
    val CardBorder = Color(0x143C64C8)
    val CardBottom = Color(0xFFFBFDFF)
    val SubBorder = Color(0xFFDCE6F5)
    val OpenRow = Color(0xFFF2F6FF)
    val AmberBg = Color(0xFFFFF4DB)
    val AmberFg = Color(0xFF8A4B00)
    val AmberLine = Color(0xFFF0D9A8)
    val Shadow = Color(0xFF1E326E)
    val BlueShadow = Color(0xFF1E4FFF)

    /** Прежние имена (общие компоненты): текст, вторичный, третичный, кромка, заливки. */
    val Label = Ink
    val LabelSecondary = Muted
    val LabelTertiary = Faint
    val Hairline = Line
    val Fill = ChipBg
    val FillPressed = OpenRow

    /** Карточка-диалог поверх страницы. */
    val Surface = White

    /** Затемнение под диалогом (на API 31+ страница ещё и размыта). */
    val Scrim = Color(0x470F1E5C)
}

/** Nunito (вшит в APK, res/font/nunito_*): мягкий округлый гротеск прототипа, все казахские буквы. */
object HubFonts {
    val Nunito = FontFamily(
        Font(R.font.nunito_regular, FontWeight.Normal),
        Font(R.font.nunito_semibold, FontWeight.SemiBold),
        Font(R.font.nunito_bold, FontWeight.Bold),
        Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    )

    /** Прежнее имя. */
    val Inter = Nunito
}

/** Типографика прототипа (размеры — в sp сцены 1600 × 900). */
object HubType {
    private val f = HubFonts.Nunito
    val Display = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, lineHeight = 60.sp)
    val Title = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 52.sp)
    val Headline = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp)
    val Answer = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 52.sp)
    val Body = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 38.sp)
    val Tile = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp)
    val Callout = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 35.sp)
    val Button = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val ButtonPrimary = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp)
    val Caption = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp)
}

/** Отступы (сетка 8 dp) и радиусы. Минимальная зона касания — 44 dp (на киоске берём 64). */
object HubSpace {
    val XS = 8.dp
    val S = 16.dp
    val M = 24.dp
    val L = 32.dp
    val XL = 48.dp
    val XXL = 64.dp
    val TouchMin = 44.dp
    val TouchKiosk = 64.dp
}

/**
 * Движение: пружины Apple (ζ, response) → Compose `spring(dampingRatio, stiffness = (2π / response)²)`.
 * По умолчанию без отскока; отскок — только после жеста с импульсом ([Momentum]).
 */
object HubMotion {
    /** Обычный UI: появление, смена состояния. */
    val Default = HubSpring(dampingRatio = 1f, response = 0.35f)

    /** Быстрый отклик: смена выделения, мелкие элементы. */
    val Snappy = HubSpring(dampingRatio = 1f, response = 0.25f)

    /** Крупные поверхности: экран, лицо при смене слоя. */
    val Gentle = HubSpring(dampingRatio = 1f, response = 0.5f)

    /** После жеста с импульсом (бросок, отпускание): лёгкий отскок. */
    val Momentum = HubSpring(dampingRatio = 0.8f, response = 0.35f)

    /** Нажатие: scale 0.97 сразу на touch-down. */
    val Press = HubSpring(dampingRatio = 1f, response = 0.12f)

    const val PRESS_SCALE = 0.97f
}

/** Пружина в терминах Apple для Compose и для движка лица. */
class HubSpring(val dampingRatio: Float, val response: Float) {
    val stiffness: Float = SpringSpec.stiffnessFor(response)

    fun <T> compose(visibilityThreshold: T? = null): ComposeSpring<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    fun toFaceSpec(): SpringSpec = SpringSpec(dampingRatio, response)
}
