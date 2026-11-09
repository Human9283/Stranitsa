package app.stranitsa.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stranitsa.core.ThemeMode
import app.stranitsa.core.pageSizeLimits
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Модальное окно поверх главного: затемнение, Esc закрывает, Enter — главное действие. */
@Composable
fun Modal(onClose: () -> Unit, width: Dp = 460.dp, onEnter: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Ui.c
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.28f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.Escape -> { onClose(); true }
                    Key.Enter -> onEnter?.let { it(); true } ?: false
                    else -> false
                }
            }
            .focusRequester(fr).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(width).heightIn(max = 700.dp).shadow(24.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(c.bg)
                .border(1.dp, c.rule, RoundedCornerShape(10.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 18.dp),
            content = content,
        )
    }
}


@Composable
fun DialogButtons(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }

@Composable
fun ConfirmDialog(st: AppState) {
    val c = Ui.c
    val n = st.snap.pageActiveCount.getOrElse(st.snap.currentPage) { 0 }
    Modal({ st.dialog = Dialog.None }, onEnter = { st.dismissPage() }) {
        Txt(S.confirmTitle, Type.title)
        Spacer(h = 8.dp)
        Txt(S.confirmBody(n, pad2(st.snap.currentPage + 1)), Type.body, c.muted)
        DialogButtons {
            Btn(S.cancel, { st.dialog = Dialog.None }, Kind.Outline, "Esc", height = 32.dp)
            Btn(S.keep, { st.keepPage() }, Kind.Outline, height = 32.dp)
            Btn(S.dismissN(n), { st.dismissPage() }, Kind.Filled, "Enter", height = 32.dp)
        }
    }
}

@Composable
private fun SettingRow(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val c = Ui.c
    Hairline()
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(label, Type.ui)
            if (hint != null) Txt(hint, Type.meta, c.muted)
        }
        control()
    }
}

