package app.stranitsa.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.stranitsa.StActions
import app.stranitsa.R
import app.stranitsa.Screen
import app.stranitsa.core.Entry
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Номер дня в часовом поясе телефона — для отметки «первая запись дня». */
private fun dayKey(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Индексы строк, где начинается новый день (первая запись дня) — для отметки даты. */
private fun dayStarts(entries: List<Entry>): Set<Int> {
    val out = HashSet<Int>()
    var prev = Long.MIN_VALUE
    entries.forEachIndexed { i, e ->
        val d = dayKey(e.createdAt)
        if (d != prev) out += i
        prev = d
    }
    return out
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Сообщение в строке над вводом: «Сделано · …   Отменить». */
private data class Bar(val text: String, val undoable: Boolean, val id: Long = System.nanoTime())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    state: Snapshot,
    vm: StActions,
    onOpen: (Screen) -> Unit,
    // Только для превью/скриншот-тестов.
    initialSelected: String? = null,
    initialConfirming: Boolean = false,
    /** Открыть страницу этой задачи и выделить её (переход из поиска). */
    jumpTo: String? = null,
    onJumped: () -> Unit = {},
    showHints: Boolean = true,
    /** Имя текущего списка; null — список один, имя не показываем. */
    listName: String? = null,
    /** Дней без открытия приложения (0 — не напоминаем). */
    breakDays: Int = 0,
    onDismissBreak: () -> Unit = {},
    /** Вышла новая версия — предложить скачать. */
    update: app.stranitsa.Release? = null,
    onDismissUpdate: () -> Unit = {},
    /** Прошлый запуск закончился сбоем — предложить отчёт. */
    crashed: Boolean = false,
    onDismissCrash: () -> Unit = {},
) {
    val c = Ui.c
    val t = Ui.t
    val res = LocalContext.current.resources
    val last = state.lastPage
    val current = state.currentPage.coerceIn(0, last)

    // Страница, которую сейчас смотрим. Листание стрелками — только просмотр:
    // рабочая страница и правило «первого прохода» от этого не меняются.
    var viewPage by rememberSaveable { mutableIntStateOf(current) }
    var knownCurrent by rememberSaveable { mutableIntStateOf(current) }
    if (knownCurrent != current) {
        // Рабочая страница сменилась (дальше / отбрасывание / первая задача) — показываем её.
        knownCurrent = current
        viewPage = current
    }
    val page = viewPage.coerceIn(0, last)
    val onCurrent = page == current

    var selectedId by rememberSaveable { mutableStateOf(initialSelected) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(initialConfirming) }
    var menuOpen by remember { mutableStateOf(false) }
    var addFocused by remember { mutableStateOf(false) }
    var shownPage by rememberSaveable { mutableIntStateOf(page) }
    var bar by remember { mutableStateOf<Bar?>(null) }
    var flashId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    var pendingScroll by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(bar) { if (bar != null) { delay(4500); bar = null } }
    // Сбрасываем выбор только при настоящей смене страницы, а не при повороте экрана.
    // Важно: этот эффект объявлен ДО перехода из поиска — эффекты запускаются по порядку,
    // и переход должен выполниться последним, иначе сброс сотрёт его выделение.
    LaunchedEffect(page) {
        if (shownPage != page) {
            shownPage = page
            selectedId = null; editingId = null; confirming = false
            listState.scrollToItem(0)
        }
    }

    // Переход из поиска: показываем страницу задачи и отмечаем строку.
    LaunchedEffect(jumpTo) {
        val id = jumpTo ?: return@LaunchedEffect
        val idx = state.entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val target = idx / state.pageSize
            shownPage = target
            viewPage = target
            val e = state.entries[idx]
            selectedId = if (e.status == Status.ACTIVE) id else null
            flashId = if (e.status == Status.ACTIVE) null else id
            pendingScroll = (idx % state.pageSize - 2).coerceAtLeast(0)
        }
        onJumped()
    }
    // Прокрутка — когда нужная страница уже на экране.
    LaunchedEffect(page, pendingScroll) {
        val to = pendingScroll ?: return@LaunchedEffect
        if (page == shownPage) {
            listState.scrollToItem(to)
            pendingScroll = null
        }
    }
    LaunchedEffect(flashId) { if (flashId != null) { delay(3000); flashId = null } }

    val showConfirm = confirming && onCurrent && state.dismissApplies
    BackHandler(enabled = selectedId != null || editingId != null || showConfirm) {
        selectedId = null; editingId = null; confirming = false
    }

    val dismissedCount = state.dismissedForReview.size
    val menu = listOf(
        MenuItem(stringResource(R.string.menu_lists), listName) { onOpen(Screen.Lists) },
        MenuItem(stringResource(R.string.menu_review)) { onOpen(Screen.Review) },
        MenuItem(stringResource(R.string.menu_sync)) { onOpen(Screen.Sync) },
        MenuItem(stringResource(R.string.menu_dismissed), if (dismissedCount > 0) dismissedCount.toString() else null) { onOpen(Screen.Dismissed) },
        MenuItem(stringResource(R.string.menu_settings)) { onOpen(Screen.Settings) },
        MenuItem(stringResource(R.string.menu_howto)) { onOpen(Screen.About) },
    )

    fun add(text: String) {
        if (text.isBlank()) return
        // Новая строка встанет с индексом entries.size — на эту страницу.
        val target = state.entries.size / state.pageSize
        vm.add(text)
        if (state.entries.isNotEmpty() && target != page) {
            bar = Bar(res.getString(R.string.bar_added, pad2(target + 1)), undoable = true)
        }
    }

    if (state.entries.isEmpty()) {
        FirstRun(menu = menu, menuOpen = menuOpen, onMenu = { menuOpen = it }, onAdd = ::add)
        return
    }

    val entries = state.pageEntries(page)
    val dayStarts = remember(state.entries) { dayStarts(state.entries) }
    // Язык интерфейса, а не JVM: с выбранным для приложения языком они могут различаться.
    val locale = LocalConfiguration.current.locales[0]
    val dateFmt = remember(locale) { DateTimeFormatter.ofPattern("EEE, d MMM", locale) }
    val currentActive = state.isPageActive(current)
    val nextTarget = state.nextPage
    val activeTotal = state.activeTotal

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
    ) {
        // ---------- шапка ----------
        // Крупный номер страницы, поиск и меню. Под ним — одна строка счётчиков и подсказка.
        Column(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    Txt(pad2(page + 1), t.pageNoCompact, if (onCurrent) c.ink else c.muted, Modifier.alignByBaseline())
                    Spacer(Modifier.width(8.dp))
                    Txt(stringResource(R.string.page_of, pad2(last + 1)), t.pageOf, c.muted, Modifier.alignByBaseline())
                }
                SearchButton { onOpen(Screen.Search) }
                Box {
                    MenuButton { menuOpen = true }
                    if (menuOpen) MenuPopup(menu) { menuOpen = false }
                }
            }
            FlowRow(Modifier.padding(end = 16.dp)) {
                val dot = " · "
                if (listName != null) {
                    Txt(
                        listName, t.stats.copy(fontWeight = FontWeight.Medium), c.ink,
                        Modifier.clickable(role = Role.Button) { onOpen(Screen.Lists) },
                    )
                    Txt(dot, t.stats, c.muted)
                }
                Txt(stringResource(R.string.active_count, activeTotal), t.stats, c.muted)
                Txt(dot, t.stats, c.muted)
                Txt(stringResource(R.string.done_today, vm.doneSince(startOfToday())), t.stats, c.muted)
                if (dismissedCount > 0) {
                    Txt(dot, t.stats, c.muted)
                    Txt(
                        stringResource(R.string.review_count, dismissedCount), t.stats, c.accent,
                        Modifier.clickable(role = Role.Button) { onOpen(Screen.Dismissed) },
                    )
                }
            }
            val hint = when {
                !onCurrent -> stringResource(R.string.hint_viewing, pad2(page + 1), pad2(current + 1))
                !currentActive -> stringResource(R.string.hint_closed)
                // При очень крупном шрифте подсказка съедает полэкрана — прячем её (список важнее).
                !showHints || LocalDensity.current.fontScale >= 1.6f -> null
                !state.actedOnVisit -> stringResource(R.string.hint_first_pass)
                else -> stringResource(R.string.hint_circle)
            }
            if (hint != null) Txt(hint, t.hint, c.ink, Modifier.padding(top = 8.dp, end = 16.dp))
        }

        if (crashed) {
            Hairline(Modifier.padding(top = 10.dp))
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp)) {
                Txt(stringResource(R.string.crash_banner), t.hint, c.ink)
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledAction(stringResource(R.string.crash_view), { onOpen(Screen.Report) })
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.break_later), onDismissCrash, color = c.ink)
                }
            }
        }
        if (update != null) {
            val ctx = LocalContext.current
            Hairline(Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.update_available, update.version), t.hint, c.ink, Modifier.weight(1f))
                TextAction(stringResource(R.string.update_get), {
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(update.apkUrl ?: update.pageUrl)))
                    }
                    onDismissUpdate()
                }, color = c.accent)
                TextAction(stringResource(R.string.break_later), onDismissUpdate, color = c.ink)
            }
        }
        // После долгого перерыва Форстер советует сначала прочитать все невыполненные задачи.
        if (breakDays > 0) {
            Hairline(Modifier.padding(top = 10.dp))
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp)) {
                Txt(res.getQuantityString(R.plurals.break_banner, breakDays, breakDays), t.hint, c.ink)
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledAction(stringResource(R.string.break_read), { onOpen(Screen.Review) })
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.break_later), onDismissBreak, color = c.ink)
                }
            }
        }

        // ---------- страница ----------
        Hairline(Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                val globalIndex = page * state.pageSize + i
                TaskRow(
                    e = e,
                    lineNo = globalIndex + 1,
                    dateLabel = if (globalIndex in dayStarts) {
                        dateFmt.format(Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()))
                    } else null,
                    selected = e.id == selectedId,
                    flashed = e.id == flashId,
                    editing = e.id == editingId,
                    preview = showConfirm && e.status == Status.ACTIVE,
                    onTap = { selectedId = if (selectedId == e.id) null else e.id; editingId = null },
                    onDone = {
                        selectedId = null; vm.done(e.id); bar = Bar(res.getString(R.string.bar_done, e.text), true)
                    },
                    onReenter = {
                        selectedId = null; vm.reenter(e.id); bar = Bar(res.getString(R.string.bar_reentered, e.text), true)
                    },
                    onEdit = { editingId = e.id; selectedId = null },
                    onSave = { vm.edit(e.id, it); editingId = null },
                    onCancelEdit = { editingId = null },
                    onDelete = {
                        editingId = null; vm.cancel(e.id); bar = Bar(res.getString(R.string.bar_deleted, e.text), true)
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // ---------- строка сообщения ----------
        bar?.let { b ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(b.text, t.stats, c.muted, Modifier.weight(1f), maxLines = 1)
                if (b.undoable) TextAction(stringResource(R.string.undo), { vm.undo(); bar = null }, color = c.accent)
            }
        }

        // ---------- нижняя зона ----------
        // Ввод новой задачи и навигация по страницам — под большим пальцем.
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            Hairline()
            AddRow(stringResource(R.string.new_task), onAdd = ::add, onFocus = { addFocused = it })
            if (!addFocused) {
                Hairline()
                val passed = onCurrent && currentActive && state.actedOnVisit && !state.dismissApplies
                val (label, right) = when {
                    !onCurrent -> stringResource(R.string.next_back, pad2(current + 1)) to ""
                    activeTotal == 0 -> stringResource(R.string.next_all_closed) to ""
                    nextTarget == current && currentActive -> stringResource(R.string.next_no_other) to ""
                    !currentActive -> stringResource(R.string.next_page_plain) to pad2(nextTarget + 1)
                    state.dismissApplies -> stringResource(R.string.next_nothing) to pad2(nextTarget + 1)
                    else -> stringResource(R.string.next_passed) to pad2(nextTarget + 1)
                }
                val enabled = !onCurrent || (activeTotal > 0 && nextTarget != current)
                // Главная кнопка заполнена, когда на странице поработали: «пройдена — дальше».
                val primary = enabled && (passed || !onCurrent)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(Glyph.Prev, stringResource(R.string.prev_page), enabled = page > 0) { viewPage = page - 1 }
                    IconAction(Glyph.Next, stringResource(R.string.next_page), enabled = page < last) { viewPage = page + 1 }
                    Spacer(Modifier.width(4.dp))
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(if (primary) Modifier.background(c.accent) else Modifier)
                            .clickable(enabled = enabled, role = Role.Button) {
                                when {
                                    !onCurrent -> viewPage = current
                                    state.dismissApplies -> confirming = true
                                    else -> vm.advance(dismiss = false)
                                }
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = when { primary -> c.onAccent; enabled -> c.ink; else -> c.muted }
                        FitText(label, t.actionLarge, fg, Modifier.weight(1f))
                        if (right.isNotEmpty()) {
                            Txt(right, t.stats.copy(fontWeight = FontWeight.Medium), if (primary) c.onAccent else c.muted)
                            GlyphIcon(Glyph.Next, if (primary) c.onAccent else c.muted, 18.dp)
                        }
                    }
                }
            }
        }
    }

    // ---------- «ничего не выделилось» — нижний лист поверх страницы ----------
    if (showConfirm) {
        val n = state.activeCountOnPage(current)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (c.isDark) 0.6f else 0.42f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirming = false }
            )
            ConfirmDismiss(
                n = n,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDismiss = {
                    confirming = false; vm.advance(dismiss = true)
                    bar = Bar(res.getQuantityString(R.plurals.bar_dismissed, n, n), true)
                },
                onKeep = { confirming = false; vm.advance(dismiss = false) },
                onCancel = { confirming = false },
            )
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    e: Entry,
    lineNo: Int,
    dateLabel: String?,
    selected: Boolean,
    flashed: Boolean,
    editing: Boolean,
    preview: Boolean,
    onTap: () -> Unit,
    onDone: () -> Unit,
    onReenter: () -> Unit,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Ui.c
    val t = Ui.t
    val active = e.status == Status.ACTIVE
    val struck = e.status == Status.DONE || e.status == Status.REENTERED || e.status == Status.CANCELLED
    val italic = e.status == Status.DISMISSED || preview
    val textColor = if (active && !preview) c.ink else c.muted
    val meta = when (e.status) {
        // «×2» — задачу уже дважды переносили в конец.
        Status.ACTIVE -> if (e.reentries > 0) "×${e.reentries}" else null
        Status.DONE -> null
        Status.REENTERED -> stringResource(R.string.meta_reentered)
        Status.DISMISSED -> stringResource(R.string.meta_dismissed)
        Status.CANCELLED -> stringResource(R.string.meta_deleted)
    }
    val stateText = when (e.status) {
        Status.ACTIVE -> if (e.reentries > 0) stringResource(R.string.state_in_progress, e.reentries) else stringResource(R.string.state_new)
        Status.DONE -> stringResource(R.string.state_done)
        Status.REENTERED -> stringResource(R.string.state_reentered)
        Status.DISMISSED -> stringResource(R.string.state_dismissed)
        Status.CANCELLED -> stringResource(R.string.state_deleted)
    }
    val sShow = stringResource(R.string.act_show)
    val sDone = stringResource(R.string.act_done)
    val sNotFinished = stringResource(R.string.act_not_finished)
    val sNotFinishedLong = stringResource(R.string.act_not_finished_long)
    val sEdit = stringResource(R.string.act_edit)
    val accent = c.accent
    // Черновик правки живёт в строке, пока она в режиме редактирования.
    var draft by remember(e.id, editing) { mutableStateOf(TextFieldValue(e.text, TextRange(e.text.length))) }

    val row: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected || editing || flashed) c.tint else c.bg)
                .then(
                    if (active && !editing && !preview) Modifier.clickable(onClickLabel = sShow, onClick = onTap)
                    else Modifier
                )
                .semantics(mergeDescendants = !editing) {
                    stateDescription = stateText
                    if (active && !preview && !editing) {
                        customActions = listOf(
                            CustomAccessibilityAction(sDone) { onDone(); true },
                            CustomAccessibilityAction(sNotFinishedLong) { onReenter(); true },
                            CustomAccessibilityAction(sEdit) { onEdit(); true },
                        )
                    }
                }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = if (selected || editing) 4.dp else 10.dp)
        ) {
            // Дата у первой записи дня — как на бумаге у Форстера.
            if (dateLabel != null) {
                Txt(dateLabel, t.meta.copy(fontWeight = FontWeight.Medium), c.muted, Modifier.padding(top = 6.dp, bottom = 6.dp))
            }
            Row {
                Txt(
                    lineNo.toString(),
                    if (selected || editing || flashed) t.meta.copy(fontWeight = FontWeight.SemiBold) else t.meta,
                    if (selected || editing || flashed) c.accent else c.muted,
                    Modifier.width(38.dp).alignByBaseline(),
                )
                if (editing) {
                    EditField(
                        value = draft,
                        onChange = { draft = it },
                        onSave = { onSave(draft.text) },
                        modifier = Modifier.weight(1f).alignByBaseline(),
                    )
                } else {
                    Txt(
                        e.text,
                        if (italic) t.task.copy(fontStyle = FontStyle.Italic) else t.task,
                        textColor,
                        Modifier.weight(1f).alignByBaseline(),
                        decoration = if (struck) TextDecoration.LineThrough else null,
                    )
                }
                if (meta != null && !editing) {
                    Spacer(Modifier.width(12.dp))
                    Txt(meta, t.meta, c.muted, Modifier.alignByBaseline())
                }
            }
            if (selected) {
                // На крупном шрифте кнопки переносятся на вторую строку, а не обрезаются.
                FlowRow(Modifier.padding(start = 26.dp, top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.Center) {
                    FilledAction(sDone, onDone)
                    Spacer(Modifier.width(4.dp))
                    TextAction(sNotFinished, onReenter, color = c.accent)
                    TextAction(sEdit, onEdit)
                }
            }
            if (editing) {
                Row(Modifier.fillMaxWidth().padding(start = 26.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledAction(stringResource(R.string.act_save), { onSave(draft.text) }, enabled = draft.text.isNotBlank())
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.act_cancel), onCancelEdit)
                    Spacer(Modifier.weight(1f))
                    TextAction(stringResource(R.string.act_delete), onDelete, color = c.muted)
                }
            }
        }
    }

    if (active && !editing && !preview) SwipeToAct(onRight = onDone, onLeft = onReenter, content = row)
    else row()
}

