package app.stranitsa.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.stranitsa.core.Entry
import app.stranitsa.core.Status
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private fun dayKey(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

@Composable
fun Sidebar(st: AppState) {
    val c = Ui.c
    val s = st.snap
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.width(232.dp).fillMaxHeight().background(c.side).padding(horizontal = 8.dp, vertical = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(22.dp)
            Spacer(w = 10.dp)
            Txt(S.appName, Type.title.copy(fontSize = Type.ui.fontSize * 1.15f), c.ink)
        }
        Txt(S.lists, Type.label, c.muted, Modifier.padding(start = 10.dp, bottom = 6.dp, top = 4.dp))
        s.notebooks.forEach { nb ->
            val cur = nb.id == s.currentId
            HoverRow({ st.switchList(nb.id) }, selected = cur, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(nb.name.ifBlank { S.defaultList }, Type.ui.copy(fontWeight = if (cur) FontWeight.Medium else FontWeight.Normal),
                        if (cur) c.accent else c.ink, Modifier.weight(1f), maxLines = 1)
                    Txt(nb.activeTotal.toString(), Type.meta, if (cur) c.accent else c.muted)
                }
            }
        }
        if (creating) {
            InlineField("", S.newList, onDone = { if (it.isNotBlank()) st.apply { core, n -> core.addList(it, n) }; creating = false; st.view = View.Page }, onCancel = { creating = false }, st = st)
        } else {
            HoverRow({ creating = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Plus, c.muted, 16.dp); Spacer(w = 8.dp); Txt(S.newList, Type.ui, c.muted)
                }
            }
        }
        Box(Modifier.weight(1f))
        st.update?.let { u ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Txt(S.updateAvailable(u.version), Type.ui, c.ink)
                Row(Modifier.padding(top = 4.dp)) {
                    Btn(S.download, { Updates.open(u.assetUrl ?: u.pageUrl) }, Kind.Filled)
                    Btn(S.later, { st.update = null })
                }
            }
        }
        val dismissed = s.dismissedForReview.size
        SideItem(Glyph.Archive, S.dismissed, if (dismissed > 0) dismissed.toString() else null, st.view == View.Dismissed) { st.view = View.Dismissed }
        SideItem(Glyph.Sync, S.sync, null, false) { st.dialog = Dialog.Sync }
        SideItem(Glyph.Gear, S.settings, null, false) { st.dialog = Dialog.Settings }
    }
}

@Composable
private fun SideItem(g: Glyph, label: String, count: String?, on: Boolean, onClick: () -> Unit) {
    val c = Ui.c
    HoverRow(onClick, selected = on, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(g, if (on) c.accent else c.ink, 16.dp); Spacer(w = 8.dp)
            Txt(label, Type.ui, if (on) c.accent else c.ink, Modifier.weight(1f))
            if (count != null) Txt(count, Type.meta, c.muted)
        }
    }
}

/** Однострочное поле в строке (новый список, переименование). */
@Composable
fun InlineField(initial: String, placeholder: String, onDone: (String) -> Unit, onCancel: () -> Unit, st: AppState) {
    val c = Ui.c
    var v by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Box(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
        if (v.text.isEmpty()) Txt(placeholder, Type.ui, c.faint)
        BasicTextField(
            v, { v = it },
            Modifier.fillMaxWidth().focusRequester(fr)
                .onFocusChanged { st.textFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) { Key.Enter -> { onDone(v.text); true }; Key.Escape -> { onCancel(); true }; else -> false }
                },
            textStyle = Type.ui.copy(color = c.ink), cursorBrush = SolidColor(c.accent), singleLine = true,
        )
    }
}

@Composable
fun MainPane(st: AppState) {
    val c = Ui.c
    val s = st.snap
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Header(st)
        when (st.view) {
            View.Page -> PageView(st)
            View.Search -> SearchView(st)
            View.Dismissed -> DismissedView(st)
            View.Review -> ReviewView(st)
        }
    }
}

