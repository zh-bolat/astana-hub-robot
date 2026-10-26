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
 * Дизайн-токены нового интерфейса (docs/ui-redesign/DESIGN.md). Строго монохром: чёрный фон, белые элементы,
 * иерархия — прозрачностью белого, не цветом. Ошибка — формой и движением.
 */
object HubColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /** Текст: основной / вторичный / третичный. */
    val Label = White
    val LabelSecondary = Color(0xA3FFFFFF) // 64 %
    val LabelTertiary = Color(0x66FFFFFF) // 40 %

    /** Тонкая кромка материала (свет на краю) и заливки. */
    val Hairline = Color(0x24FFFFFF) // 14 %
    val Fill = Color(0x1AFFFFFF) // 10 %
    val FillPressed = Color(0x29FFFFFF) // 16 %

    /** Материал «острова»: почти чёрный, чуть светлее фона — читается как отдельный слой. Без блюра (API 28). */
    val Island = Color(0xF2141414) // 95 %
}

/** Inter 4.1 — гротеск в духе SF Pro, все казахские буквы; вшит в APK (res/font/inter_*.ttf, подмножество). */
object HubFonts {
    val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
}

/**
 * Типографика киоска (ширина 1600 dp, экран 14" на ~1 м): размер, жирность и интерлиньяж — набором.
 * Трекинг зависит от размера: крупное — плотнее (отрицательный), мелкое — свободнее (apple-design §15).
 */
object HubType {
    val Display = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 72.sp, lineHeight = 78.sp, letterSpacing = (-0.025).em)
    val Title = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.015).em)
    val Headline = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.01).em)
    val Body = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 38.sp, letterSpacing = 0.em)
    val Callout = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.em)
    val Caption = TextStyle(fontFamily = HubFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.01.em)
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
