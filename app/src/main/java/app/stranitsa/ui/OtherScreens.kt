package app.stranitsa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.stranitsa.StActions
import app.stranitsa.R
import app.stranitsa.core.Settings
import app.stranitsa.core.Snapshot
import app.stranitsa.core.ThemeMode
import app.stranitsa.core.pageSizeLimits
import android.os.Build
import androidx.compose.foundation.layout.height
import java.time.Instant
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DismissedScreen(state: Snapshot, vm: StActions, onBack: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val items = state.dismissedForReview
    var reviving by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }

    SubScreen(stringResource(R.string.menu_dismissed), onBack, scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Txt(
                    stringResource(R.string.dismissed_lead),
                    t.body, c.muted, Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 22.dp),
                )
                Hairline()
            }
            if (items.isEmpty()) {
                item { Txt(stringResource(R.string.dismissed_empty), t.lead, c.muted, Modifier.padding(20.dp)) }
            }
            items(items, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp)) {
                    if (reviving == e.id) {
                        var draft by remember(e.id) { mutableStateOf(TextFieldValue(e.text, TextRange(e.text.length))) }
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        val accent = c.accent
                        val submit = {
                            if (draft.text.isNotBlank()) { vm.revive(e.id, draft.text); reviving = null }
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { v -> if (v.text.contains('\n')) submit() else draft = v },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 6.dp)
                                .focusRequester(focus)
                                .drawBehind {
                                    val y = size.height + 3.dp.toPx()
                                    drawLine(accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                                },
                            textStyle = t.task.copy(color = c.ink),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                        )
                        Row(Modifier.padding(top = 4.dp)) {
                            TextAction(stringResource(R.string.revive_to_end), submit, color = c.accent, enabled = draft.text.isNotBlank())
                            Spacer(Modifier.width(14.dp))
                            TextAction(stringResource(R.string.act_cancel), { reviving = null })
                        }
                    } else {
                        Txt(e.text, t.task, modifier = Modifier.padding(end = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(
                                e.closedAt?.let {
                                    stringResource(R.string.dismissed_on, fmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())))
                                } ?: "",
                                t.meta, c.muted, Modifier.weight(1f),
                            )
                            TextAction(stringResource(R.string.revive), { reviving = e.id }, color = c.accent)
                            Spacer(Modifier.width(10.dp))
                            TextAction(stringResource(R.string.forget), { vm.forget(e.id) }, color = c.muted)
                        }
                    }
                }
                Hairline()
            }
        }
    }
}