@Composable
private fun Header(st: AppState) {
    val c = Ui.c
    val s = st.snap
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row {
                Txt(pad2(st.page + 1), Type.pageNo, if (st.onCurrent) c.ink else c.muted, Modifier.alignByBaseline())
                Spacer(w = 6.dp)
                Txt(S.pageOf(pad2(s.lastPage + 1)), Type.pageOf, c.muted, Modifier.alignByBaseline())
            }
            Txt(S.stats(s.activeTotal, st.doneToday()), Type.meta.copy(fontSize = Type.ui.fontSize * 0.93f), c.muted)
        }
        SearchField(st)
    }
}

@Composable
private fun SearchField(st: AppState) {
    val c = Ui.c
    val fr = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(st.focusSearch) { if (st.focusSearch > 0) fr.requestFocus() }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.width(280.dp).height(32.dp).clip(shape).background(c.field)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.rule, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Search, c.muted, 16.dp); Spacer(w = 8.dp)
        Box(Modifier.weight(1f)) {
            if (st.query.isEmpty()) Txt(S.search, Type.ui, c.faint)
            BasicTextField(
                st.query,
                { st.query = it.replace("\n", " "); st.view = if (it.isBlank()) View.Page else View.Search },
                Modifier.fillMaxWidth().focusRequester(fr)
                    .onFocusChanged { focused = it.isFocused; st.textFocused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Escape -> { st.query = ""; st.view = View.Page; fm.clearFocus(); true }
                            Key.DirectionDown -> { st.searchIndex++; true }
                            Key.DirectionUp -> { st.searchIndex = (st.searchIndex - 1).coerceAtLeast(0); true }
                            Key.Enter -> { st.searchHits().getOrNull(st.searchIndex)?.let { st.query = ""; st.open(it.entry.id); fm.clearFocus() }; true }
                            else -> false
                        }
                    },
                textStyle = Type.ui.copy(color = c.ink), cursorBrush = SolidColor(c.accent), singleLine = true,
            )
        }
        if (!focused && st.query.isEmpty()) KeyCap("Ctrl+F")
    }
}

@Composable
private fun ColumnScope.PageView(st: AppState) {
    val c = Ui.c
    val s = st.snap
    val currentActive = st.isPageActive(s.currentPage)
    val hint = when {
        !st.onCurrent -> S.hintViewing(pad2(st.page + 1), pad2(s.currentPage + 1))
        !currentActive && s.entries.isNotEmpty() -> S.hintClosed
        !s.settings.showHints || s.entries.isEmpty() -> null
        !s.actedOnVisit -> S.hintFirst
        else -> S.hintCircle
    }
    if (hint != null) Txt(hint, Type.ui, c.ink, Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp))
    if (st.breakDays > 0) {
        Row(Modifier.padding(start = 28.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(S.breakBanner(st.breakDays), Type.ui, c.ink, Modifier.weight(1f, fill = false))
            Spacer(w = 12.dp)
            Btn(S.readAll, { st.view = View.Review; st.breakDays = 0 }, Kind.Filled)
            Btn(S.later, { st.breakDays = 0 })
        }
    }
    Hairline()
    val entries = st.pageEntries()
    val days = remember(s.entries) {
        var prev = Long.MIN_VALUE
        s.entries.mapIndexedNotNull { i, e -> val d = dayKey(e.createdAt); (if (d != prev) i else null).also { prev = d } }.toSet()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(st.page) { listState.scrollToItem(0) }
    LaunchedEffect(st.selectedId) {
        val i = entries.indexOfFirst { it.value.id == st.selectedId }
        if (i >= 0) {
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == i } || vis.lastOrNull()?.index == i) listState.animateScrollToItem((i - 3).coerceAtLeast(0))
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (s.entries.isEmpty()) {
            Column(Modifier.padding(28.dp).widthIn(max = 640.dp)) {
                Txt(S.firstTask, Type.title)
                Spacer(h = 8.dp)
                S.rules.take(4).forEachIndexed { i, r -> Row(Modifier.padding(vertical = 4.dp)) { Txt("${i + 1}", Type.meta, c.muted, Modifier.width(22.dp)); Txt(r, Type.body, c.ink) } }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp), state = listState) {
            items(entries, key = { it.value.id }) { (idx, e) ->
                Column(Modifier.widthIn(max = 820.dp).padding(start = 18.dp, end = 18.dp)) {
                    if (idx in days) Txt(dateFmt.format(Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault())), Type.label, c.muted, Modifier.padding(start = 44.dp, top = 10.dp, bottom = 4.dp))
                    TaskRow(st, e, idx + 1)
                }
            }
            item { Spacer(h = 16.dp) }
        }
    }
    UndoBar(st)
    NewTaskField(st)
    Footer(st)
}

