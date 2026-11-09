package app.stranitsa.ui

import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import app.stranitsa.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color = Ui.c.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    decoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textDecoration = decoration),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Txt(text: AnnotatedString, style: TextStyle, color: Color = Ui.c.ink, modifier: Modifier = Modifier) {
    BasicText(text = text, modifier = modifier, style = style.copy(color = color))
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ui.c.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Текстовая кнопка: только слово, но с нормальной зоной касания (48 dp в высоту). */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ui.c.ink,
    style: TextStyle = Ui.t.action,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, style, if (enabled) color else Ui.c.faint, maxLines = 1)
    }
}

/** Значки одной линейной гарнитуры (штрих 1,75 dp, сетка 24 dp), нарисованные вручную. */
enum class Glyph { Back, Prev, Next, Plus, Minus, Check, More, Search, Menu, Close }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color = Ui.c.ink, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val w = 1.75f * u
        fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.Back -> { l(19f, 12f, 5f, 12f); l(11f, 6f, 5f, 12f); l(11f, 18f, 5f, 12f) }
            Glyph.Prev -> { l(15f, 6f, 9f, 12f); l(9f, 12f, 15f, 18f) }
            Glyph.Next -> { l(9f, 6f, 15f, 12f); l(15f, 12f, 9f, 18f) }
            Glyph.Plus -> { l(12f, 5f, 12f, 19f); l(5f, 12f, 19f, 12f) }
            Glyph.Minus -> l(5f, 12f, 19f, 12f)
            Glyph.Check -> { l(5f, 12.5f, 9.5f, 17f); l(9.5f, 17f, 19f, 7.5f) }
            Glyph.More -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.5f * u, Offset(12f * u, it * u)) }
            Glyph.Search -> {
                drawCircle(color, 7.5f * u, Offset(10.5f * u, 10.5f * u), style = Stroke(w))
                l(16f, 16f, 21f, 21f)
            }
            Glyph.Menu -> { l(4f, 7f, 20f, 7f); l(4f, 12f, 20f, 12f); l(4f, 17f, 20f, 17f) }
            Glyph.Close -> { l(6f, 6f, 18f, 18f); l(18f, 6f, 6f, 18f) }
        }
    }
}

/** Кнопка-значок 48×48 dp с подписью для TalkBack. */
@Composable
fun IconAction(glyph: Glyph, description: String, enabled: Boolean = true, color: Color = Ui.c.ink, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) color else Ui.c.faint.copy(alpha = 0.6f))
    }
}

@Composable
fun MenuButton(onClick: () -> Unit) = IconAction(Glyph.Menu, stringResource(R.string.menu), onClick = onClick)

@Composable
fun SearchButton(onClick: () -> Unit) = IconAction(Glyph.Search, stringResource(R.string.search), onClick = onClick)

/** Главная кнопка экрана: заливка акцентом, скругление 10. На экране такая одна. */
@Composable
fun FilledAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Ui.c
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) c.accent else c.rule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, Ui.t.action, if (enabled) c.onAccent else c.muted, maxLines = 1)
    }
}

class MenuItem(val label: String, val value: String? = null, val onClick: () -> Unit)

@Composable
fun MenuPopup(items: List<MenuItem>, onDismiss: () -> Unit) {
    val c = Ui.c
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(top = 44.dp, end = 12.dp)
                .width(256.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg)
                .border(1.dp, c.rule, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
        ) {
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onDismiss(); item.onClick() }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(item.label, Ui.t.setting, modifier = Modifier.weight(1f))
                    if (item.value != null) Txt(item.value, Ui.t.stats, c.muted)
                }
            }
        }
    }
}

/** Переключатель 52×32: дорожка и бегунок, «включено» — заливка акцентом. */
@Composable
fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val c = Ui.c
    Box(
        Modifier
            .size(width = 60.dp, height = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(shape)
                .background(if (checked) c.accent else c.rule)
                .then(if (checked) Modifier else Modifier.border(2.dp, c.muted, shape)),
        ) {
            val d = if (checked) 24.dp else 16.dp
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = if (checked) 4.dp else 8.dp)
                    .size(d)
                    .clip(CircleShape)
                    .background(if (checked) c.onAccent else c.muted)
            )
        }
    }
}

/** Выбор одного из нескольких: сегменты в одну строку, выбранный — заливкой и рамкой акцента. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ui.c
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().border(1.5.dp, c.rule, shape)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .then(if (on) Modifier.background(c.tint).border(1.5.dp, c.accent, shape) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(label, Ui.t.label.copy(fontSize = 15.sp, textAlign = TextAlign.Center), if (on) c.accent else c.muted, maxLines = 2)
            }
        }
    }
}

/** Строка с переключателем: подпись, пояснение и переключатель. */
@Composable
fun ToggleRow(label: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Txt(label, Ui.t.setting)
            if (hint != null) Txt(hint, Ui.t.stats, Ui.c.muted)
        }
        Switch(checked, onChange, label, enabled)
    }
}

/** Каркас вторичного экрана: стрелка «назад», крупный заголовок, прокручиваемое содержимое. */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = Ui.c
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconAction(Glyph.Back, stringResource(R.string.back), onClick = onBack)
        }
        if (title.isNotEmpty()) {
            Txt(title, Ui.t.title, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (scrollable) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
        } else {
            content()
        }
    }
}

/** Заголовок раздела: обычным регистром, цветом акцента. */
@Composable
fun SectionLabel(text: String) {
    Txt(text, Ui.t.label, Ui.c.accent, Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp))
}

/** Строка-переход: название, пояснение и шеврон. */
@Composable
fun NavRow(label: String, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, Ui.t.setting)
            if (hint != null) Txt(hint, Ui.t.stats, Ui.c.muted)
        }
        GlyphIcon(Glyph.Next, Ui.c.muted)
    }
}

/**
 * Подпись в одну строку: если не помещается (крупный системный шрифт, длинный перевод),
 * шрифт уменьшается ступенями до [minScale] от исходного, а не переносится на вторую строку.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, minScale: Float = 0.75f) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        fun scaled(k: Float) = style.copy(fontSize = style.fontSize * k, lineHeight = style.lineHeight * k)
        fun width(k: Float) = measurer.measure(text, scaled(k), maxLines = 1, softWrap = false).size.width
        // Подбираем размер за один проход, до отрисовки: уменьшаем ступенями по 5 %.
        val k = remember(text, style, maxW) {
            var k = 1f
            while (k > minScale + 0.001f && width(k) > maxW) k -= 0.05f
            k
        }
        // Если и на минимальном размере не помещается — разрешаем вторую строку.
        val fits = remember(text, style, maxW, k) { width(k) <= maxW }
        BasicText(
            text = text,
            style = scaled(k).copy(color = color),
            maxLines = if (fits) 1 else 2,
            softWrap = !fits,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

