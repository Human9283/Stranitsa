package app.stranitsa.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
fun Txt(
    text: String, style: TextStyle, color: Color = Ui.c.ink, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, decoration: TextDecoration? = null,
) = BasicText(
    text, modifier, style.copy(color = color, textDecoration = decoration),
    maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
)

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Ui.c.rule))

enum class Glyph { Prev, Next, Plus, Minus, Check, Search, Archive, Sync, Gear, Close, Back }

/** Значки одной линейной гарнитуры (штрих 1,75 на сетке 24), нарисованные вручную. */
@Composable
fun GlyphIcon(g: Glyph, color: Color = Ui.c.ink, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val w = 1.75f * u
        fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), w, StrokeCap.Round)
        when (g) {
            Glyph.Prev -> { l(15f, 6f, 9f, 12f); l(9f, 12f, 15f, 18f) }
            Glyph.Next -> { l(9f, 6f, 15f, 12f); l(15f, 12f, 9f, 18f) }
            Glyph.Back -> { l(19f, 12f, 5f, 12f); l(11f, 6f, 5f, 12f); l(11f, 18f, 5f, 12f) }
            Glyph.Plus -> { l(12f, 5f, 12f, 19f); l(5f, 12f, 19f, 12f) }
            Glyph.Minus -> l(5f, 12f, 19f, 12f)
            Glyph.Check -> { l(5f, 12.5f, 9.5f, 17f); l(9.5f, 17f, 19f, 7.5f) }
            Glyph.Close -> { l(6f, 6f, 18f, 18f); l(18f, 6f, 6f, 18f) }
            Glyph.Search -> { drawCircle(color, 7.5f * u, Offset(10.5f * u, 10.5f * u), style = Stroke(w)); l(16f, 16f, 21f, 21f) }
            Glyph.Archive -> { l(4f, 7f, 20f, 7f); l(6f, 7f, 6f, 19f); l(6f, 19f, 18f, 19f); l(18f, 19f, 18f, 7f); l(9f, 11f, 15f, 11f) }
            Glyph.Sync -> {
                drawArc(color, 200f, 140f, false, Offset(4f * u, 4f * u), androidx.compose.ui.geometry.Size(16f * u, 16f * u), style = Stroke(w, cap = StrokeCap.Round))
                drawArc(color, 20f, 140f, false, Offset(4f * u, 4f * u), androidx.compose.ui.geometry.Size(16f * u, 16f * u), style = Stroke(w, cap = StrokeCap.Round))
                l(18f, 3.5f, 18.5f, 7.5f); l(18.5f, 7.5f, 14.5f, 8f); l(6f, 20.5f, 5.5f, 16.5f); l(5.5f, 16.5f, 9.5f, 16f)
            }
            Glyph.Gear -> {
                drawCircle(color, 3f * u, Offset(12f * u, 12f * u), style = Stroke(w))
                l(12f, 3f, 12f, 6f); l(12f, 18f, 12f, 21f); l(3f, 12f, 6f, 12f); l(18f, 12f, 21f, 12f)
                l(5.6f, 5.6f, 7.7f, 7.7f); l(16.3f, 16.3f, 18.4f, 18.4f); l(5.6f, 18.4f, 7.7f, 16.3f); l(16.3f, 7.7f, 18.4f, 5.6f)
            }
        }
    }
}

/** Всплывающая подсказка: название и клавиши («Сделано · Ctrl+Enter»). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String?, content: @Composable () -> Unit) {
    if (text == null) { content(); return }
    val c = Ui.c
    TooltipArea(
        tooltip = {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(c.ink).padding(horizontal = 8.dp, vertical = 5.dp)
            ) { Txt(text, Type.meta, c.bg) }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    ) { content() }
}

enum class Kind { Filled, Accent, Plain, Outline }

/** Кнопка: заполненная (одна главная на экране), акцентная текстовая, обычная, с рамкой. */
@Composable
fun Btn(
    label: String, onClick: () -> Unit, kind: Kind = Kind.Plain, tip: String? = null,
    enabled: Boolean = true, modifier: Modifier = Modifier, height: Dp = 30.dp,
) {
    val c = Ui.c
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        kind == Kind.Filled && enabled -> if (hovered) c.accent.copy(alpha = 0.88f) else c.accent
        hovered && enabled -> c.hover
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.faint
        kind == Kind.Filled -> c.onAccent
        kind == Kind.Accent -> c.accent
        else -> c.ink
    }
    Tip(tip) {
        Box(
            modifier
                .heightIn(min = height)
                .then(if (focused) Modifier.border(2.dp, c.accent, shape) else Modifier)
                .clip(shape)
                .background(bg)
                .then(if (kind == Kind.Outline && !focused) Modifier.border(1.dp, c.rule, shape) else Modifier)
                .hoverable(src)
                .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(label, Type.action.copy(fontSize = if (height > 30.dp) Type.ui.fontSize else Type.action.fontSize), fg, maxLines = 1) }
    }
}

@Composable
fun IconBtn(g: Glyph, tip: String, enabled: Boolean = true, size: Dp = 32.dp, onClick: () -> Unit) {
    val c = Ui.c
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Tip(tip) {
        Box(
            Modifier
                .size(size)
                .then(if (focused) Modifier.border(2.dp, c.accent, shape) else Modifier)
                .clip(shape)
                .background(if (hovered && enabled) c.hover else Color.Transparent)
                .hoverable(src)
                .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = tip },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(g, if (enabled) c.ink else c.faint.copy(alpha = 0.6f)) }
    }
}

/** Переключатель 36×20. */
@Composable
fun Switch(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Ui.c
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = label }
            .size(width = 36.dp, height = 20.dp)
            .clip(shape)
            .background(if (checked) c.accent else c.rule)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, c.muted, shape)),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = if (checked) 3.dp else 4.dp)
                .size(if (checked) 14.dp else 12.dp)
                .clip(CircleShape)
                .background(if (checked) c.onAccent else c.muted)
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Ui.c
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.border(1.dp, c.rule, shape).clip(shape)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .height(28.dp)
                    .background(if (on) c.tint else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, Type.action, if (on) c.accent else c.muted, maxLines = 1) }
        }
    }
}

@Composable
fun KeyCap(text: String) {
    val c = Ui.c
    Box(Modifier.border(1.dp, c.rule, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Txt(text, Type.meta.copy(fontSize = Type.meta.fontSize * 0.92f), c.muted, maxLines = 1)
    }
}

/** Строка, подсвечивающаяся под мышью. */
@Composable
fun HoverRow(
    onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier,
    content: @Composable (hovered: Boolean) -> Unit,
) {
    val c = Ui.c
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(when { selected -> c.tint; hovered -> c.hover; else -> Color.Transparent })
            .hoverable(src)
            .clickable(src, null, onClick = onClick)
    ) { content(hovered) }
}

@Composable
fun Spacer(w: Dp = 0.dp, h: Dp = 0.dp) = Box(Modifier.width(w).height(h))
