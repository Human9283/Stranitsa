package app.stranitsa.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.stranitsa.R
import app.stranitsa.core.ThemeMode
import kotlinx.coroutines.launch

/**
 * Дизайн-токены «строгой типографики».
 * Один акцент (синие чернила), остальное — оттенки текста на тёплом светлом фоне.
 * Серые подобраны с контрастом ≥ 4.5:1 к фону для всего, что нужно читать.
 */
@Immutable
data class StColors(
    val bg: Color,
    val ink: Color,
    /** Вторичный текст: подсказки, метки, вычеркнутое. */
    val muted: Color,
    /** Декоративное: номера строк, неактивные стрелки. */
    val faint: Color,
    val rule: Color,
    val accent: Color,
    val pressed: Color,
    val isDark: Boolean,
    /** Текст и значки на заливке акцентом. */
    val onAccent: Color = Color.White,
    /** Заливка выбранного: строка, текущий список, совпадение в поиске. */
    val tint: Color = accent.copy(alpha = 0.1f),
)

val LightColors = StColors(
    bg = Color(0xFFF7F5EF),
    ink = Color(0xFF1C1B18),
    muted = Color(0xFF5E5C55),
    faint = Color(0xFF8A877E),
    rule = Color(0xFFE1DED3),
    accent = Color(0xFF1F3FBF),
    pressed = Color(0x0F1C1B18),
    isDark = false,
    onAccent = Color.White,
    tint = Color(0xFFE8EBF7),
)

val DarkColors = StColors(
    bg = Color(0xFF151513),
    ink = Color(0xFFECEAE3),
    muted = Color(0xFFA6A399),
    faint = Color(0xFF6F6C64),
    rule = Color(0xFF2B2A26),
    accent = Color(0xFF9DB4FF),
    pressed = Color(0x14ECEAE3),
    isDark = true,
    onAccent = Color(0xFF10131F),
    tint = Color(0xFF1F2335),
)

/** AMOLED: чистый чёрный — пиксели OLED-экрана выключены. Линии чуть светлее, чтобы не пропасть. */
val AmoledColors = DarkColors.copy(
    bg = Color(0xFF000000),
    rule = Color(0xFF242421),
    pressed = Color(0x1AECEAE3),
    tint = Color(0xFF12151F),
)

/**
 * Цвета обоев (Material You, Android 12+): берём тональные палитры системы.
 * Тоны выбраны так, чтобы сохранить ту же иерархию и контраст, что и в своей палитре.
 */
@RequiresApi(Build.VERSION_CODES.S)
fun dynamicColors(ctx: Context, dark: Boolean, pureBlack: Boolean): StColors {
    fun c(id: Int) = Color(ctx.getColor(id))
    return if (!dark) StColors(
        bg = c(android.R.color.system_neutral1_10),
        ink = c(android.R.color.system_neutral1_900),
        muted = c(android.R.color.system_neutral2_700),
        faint = c(android.R.color.system_neutral2_400),
        rule = c(android.R.color.system_neutral2_100),
        accent = c(android.R.color.system_accent1_600),
        pressed = c(android.R.color.system_neutral1_900).copy(alpha = 0.06f),
        isDark = false,
        onAccent = Color.White,
        tint = c(android.R.color.system_accent1_100),
    ) else StColors(
        bg = if (pureBlack) Color.Black else c(android.R.color.system_neutral1_900),
        ink = c(android.R.color.system_neutral1_50),
        muted = c(android.R.color.system_neutral2_300),
        faint = c(android.R.color.system_neutral2_600),
        rule = if (pureBlack) c(android.R.color.system_neutral1_900) else c(android.R.color.system_neutral1_800),
        accent = c(android.R.color.system_accent1_200),
        pressed = c(android.R.color.system_neutral1_50).copy(alpha = 0.09f),
        isDark = true,
        onAccent = c(android.R.color.system_accent1_800),
        tint = if (pureBlack) c(android.R.color.system_accent1_900).copy(alpha = 0.6f) else c(android.R.color.system_accent1_900),
    )
}