@Composable
private fun EditField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    val c = Ui.c
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            // Перевод строки = «сохранить»: так «Готово» работает на любой клавиатуре.
            if (v.text.contains('\n')) {
                onChange(v.copy(text = v.text.replace("\n", "")))
                onSave()
            } else onChange(v)
        },
        modifier = modifier
            .focusRequester(focus)
            .drawBehind {
                val y = size.height + 3.dp.toPx()
                drawLine(c.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        textStyle = Ui.t.task.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
    )
}

/** Свайп вправо — «Сделано», влево — «Не закончил, в конец». */
@Composable
private fun SwipeToAct(onRight: () -> Unit, onLeft: () -> Unit, content: @Composable () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    var armed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        val x = offset.value
        if (x > 0f) {
            Txt(stringResource(R.string.act_done), t.action, c.accent, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
        } else if (x < 0f) {
            Txt(stringResource(R.string.swipe_to_end), t.action, c.ink, Modifier.align(Alignment.CenterEnd).padding(end = 20.dp))
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    fun threshold() = width * 0.33f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val v = offset.value
                                when {
                                    v > threshold() -> {
                                        offset.animateTo(width.toFloat(), tween(140)); right(); offset.snapTo(0f)
                                    }
                                    v < -threshold() -> {
                                        offset.animateTo(-width.toFloat(), tween(140)); left(); offset.snapTo(0f)
                                    }
                                    else -> offset.animateTo(0f, tween(160))
                                }
                                armed = false
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(160)); armed = false } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch {
                            val nv = offset.value + dx
                            offset.snapTo(nv)
                            val nowArmed = abs(nv) > threshold()
                            if (nowArmed != armed) {
                                armed = nowArmed
                                if (nowArmed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                }
        ) { content() }
    }
}

