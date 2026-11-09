package app.stranitsa.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** Токены ПК-версии: тот же язык, что на телефоне, но плотнее — под мышь и клавиатуру. */
@Immutable
data class Colors(
    val bg: Color, val side: Color, val ink: Color, val muted: Color, val faint: Color,
    val rule: Color, val accent: Color, val onAccent: Color, val tint: Color, val hover: Color,
    val field: Color, val isDark: Boolean,
)

// Нейтральная бумага без бежевого оттенка; один акцент — синие чернила.
val Light = Colors(
    bg = Color(0xFFFAFAF9), side = Color(0xFFF1F1EF), ink = Color(0xFF1B1B1A), muted = Color(0xFF5D5C58),
    faint = Color(0xFF8C8B86), rule = Color(0xFFE3E2DE), accent = Color(0xFF1F3FBF), onAccent = Color.White,
    tint = Color(0xFFE8EBF7), hover = Color(0x0D1B1B1A), field = Color.White, isDark = false,
)
val Dark = Colors(
    bg = Color(0xFF161615), side = Color(0xFF1E1E1C), ink = Color(0xFFECEBE6), muted = Color(0xFFA3A29C),
    faint = Color(0xFF6F6E69), rule = Color(0xFF2E2E2B), accent = Color(0xFF9DB4FF), onAccent = Color(0xFF10131F),
    tint = Color(0xFF1F2335), hover = Color(0x10ECEBE6), field = Color(0xFF1E1E1C), isDark = true,
)
val Amoled = Dark.copy(bg = Color.Black, side = Color(0xFF0D0D0C), rule = Color(0xFF262624), tint = Color(0xFF12151F), field = Color(0xFF0D0D0C))

object Fonts {
    val serif = FontFamily(
        Font("font/serif_regular.ttf", FontWeight.Normal),
        Font("font/serif_italic.ttf", FontWeight.Normal, FontStyle.Italic),
        Font("font/serif_semibold.ttf", FontWeight.SemiBold),
    )
    val sans = FontFamily(
        Font("font/plex_sans_regular.ttf", FontWeight.Normal),
        Font("font/plex_sans_medium.ttf", FontWeight.Medium),
        Font("font/plex_sans_semibold.ttf", FontWeight.SemiBold),
    )
}

private const val TNUM = "tnum"

object Type {
    val task = TextStyle(fontFamily = Fonts.serif, fontSize = 17.sp, lineHeight = 24.sp)
    val pageNo = TextStyle(fontFamily = Fonts.serif, fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = TNUM)
    val pageOf = TextStyle(fontFamily = Fonts.sans, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, lineHeight = 21.sp)
    val ui = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, lineHeight = 20.sp)
    val action = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val meta = TextStyle(fontFamily = Fonts.sans, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM)
    val label = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val code = TextStyle(fontFamily = Fonts.serif, fontSize = 44.sp, lineHeight = 52.sp, fontFeatureSettings = TNUM)
}

private val LocalColors = staticCompositionLocalOf { Light }

object Ui {
    val c: Colors @Composable get() = LocalColors.current
}

@Composable
fun AppTheme(colors: Colors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides colors, content = content)
}