/** Поддерживает ли устройство цвета обоев. */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

object Fonts {
    val serif = FontFamily(
        Font(R.font.serif_regular, FontWeight.Normal),
        Font(R.font.serif_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.serif_semibold, FontWeight.SemiBold),
    )
    val sans = FontFamily(
        Font(R.font.plex_sans_regular, FontWeight.Normal),
        Font(R.font.plex_sans_medium, FontWeight.Medium),
        Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    )
}

/** Табличные цифры: номера строк и страниц стоят ровным столбцом. */
private const val TNUM = "tnum"

/**
 * Два шрифта: Source Serif 4 — задачи, заголовки, номер страницы; IBM Plex Sans — всё остальное.
 * Никаких капителей с разрядкой; мельче 13 sp ничего нет.
 */
@Immutable
class StType {
    /** Текст задачи. */
    val task = TextStyle(fontFamily = Fonts.serif, fontSize = 19.sp, lineHeight = 28.sp)
    /** Ввод новой задачи. */
    val input = TextStyle(fontFamily = Fonts.serif, fontSize = 19.sp, lineHeight = 28.sp)
    /** Крупный номер страницы. */
    val pageNo = TextStyle(fontFamily = Fonts.serif, fontSize = 56.sp, lineHeight = 60.sp, fontFeatureSettings = TNUM)
    /** Номер страницы в шапке. */
    val pageNoCompact = TextStyle(fontFamily = Fonts.serif, fontSize = 48.sp, lineHeight = 52.sp, fontFeatureSettings = TNUM)
    val pageOf = TextStyle(fontFamily = Fonts.sans, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    /** Номера строк, даты, пояснения под строкой. */
    val meta = TextStyle(fontFamily = Fonts.sans, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM)
    /** Счётчики под номером страницы. */
    val stats = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    val value = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM)
    /** Заголовок раздела настроек — обычным регистром. */
    val label = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val action = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
    val actionLarge = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
    val hint = TextStyle(fontFamily = Fonts.sans, fontSize = 15.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Fonts.sans, fontSize = 16.sp, lineHeight = 24.sp)
    val title = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp)
    val headline = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp)
    /** Вопрос в нижнем листе. */
    val sheetTitle = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    /** Вводный абзац под заголовком экрана. */
    val lead = TextStyle(fontFamily = Fonts.sans, fontSize = 16.sp, lineHeight = 24.sp)
    /** Название пункта настроек или строки списка. */
    val setting = TextStyle(fontFamily = Fonts.sans, fontSize = 17.sp, lineHeight = 24.sp)
    /** Короткий связный текст («О приложении»). */
    val prose = TextStyle(fontFamily = Fonts.serif, fontSize = 17.sp, lineHeight = 26.sp)
    /** Шестизначный код синхронизации. */
    val code = TextStyle(fontFamily = Fonts.serif, fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = 0.04.em, fontFeatureSettings = TNUM)
}

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { StType() }

/** Доступ к токенам: `Ui.c.ink`, `Ui.t.task`. */
object Ui {
    val c: StColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val t: StType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

private val type = StType()

/** Тёмная ли тема с учётом выбора пользователя. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun StTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    pureBlack: Boolean = false,
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    val ctx = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicColors(ctx, dark, pureBlack)
        dark && pureBlack -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides type,
        LocalIndication provides PressTint(colors.pressed),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.25f),
        ),
        content = content,
    )
}

/** Отклик на нажатие: ровная лёгкая заливка, без «волн» Material. */
private class PressTint(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressTintNode(interactionSource, color)

    override fun equals(other: Any?) = other is PressTint && other.color == color
    override fun hashCode() = color.hashCode()
}

private class PressTintNode(
    private val source: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                val p = when (i) {
                    is PressInteraction.Press -> true
                    is PressInteraction.Release, is PressInteraction.Cancel -> false
                    else -> pressed
                }
                if (p != pressed) {
                    pressed = p
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (pressed) drawRect(color)
        drawContent()
    }
}
