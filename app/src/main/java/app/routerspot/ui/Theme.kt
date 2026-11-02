package app.routerspot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.routerspot.R

/** Цвета из согласованного дизайна: сканди-минимализм, один кислотный акцент, малиновая тревога. */
@Immutable
data class Palette(
    val bg: Color,
    val panel: Color,
    val card: Color,
    val ink: Color,
    val mute: Color,
    val line: Color,
    val off: Color,
    val accent: Color,
    val onAccent: Color,
    val alarm: Color,
    val dots: Color,
)

val LightPalette = Palette(
    bg = Color(0xFFF2F2EF), panel = Color(0xFFFAFAF8), card = Color(0xFFFFFFFF),
    ink = Color(0xFF111214), mute = Color(0xFF5E6168), line = Color(0xFFD6D7D2), off = Color(0xFFB4B6B1),
    accent = Color(0xFFC6FF3D), onAccent = Color(0xFF111214), alarm = Color(0xFFD1135F), dots = Color(0x22111214),
)

val DarkPalette = Palette(
    bg = Color(0xFF0E0F11), panel = Color(0xFF131518), card = Color(0xFF17191C),
    ink = Color(0xFFECEDE8), mute = Color(0xFF9A9DA3), line = Color(0xFF2A2D31), off = Color(0xFF4A4D52),
    accent = Color(0xFFC6FF3D), onAccent = Color(0xFF111214), alarm = Color(0xFFFF4D8D), dots = Color(0x1CECEDE8),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Manrope = FontFamily((400..800 step 100).map { variable(R.font.manrope, it) })
val Mono = FontFamily(listOf(400, 500, 700).map { variable(R.font.jetbrains_mono, it) })

object Type {
    val display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-0.035).em)
    val title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.03).em)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyBold = body.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val small = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp)
    val button = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = (-0.01).em)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 0.12.em)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val stat = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.02).em)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

val colors: Palette
    @Composable get() = LocalPalette.current

@Composable
fun RouterSpotTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) DarkPalette else LightPalette, content = content)
}