@Composable
private fun TaskRow(st: AppState, e: Entry, line: Int) {
    val c = Ui.c
    val active = e.status == Status.ACTIVE
    val selected = st.selectedId == e.id
    val editing = st.editingId == e.id
    val struck = e.status == Status.DONE || e.status == Status.REENTERED || e.status == Status.CANCELLED
    val fm = LocalFocusManager.current
    HoverRow({ fm.clearFocus(); if (active) st.selectedId = if (selected) null else e.id }, selected = selected || editing, modifier = Modifier.fillMaxWidth()) { hovered ->
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Номер и текст — по одной базовой линии, как строки в тетради.
            Txt(line.toString(), if (selected) Type.meta.copy(fontWeight = FontWeight.SemiBold) else Type.meta,
                if (selected) c.accent else c.muted, Modifier.width(34.dp).padding(end = 12.dp).alignBy(LastBaseline), maxLines = 1)
            if (editing) {
                EditField(st, e)
            } else {
                Txt(
                    e.text,
                    if (e.status == Status.DISMISSED) Type.task.copy(fontStyle = FontStyle.Italic) else Type.task,
                    if (active) c.ink else c.muted, Modifier.weight(1f).padding(vertical = 7.dp).alignBy(LastBaseline),
                    decoration = if (struck) TextDecoration.LineThrough else null,
                )
                when {
                    active && selected -> Row {
                        Btn(S.done, { st.done(e) }, Kind.Filled, "${S.done} · Ctrl+Enter")
                        Btn(S.notFinished, { st.reenter(e) }, Kind.Accent, "${S.notFinished} · Ctrl+→")
                        Btn(S.edit, { st.selectedId = e.id; st.startEdit() }, tip = "${S.edit} · F2")
                    }
                    active && hovered -> Row {
                        Btn(S.done, { st.done(e) }, Kind.Accent, "${S.done} · Ctrl+Enter")
                        Btn(S.notFinished, { st.reenter(e) }, Kind.Accent, "${S.notFinished} · Ctrl+→")
                    }
                    e.status == Status.REENTERED -> Txt(S.toEnd, Type.meta, c.muted)
                    active && e.reentries > 0 -> Txt("×${e.reentries}", Type.meta, c.muted)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EditField(st: AppState, e: Entry) {
    val c = Ui.c
    var v by remember(e.id) { mutableStateOf(TextFieldValue(e.text, TextRange(e.text.length))) }
    val fr = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) { fr.requestFocus() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { st.textFocused = false } }
    BasicTextField(
        v, { v = it },
        Modifier.weight(1f).padding(vertical = 7.dp).focusRequester(fr)
            .onFocusChanged { st.textFocused = it.isFocused }
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) false
                else when (k.key) { Key.Enter -> { st.saveEdit(e.id, v.text); true }; Key.Escape -> { st.editingId = null; true }; else -> false }
            },
        textStyle = Type.task.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
    )
    Btn(S.save, { st.saveEdit(e.id, v.text) }, Kind.Filled, "Enter", enabled = v.text.isNotBlank())
    Btn(S.cancel, { st.editingId = null }, tip = "Esc")
    Btn(S.delete, { st.delete(e) }, tip = "Delete")
}

@Composable
private fun UndoBar(st: AppState) {
    val c = Ui.c
    val b = st.bar ?: return
    LaunchedEffect(b.id) { delay(6000); if (st.bar?.id == b.id) st.bar = null }
    Hairline()
    Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 28.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(b.text, Type.meta.copy(fontSize = Type.ui.fontSize * 0.93f), c.muted, Modifier.weight(1f), maxLines = 1)
        if (b.undoable && st.snap.canUndo) Btn(S.undo, { st.undo() }, Kind.Accent, "Ctrl+Z")
    }
}

