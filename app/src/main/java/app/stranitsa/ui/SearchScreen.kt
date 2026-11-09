package app.stranitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.stranitsa.R
import app.stranitsa.core.SearchHit
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status

/** Поиск по всему списку. Нажатие на результат открывает его страницу. */
@Composable
fun SearchScreen(
    state: Snapshot,
    search: (query: String, includeClosed: Boolean) -> List<SearchHit>,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    initialQuery: String = "",
) {
    val c = Ui.c
    val t = Ui.t
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var includeClosed by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val hits = remember(state.entries, query, includeClosed) { search(query, includeClosed) }
    val closedCount = remember(state.entries, query) {
        if (query.isBlank()) 0 else search(query, true).count { it.entry.status != Status.ACTIVE }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    ) {
        Box(Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconAction(Glyph.Back, stringResource(R.string.back), onClick = onBack)
        }

        // Поле поиска — крупный набор, подчёркнутый акцентом.
        val placeholder = stringResource(R.string.search_placeholder)
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            if (query.isEmpty()) Txt(placeholder, t.title.copy(fontWeight = FontWeight.Normal), c.faint)
            BasicTextField(
                value = query,
                onValueChange = { query = it.replace("\n", " ") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = placeholder }
                    .drawBehind {
                        val y = size.height + 6.dp.toPx()
                        drawLine(c.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                    },
                textStyle = t.title.copy(color = c.ink, fontWeight = FontWeight.Normal),
                cursorBrush = SolidColor(c.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val summary = when {
                query.isBlank() -> stringResource(R.string.search_hint)
                hits.isEmpty() -> stringResource(R.string.search_nothing)
                else -> stringResource(R.string.search_count, hits.size)
            }
            Txt(summary, t.stats, c.muted, Modifier.weight(1f))
            if (closedCount > 0) {
                TextAction(
                    if (includeClosed) stringResource(R.string.search_hide_closed)
                    else stringResource(R.string.search_show_closed, closedCount),
                    { includeClosed = !includeClosed },
                    color = c.accent,
                )
            }
        }
        Hairline(Modifier.padding(top = 4.dp))

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(hits, key = { it.entry.id }) { h ->
                val e = h.entry
                val active = e.status == Status.ACTIVE
                val page = h.index / state.pageSize
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEntry(e.id) }
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Txt(
                        buildAnnotatedString {
                            var pos = 0
                            // Позиции от ядра — в единицах UTF-16, как у строк Kotlin; end не включается.
                            val ranges = h.ranges.sortedBy { it.start }
                            for (r in ranges) {
                                if (r.start < pos || r.end > e.text.length) continue
                                append(e.text.substring(pos, r.start))
                                withStyle(SpanStyle(background = c.tint, textDecoration = TextDecoration.Underline)) {
                                    append(e.text.substring(r.start, r.end))
                                }
                                pos = r.end
                            }
                            append(e.text.substring(pos))
                        },
                        t.task.copy(
                            fontStyle = if (e.status == Status.DISMISSED) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (active || e.status == Status.DISMISSED) null else TextDecoration.LineThrough,
                        ),
                        if (active) c.ink else c.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Txt(stringResource(R.string.search_where, pad2(page + 1), h.index + 1), t.stats, c.muted)
                        val status = when (e.status) {
                            Status.ACTIVE -> null
                            Status.DONE -> stringResource(R.string.state_done)
                            Status.REENTERED -> stringResource(R.string.state_reentered)
                            Status.DISMISSED -> stringResource(R.string.state_dismissed)
                            Status.CANCELLED -> stringResource(R.string.state_deleted)
                        }
                        if (status != null) {
                            Spacer(Modifier.width(12.dp))
                            Txt(status, t.stats, c.muted)
                        }
                    }
                }
                Hairline()
            }
        }
    }
}