@Composable
fun SettingsDialog(st: AppState, dataFile: File, window: Frame?) {
    val c = Ui.c
    val s = st.snap
    val set = s.settings
    val (minSize, _, maxSize) = remember { pageSizeLimits() }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    Modal({ st.dialog = Dialog.None }, width = 540.dp) {
        Txt(S.settings, Type.title)
        Spacer(h = 12.dp)
        // Строки настроек прокручиваются: окно может быть невысоким, а кнопка «Готово» остаётся на виду.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        SettingRow(S.linesPerPage, S.linesHint) {
            Btn("−", { st.apply { core, _ -> core.setPageSize(s.pageSize - 1) } }, Kind.Outline, enabled = s.pageSize > minSize)
            Txt(s.pageSize.toString(), Type.ui, c.ink, Modifier.width(36.dp).padding(horizontal = 8.dp))
            Btn("+", { st.apply { core, _ -> core.setPageSize(s.pageSize + 1) } }, Kind.Outline, enabled = s.pageSize < maxSize)
        }
        SettingRow(S.hints) { Switch(set.showHints, S.hints) { v -> st.apply { core, _ -> core.setSettings(set.copy(showHints = v)) } } }
        SettingRow(S.theme) {
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            Segmented(listOf(S.themeSystem, S.themeLight, S.themeDark), modes.indexOf(set.theme)) { i ->
                st.apply { core, _ -> core.setSettings(set.copy(theme = modes[i])) }
            }
        }
        Hairline()
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Txt(S.autoSync, Type.ui)
            Txt(S.autoSyncHint, Type.meta, c.muted, Modifier.padding(top = 2.dp, bottom = 8.dp))
            val steps = listOf(0, 5, 15, 30, 60)
            Segmented(listOf(S.off) + steps.drop(1).map { S.minutes(it) }, steps.indexOf(set.autoSyncMinutes).coerceAtLeast(0)) { i ->
                st.apply { core, _ -> core.setSettings(set.copy(autoSyncMinutes = steps[i])) }
            }
        }
        st.autoSync.lastSync?.let { (t, who) ->
            Txt(S.lastSync(java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())), who), Type.meta, c.muted, Modifier.padding(bottom = 6.dp))
        }
        SettingRow(S.checkUpdates, S.checkUpdatesHint) { Switch(set.checkUpdates, S.checkUpdates) { v -> st.apply { core, _ -> core.setSettings(set.copy(checkUpdates = v)) } } }
        run {
            val steps = listOf(30, 90, 180, 365)
            var pick by remember { mutableStateOf(1) }
            var confirm by remember { mutableStateOf(false) }
            var done by remember { mutableStateOf<Int?>(null) }
            val count = remember(pick, s.entries, done) { st.core.cleanupPreview(steps[pick], System.currentTimeMillis()) }
            Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Txt(S.cleanup, Type.ui)
                Txt(S.cleanupHint, Type.meta, c.muted, Modifier.padding(top = 2.dp, bottom = 8.dp))
                Segmented(steps.map { S.days(it) }, pick) { pick = it; confirm = false; done = null }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        when { confirm -> S.cleanupConfirm(count); done != null -> S.cleanupDone(done!!); else -> S.cleanupCount(count) },
                        Type.meta, if (confirm) c.ink else c.muted, Modifier.weight(1f),
                    )
                    when {
                        confirm -> { Btn(S.remove, { st.apply { core, n -> core.cleanup(steps[pick], n) }; done = count; confirm = false }, Kind.Filled); Btn(S.cancel, { confirm = false }) }
                        done != null -> Btn(S.undo, { st.undo(); done = null }, Kind.Accent, "Ctrl+Z")
                        else -> Btn(S.remove, { confirm = true }, Kind.Accent, enabled = count > 0)
                    }
                }
            }
        }
        SettingRow(S.pureBlack) { Switch(set.pureBlack, S.pureBlack) { v -> st.apply { core, _ -> core.setSettings(set.copy(pureBlack = v)) } } }
        SettingRow(S.dataFile, dataFile.path) {
            Btn(S.openFolder, { runCatching { Desktop.getDesktop().open(dataFile.parentFile) } }, Kind.Outline)
        }
        SettingRow(S.exportCopy) {
            Btn(S.save + "…", {
                val d = FileDialog(window, S.exportCopy, FileDialog.SAVE).apply { file = "stranitsa-backup.json"; isVisible = true }
                if (d.file != null) status = runCatching { File(d.directory, d.file).writeText(st.core.exportJson()) }
                    .fold({ S.exportOk }, { it.message ?: "" })
            }, Kind.Outline, "Ctrl+Shift+S")
        }
        SettingRow(S.importCopy) {
            Btn(S.importCopy.trimEnd('…').substringBefore(' ') + "…", {
                val d = FileDialog(window, S.importCopy, FileDialog.LOAD).apply { isVisible = true }
                if (d.file != null) pendingImport = runCatching { File(d.directory, d.file).readText() }.getOrNull()
            }, Kind.Outline)
        }
        if (pendingImport != null) {
            Txt(S.importConfirm, Type.ui, c.ink, Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 6.dp)) {
                Btn(S.replace, {
                    val t = pendingImport!!; pendingImport = null
                    status = runCatching { st.apply { core, n -> core.importJson(t, n) } }.fold({ S.importOk }, { S.importBad })
                }, Kind.Filled)
                Btn(S.cancel, { pendingImport = null })
            }
        }
        status?.let { Txt(it, Type.meta, c.muted, Modifier.padding(top = 8.dp)) }
        }
        DialogButtons { Btn(S.ok, { st.dialog = Dialog.None }, Kind.Filled, "Esc", height = 32.dp) }
    }
}