@Composable
private fun NewTaskField(st: AppState) {
    val c = Ui.c
    var text by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(st.focusNewTask) { if (st.focusNewTask > 0) fr.requestFocus() }
    Hairline()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(indication = null, interactionSource = null) { fr.requestFocus() }
            .pointerHoverIcon(PointerIcon.Text).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Plus, c.muted, 18.dp); Spacer(w = 14.dp)
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Txt(if (st.snap.entries.isEmpty()) S.firstTask else S.newTask, Type.task, c.faint)
            BasicTextField(
                text, { text = it.replace("\n", " ") },
                Modifier.fillMaxWidth().focusRequester(fr)
                    .onFocusChanged { st.textFocused = it.isFocused }
                    .onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Enter) { st.add(text); text = ""; true }
                        // Esc — выйти из поля: дальше снова работают стрелки и клавиши списка.
                        else if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { text = ""; fm.clearFocus(); true }
                        else false
                    },
                textStyle = Type.task.copy(color = c.ink), cursorBrush = SolidColor(c.accent), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { st.add(text); text = "" }),
            )
        }
        if (text.isNotBlank()) Btn(S.newTask, { st.add(text); text = "" }, Kind.Accent, "Enter")
    }
}

@Composable
private fun Footer(st: AppState) {
    val c = Ui.c
    val s = st.snap
    val currentActive = st.isPageActive(s.currentPage)
    val passed = st.onCurrent && currentActive && s.actedOnVisit && !s.dismissApplies
    val (label, right) = when {
        !st.onCurrent -> S.nextBack(pad2(s.currentPage + 1)) to ""
        s.activeTotal == 0 -> S.allClosed to ""
        s.nextPage == s.currentPage && currentActive -> S.noOther to ""
        !currentActive -> S.nextPlain to pad2(s.nextPage + 1)
        s.dismissApplies -> S.nextNothing to pad2(s.nextPage + 1)
        else -> S.nextPassed to pad2(s.nextPage + 1)
    }
    val enabled = !st.onCurrent || (s.activeTotal > 0 && s.nextPage != s.currentPage)
    Hairline()
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 22.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Glyph.Prev, "${S.prevPage} · PgUp", st.page > 0) { st.viewPage = st.page - 1 }
        IconBtn(Glyph.Next, "${S.nextPage} · PgDn", st.page < s.lastPage) { st.viewPage = st.page + 1 }
        Box(Modifier.weight(1f))
        Btn(
            if (right.isEmpty()) label else "$label   $right ›", { st.next() },
            if (passed || !st.onCurrent) Kind.Filled else Kind.Plain, "Ctrl+Shift+→", enabled, height = 32.dp,
        )
    }
}