@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val c = Ui.c
    val t = Ui.t
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text); text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(38.dp)) { GlyphIcon(Glyph.Plus, c.muted, 22.dp) }
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (text.isEmpty()) Txt(placeholder, t.input, c.muted)
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    // Перевод строки = «записать». Работает на любой клавиатуре.
                    if (v.contains('\n')) {
                        text = v.replace("\n", " "); submit()
                    } else text = v
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = placeholder },
                textStyle = t.input.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        if (text.isNotBlank()) TextAction(stringResource(R.string.add), ::submit, color = c.accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmDismiss(n: Int, modifier: Modifier, onDismiss: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val res = LocalContext.current.resources
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(c.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.rule))
        Txt(stringResource(R.string.confirm_title), t.sheetTitle, modifier = Modifier.padding(top = 18.dp))
        Txt(res.getQuantityString(R.plurals.confirm_body, n, n), t.hint, c.muted, Modifier.padding(top = 8.dp))
        FlowRow(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.Center) {
            FilledAction(res.getQuantityString(R.plurals.confirm_dismiss_n, n, n), onDismiss)
            Spacer(Modifier.width(4.dp))
            TextAction(stringResource(R.string.confirm_keep), onKeep, color = c.accent)
            TextAction(stringResource(R.string.act_cancel), onCancel)
        }
    }
}

@Composable
private fun FirstRun(menu: List<MenuItem>, menuOpen: Boolean, onMenu: (Boolean) -> Unit, onAdd: (String) -> Unit) {
    val c = Ui.c
    val t = Ui.t
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box {
                MenuButton { onMenu(true) }
                if (menuOpen) MenuPopup(menu) { onMenu(false) }
            }
        }
        Column(Modifier.weight(1f).padding(start = 20.dp, end = 28.dp, top = 24.dp)) {
            Txt(stringResource(R.string.first_title), t.headline)
            Txt(
                stringResource(R.string.first_lead),
                t.lead, c.muted, Modifier.padding(top = 12.dp),
            )
            Hairline(Modifier.padding(top = 30.dp))
            stringArrayResource(R.array.first_steps).forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 13.dp)) {
                    Txt((i + 1).toString(), t.meta, c.muted, Modifier.width(30.dp).alignByBaseline())
                    Txt(s, t.body, c.ink, Modifier.alignByBaseline())
                }
                Hairline()
            }
            Txt(stringResource(R.string.first_note), t.stats, c.muted, Modifier.padding(top = 14.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            Hairline()
            AddRow(stringResource(R.string.first_task), onAdd = onAdd, onFocus = {})
        }
    }
}