@Composable
fun KeysDialog(st: AppState) {
    val c = Ui.c
    Modal({ st.dialog = Dialog.None }, width = 500.dp, onEnter = { st.dialog = Dialog.None }) {
        Txt(S.keys, Type.title)
        Spacer(h = 10.dp)
        S.keyRows.forEach { (what, keys) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(what, Type.ui, c.ink, Modifier.weight(1f)); KeyCap(keys)
            }
        }
        DialogButtons { Btn(S.close, { st.dialog = Dialog.None }, Kind.Filled, "Esc", height = 32.dp) }
    }
}

@Composable
fun HowToDialog(st: AppState) {
    val c = Ui.c
    Modal({ st.dialog = Dialog.None }, width = 560.dp, onEnter = { st.dialog = Dialog.None }) {
        Txt(S.howTo, Type.title)
        Spacer(h = 8.dp)
        S.rules.forEachIndexed { i, r ->
            Row(Modifier.padding(vertical = 6.dp)) { Txt("${i + 1}", Type.meta, c.muted, Modifier.width(24.dp)); Txt(r, Type.task.copy(fontSize = Type.ui.fontSize * 1.1f), c.ink) }
        }
        DialogButtons { Btn(S.close, { st.dialog = Dialog.None }, Kind.Filled, "Esc", height = 32.dp) }
    }
}

@Composable
fun AboutDialog(st: AppState) {
    val c = Ui.c
    Modal({ st.dialog = Dialog.None }, onEnter = { st.dialog = Dialog.None }) {
        Txt(S.appName, Type.title)
        Spacer(h = 8.dp)
        Txt(S.aboutText(Diagnostics.version), Type.body, c.muted)
        DialogButtons {
            Btn(S.report + "…", { st.dialog = Dialog.Report }, Kind.Outline, height = 32.dp)
            Btn(S.close, { st.dialog = Dialog.None }, Kind.Filled, "Esc", height = 32.dp)
        }
    }
}

/** Отчёт об ошибке: виден целиком; отправляет сам человек — через GitHub, буфер обмена или файл. */
@Composable
fun ReportDialog(st: AppState, window: Frame?) {
    val c = Ui.c
    val report = remember { st.report(200) }
    var status by remember { mutableStateOf<String?>(null) }
    val close = { st.dialog = Dialog.None }
    fun copy() = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(report), null)
    }
    Modal(close, width = 680.dp) {
        Txt(S.report, Type.title)
        Spacer(h = 8.dp)
        if (st.crash != null) {
            Txt(S.crashBanner, Type.ui, c.ink, Modifier.padding(bottom = 6.dp))
        }
        Txt(S.reportLead, Type.body, c.muted)
        Spacer(h = 12.dp)
        Box(
            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp)
                .border(1.dp, c.rule, RoundedCornerShape(6.dp)).padding(12.dp),
        ) {
            SelectionContainer {
                Txt(
                    report, Type.meta.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp), c.ink,
                    Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            }
        }
        status?.let { Txt(it, Type.meta, c.ink, Modifier.padding(top = 8.dp)) }
        DialogButtons {
            Btn(S.save + "…", {
                val d = FileDialog(window, S.report, FileDialog.SAVE).apply { file = S.reportFile; isVisible = true }
                if (d.file != null) {
                    status = runCatching { File(d.directory, d.file).writeText(report) }
                        .fold({ st.dismissCrash(); S.reportSaved(d.file) }, { it.message ?: "" })
                }
            }, Kind.Outline, height = 32.dp)
            Btn(S.copy, { copy(); status = S.reportCopied; st.dismissCrash() }, Kind.Outline, height = 32.dp)
            Btn(if (st.crash != null) S.later else S.close, { st.dismissCrash(); close() }, Kind.Outline, "Esc", height = 32.dp)
            Btn(S.reportOpen, {
                val url = Diagnostics.issueUrl(S.reportIntro) { st.report(it) }
                    ?: Diagnostics.issueUrlWith(S.reportIntro, null).also { copy(); status = S.reportPaste }
                Updates.open(url)
                st.dismissCrash()
            }, Kind.Filled, height = 32.dp)
        }
    }
}