@Composable
private fun ColumnScope.SearchView(st: AppState) {
    val c = Ui.c
    val hits = st.searchHits()
    val closed = remember(st.query, st.snap) { if (st.query.isBlank()) 0 else st.core.search(st.query, true).size - st.core.search(st.query, false).size }
    Hairline()
    Row(Modifier.padding(start = 28.dp, end = 20.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(if (hits.isEmpty()) S.nothingFound else S.found(hits.size), Type.meta, c.muted)
        if (closed > 0 || st.includeClosed) {
            Txt(" · ", Type.meta, c.muted)
            Txt(if (st.includeClosed) S.hideClosed else S.showClosed(closed), Type.meta, c.accent,
                Modifier.clickable { st.includeClosed = !st.includeClosed }.pointerHoverIcon(PointerIcon.Hand))
        }
        Box(Modifier.weight(1f)); Txt(S.enterOpens, Type.meta, c.faint)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(hits.withIndex().toList(), key = { it.value.entry.id }) { (i, h) ->
            val e = h.entry
            HoverRow({ st.query = ""; st.open(e.id) }, selected = i == st.searchIndex.coerceAtMost(hits.lastIndex),
                modifier = Modifier.widthIn(max = 820.dp).padding(horizontal = 18.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicText(
                        buildAnnotatedString {
                            var pos = 0
                            for (r in h.ranges.sortedBy { it.start }) {
                                if (r.start < pos || r.end > e.text.length) continue
                                append(e.text.substring(pos, r.start))
                                withStyle(SpanStyle(background = c.tint, textDecoration = TextDecoration.Underline)) { append(e.text.substring(r.start, r.end)) }
                                pos = r.end
                            }
                            append(e.text.substring(pos))
                        },
                        Modifier.weight(1f).padding(vertical = 7.dp),
                        Type.task.copy(
                            color = if (e.status == Status.ACTIVE) c.ink else c.muted,
                            textDecoration = if (e.status == Status.ACTIVE || e.status == Status.DISMISSED) null else TextDecoration.LineThrough,
                        ),
                    )
                    Txt(S.where(pad2(h.index / st.snap.pageSize + 1), h.index + 1), Type.meta, c.muted)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DismissedView(st: AppState) {
    val c = Ui.c
    val items = st.snap.dismissedForReview
    var reviving by remember { mutableStateOf<String?>(null) }
    Hairline()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Glyph.Back, S.back + " · Esc") { st.view = View.Page }
                Spacer(w = 6.dp); Txt(S.dismissed, Type.title)
            }
            Txt(S.dismissedLead, Type.body, c.muted, Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 12.dp).widthIn(max = 640.dp))
            if (items.isEmpty()) Txt(S.dismissedEmpty, Type.ui, c.muted, Modifier.padding(start = 28.dp))
        }
        items(items, key = { it.id }) { e ->
            Column(Modifier.widthIn(max = 820.dp).padding(horizontal = 28.dp)) {
                Hairline()
                if (reviving == e.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineField(e.text, e.text, onDone = { if (it.isNotBlank()) st.apply { core, n -> core.revive(e.id, it, n) }; reviving = null }, onCancel = { reviving = null }, st = st)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(e.text, Type.task, c.ink, Modifier.weight(1f).padding(vertical = 8.dp))
                        Btn(S.revive, { reviving = e.id }, Kind.Accent)
                        Btn(S.forget, { st.apply { core, n -> core.forget(e.id, n) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReviewView(st: AppState) {
    val c = Ui.c
    val s = st.snap
    val byPage = remember(s.entries, s.pageSize) {
        s.entries.withIndex().filter { it.value.status == Status.ACTIVE }.groupBy { it.index / s.pageSize }.toSortedMap()
    }
    Hairline()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Glyph.Back, S.back + " · Esc") { st.view = View.Page }
                Spacer(w = 6.dp); Txt(S.reviewTitle, Type.title)
            }
            Txt(S.reviewLead, Type.body, c.muted, Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 8.dp))
        }
        byPage.forEach { (p, rows) ->
            item(key = "p$p") { Txt(S.pageN(pad2(p + 1)), Type.label, c.accent, Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)) }
            items(rows, key = { it.value.id }) { (i, e) ->
                HoverRow({ st.open(e.id) }, modifier = Modifier.widthIn(max = 820.dp).padding(horizontal = 18.dp).fillMaxWidth()) {
                    Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt("${i + 1}", Type.meta, c.muted, Modifier.width(44.dp).padding(end = 12.dp))
                        Txt(e.text, Type.task, c.ink)
                    }
                }
            }
        }
        item { Spacer(h = 24.dp) }
    }
}
