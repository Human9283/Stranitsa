package app.stranitsa.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.stranitsa.core.StCore
import app.stranitsa.core.ThemeMode
import java.awt.Dimension
import java.io.File
import java.util.prefs.Preferences

private val isMac = System.getProperty("os.name").lowercase().contains("mac")

/** Ctrl на Windows и Linux, ⌘ на macOS. */
private fun KeyEvent.primary() = if (isMac) isMetaPressed else isCtrlPressed
private fun shortcut(k: Key, shift: Boolean = false) = if (isMac) KeyShortcut(k, meta = true, shift = shift) else KeyShortcut(k, ctrl = true, shift = shift)

fun main() {
    val file = File(dataDir(), "stranitsa.json")
    val core = StCore(file.path, System.currentTimeMillis())
    val prefs = Preferences.userRoot().node("app/stranitsa")
    application {
        val st = remember { AppState(core).also { it.checkBreak(); it.sidebar = prefs.getBoolean("sidebar", true) } }
        val ws = rememberWindowState(
            size = DpSize(prefs.getInt("w", 1100).dp, prefs.getInt("h", 760).dp),
            position = if (prefs.getInt("x", Int.MIN_VALUE) == Int.MIN_VALUE) WindowPosition.PlatformDefault
            else WindowPosition(prefs.getInt("x", 0).dp, prefs.getInt("y", 0).dp),
        )
        fun quit() {
            st.markSeen(); core.flush()
            prefs.putInt("w", ws.size.width.value.toInt()); prefs.putInt("h", ws.size.height.value.toInt())
            (ws.position as? WindowPosition.Absolute)?.let { prefs.putInt("x", it.x.value.toInt()); prefs.putInt("y", it.y.value.toInt()) }
            prefs.putBoolean("sidebar", st.sidebar)
            exitApplication()
        }
        val icon = remember { runCatching { BitmapPainter(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use(::loadImageBitmap)) }.getOrNull() }
        Window(
            onCloseRequest = ::quit, state = ws, icon = icon,
            title = S.appName + " — " + st.snap.name.ifBlank { S.defaultList },
            onPreviewKeyEvent = { handleKey(st, it) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(640, 480) }
            AppMenu(st, ::quit)
            val dark = when (st.snap.settings.theme) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
            AppTheme(if (!dark) Light else if (st.snap.settings.pureBlack) Amoled else Dark) {
                App(st, file, window)
            }
        }
    }
}

@Composable
private fun App(st: AppState, file: File, window: java.awt.Frame) {
    Box(Modifier.fillMaxSize().background(Ui.c.bg)) {
        Row(Modifier.fillMaxSize()) {
            if (st.sidebar) {
                Sidebar(st)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Ui.c.rule))
            }
            Box(Modifier.weight(1f)) { MainPane(st) }
        }
        when (st.dialog) {
            Dialog.None -> {}
            Dialog.Confirm -> ConfirmDialog(st)
            Dialog.Settings -> SettingsDialog(st, file, window)
            Dialog.Sync -> SyncDialog(st)
            Dialog.Keys -> KeysDialog(st)
            Dialog.HowTo -> HowToDialog(st)
            Dialog.About -> AboutDialog(st)
        }
    }
}

@Composable
private fun FrameWindowScope.AppMenu(st: AppState, quit: () -> Unit) {
    MenuBar {
        Menu(S.menuFile, mnemonic = if (S.menuFile == "File") 'F' else 'Ф') {
            Item(S.newTask, onClick = { st.view = View.Page; st.focusNewTask++ }, shortcut = shortcut(Key.N))
            Item(S.newList, onClick = { st.sidebar = true }, shortcut = shortcut(Key.N, shift = true))
            Separator()
            Item(S.sync, onClick = { st.dialog = Dialog.Sync })
            Item(S.settings, onClick = { st.dialog = Dialog.Settings }, shortcut = shortcut(Key.Comma))
            Separator()
            Item(S.quit, onClick = quit, shortcut = shortcut(Key.Q))
        }
        Menu(S.menuView) {
            Item(S.search, onClick = { st.focusSearch++ }, shortcut = shortcut(Key.F))
            Item(S.dismissed, onClick = { st.view = View.Dismissed })
            Item(S.reviewTitle, onClick = { st.view = View.Review })
            Separator()
            CheckboxItem(S.sidebar, st.sidebar, onCheckedChange = { st.sidebar = it }, shortcut = KeyShortcut(Key.F9))
        }
        Menu(S.menuHelp) {
            Item(S.keys, onClick = { st.dialog = Dialog.Keys }, shortcut = KeyShortcut(Key.F1))
            Item(S.howTo, onClick = { st.dialog = Dialog.HowTo })
            Item(S.about, onClick = { st.dialog = Dialog.About })
        }
    }
}

/**
 * Клавиши, которые зависят от того, где фокус: в текстовом поле стрелки, Delete и Ctrl+→
 * принадлежат полю, поэтому они здесь, а не в меню.
 */
private fun handleKey(st: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (st.dialog != Dialog.None) return false // у окна поверх свои клавиши (Esc, Enter)
    val mod = e.primary()
    if (st.textFocused) {
        return when {
            e.key == Key.Escape && st.view == View.Search -> { st.query = ""; st.view = View.Page; true }
            else -> false
        }
    }
    if (st.view != View.Page) {
        if (e.key == Key.Escape) { st.view = View.Page; return true }
        return false
    }
    return when {
        e.key == Key.DirectionDown && !mod -> { st.moveSelection(+1); true }
        e.key == Key.DirectionUp && !mod -> { st.moveSelection(-1); true }
        mod && e.key == Key.Enter -> { st.done(); true }
        mod && e.isShiftPressed && e.key == Key.DirectionRight -> { st.next(); true }
        mod && e.key == Key.DirectionRight -> { st.reenter(); true }
        e.key == Key.F2 -> { st.startEdit(); true }
        e.key == Key.Delete -> { st.delete(); true }
        mod && e.key == Key.Z -> { st.undo(); true }
        e.key == Key.PageUp -> { if (st.page > 0) st.viewPage = st.page - 1; true }
        e.key == Key.PageDown -> { if (st.page < st.snap.lastPage) st.viewPage = st.page + 1; true }
        e.key == Key.Escape -> { st.selectedId = null; true }
        else -> false
    }
}