@Composable
fun SettingsScreen(state: Snapshot, settings: Settings, vm: StActions, onBack: () -> Unit, lastAutoSync: Pair<Long, String>? = null) {
    val (minSize, _, maxSize) = remember { pageSizeLimits() }
    val c = Ui.c
    val t = Ui.t
    val ctx = LocalContext.current
    val res = ctx.resources
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            status = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(vm.exportJson()) }
            }.fold({ res.getString(R.string.export_ok) }, { res.getString(R.string.export_fail, it.message ?: "") })
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .onSuccess { pendingImport = it; status = null }
                .onFailure { status = res.getString(R.string.import_read_fail) }
        }
    }

    SubScreen(stringResource(R.string.menu_settings), onBack) {
        SectionLabel(stringResource(R.string.section_page))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(R.string.lines_per_page), t.setting)
                    Txt(stringResource(R.string.lines_hint), t.stats, c.muted)
                }
                StepButton(Glyph.Minus, stringResource(R.string.fewer_lines), state.pageSize > minSize) { vm.setPageSize(state.pageSize - 1) }
                Txt(state.pageSize.toString(), t.value.copy(textAlign = TextAlign.Center), modifier = Modifier.width(44.dp), maxLines = 1)
                StepButton(Glyph.Plus, stringResource(R.string.more_lines), state.pageSize < maxSize) { vm.setPageSize(state.pageSize + 1) }
            }
            Hairline()
            ToggleRow(stringResource(R.string.hints_toggle), null, settings.showHints) { vm.setShowHints(it) }
            Hairline()
        }

        SectionLabel(stringResource(R.string.section_appearance))
        Column(Modifier.padding(horizontal = 20.dp)) {
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            Segmented(
                listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                modes.indexOf(settings.theme),
                { vm.setTheme(modes[it]) },
                Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Hairline()
            ToggleRow(
                stringResource(R.string.pure_black), stringResource(R.string.pure_black_hint),
                settings.pureBlack, enabled = isDark(settings.theme),
            ) { vm.setPureBlack(it) }
            Hairline()
            if (supportsDynamicColor) {
                ToggleRow(
                    stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint),
                    settings.dynamicColor,
                ) { vm.setDynamicColor(it) }
                Hairline()
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Txt(stringResource(R.string.themed_icon_hint), t.stats, c.muted, Modifier.padding(top = 10.dp))
            }
        }

        SectionLabel(stringResource(R.string.section_sync))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Txt(stringResource(R.string.auto_sync), t.setting, modifier = Modifier.padding(top = 6.dp))
            Txt(stringResource(R.string.auto_sync_hint), t.stats, c.muted)
            val steps = listOf(0, 5, 15, 30, 60)
            Segmented(
                listOf(stringResource(R.string.auto_sync_off), "5", "15", "30", "60"),
                steps.indexOf(settings.autoSyncMinutes).coerceAtLeast(0),
                { vm.setAutoSync(steps[it]) },
                Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Txt(
                if (settings.autoSyncMinutes > 0) stringResource(R.string.auto_sync_every, settings.autoSyncMinutes) else stringResource(R.string.auto_sync_minutes),
                t.stats, c.muted,
            )
            if (lastAutoSync != null) {
                val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
                Txt(
                    stringResource(R.string.auto_sync_last, fmt.format(Instant.ofEpochMilli(lastAutoSync.first).atZone(ZoneId.systemDefault())), lastAutoSync.second),
                    t.stats, c.muted, Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            ToggleRow(stringResource(R.string.check_updates), stringResource(R.string.check_updates_hint), settings.checkUpdates) { vm.setCheckUpdates(it) }
            Hairline()
        }

        SectionLabel(stringResource(R.string.section_data))
        Column(Modifier.padding(horizontal = 20.dp)) {
            val fileName = stringResource(R.string.backup_file_name)
            LinkRow(stringResource(R.string.export), stringResource(R.string.export_hint)) {
                exporter.launch(fileName)
            }
            Hairline()
            ToggleRow(
                stringResource(R.string.backup_toggle), stringResource(R.string.backup_hint),
                settings.systemBackup,
            ) { vm.setSystemBackup(it) }
            Hairline()
            LinkRow(stringResource(R.string.import_)) {
                importer.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            if (pendingImport != null) {
                Txt(stringResource(R.string.import_confirm), t.lead, modifier = Modifier.padding(top = 4.dp))
                Row {
                    TextAction(stringResource(R.string.replace), {
                        val text = pendingImport!!
                        pendingImport = null
                        status = runCatching { vm.importJson(text) }
                            .fold({ res.getString(R.string.import_ok) }, { res.getString(R.string.import_bad) })
                    }, color = c.accent)
                    Spacer(Modifier.width(14.dp))
                    TextAction(stringResource(R.string.act_cancel), { pendingImport = null })
                }
            }
            Hairline()
            status?.let { Txt(it, t.meta, c.muted, Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun LinkRow(label: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Txt(label, Ui.t.setting)
            if (sub != null) Txt(sub, Ui.t.stats, Ui.c.muted)
        }
        GlyphIcon(Glyph.Next, Ui.c.muted)
    }
}

/** Кнопка «−»/«+» для числа строк: квадрат 44 dp с тонкой рамкой. */
@Composable
private fun StepButton(glyph: Glyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Ui.c
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(48.dp)
            .padding(2.dp)
            .clip(shape)
            .border(1.5.dp, c.rule, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, if (enabled) c.ink else c.faint, 20.dp) }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val rules = stringArrayResource(R.array.rules)
    SubScreen(stringResource(R.string.menu_howto), onBack) {
        Hairline(Modifier.padding(top = 12.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            rules.forEachIndexed { i, r ->
                Row(Modifier.padding(vertical = 14.dp)) {
                    Txt(pad2(i + 1), t.meta, c.muted, Modifier.width(36.dp).alignByBaseline())
                    Txt(r, t.prose, modifier = Modifier.alignByBaseline())
                }
                Hairline()
            }
            Txt(
                stringResource(R.string.about_credit),
                t.hint, c.muted, Modifier.padding(top = 20.dp),
            )
            Txt(
                stringResource(R.string.about_fonts),
                t.hint, c.muted, Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}
