package app.stranitsa.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.stranitsa.core.StCore
import app.stranitsa.core.Entry
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status
import java.io.File
import java.util.Calendar

enum class View { Page, Search, Dismissed, Review }
enum class Dialog { None, Confirm, Settings, Sync, Keys, HowTo, About }

class Bar(val text: String, val undoable: Boolean, val id: Long = System.nanoTime())

/** Где хранить список: в принятой на каждой системе папке данных пользователя. */
fun dataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val dir = when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Stranitsa")
        os.contains("mac") -> File(home, "Library/Application Support/Stranitsa")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "stranitsa")
    }
    dir.mkdirs()
    return dir
}

fun deviceName(): String =
    System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')
        ?: "PC"

/** Всё состояние окна. Данные и правила — в ядре; здесь — что показано и что выбрано. */
class AppState(val core: StCore, private val clock: () -> Long = System::currentTimeMillis) {
    var snap by mutableStateOf(core.snapshot()); private set
    /** Страница, которую сейчас смотрим (листание не меняет рабочую страницу). */
    var viewPage by mutableIntStateOf(snap.currentPage)
    var selectedId by mutableStateOf<String?>(null)
    var editingId by mutableStateOf<String?>(null)
    var view by mutableStateOf(View.Page)
    var dialog by mutableStateOf(Dialog.None)
    var sidebar by mutableStateOf(true)
    var bar by mutableStateOf<Bar?>(null)
    var query by mutableStateOf("")
    var includeClosed by mutableStateOf(false)
    /** Фокус в текстовом поле: тогда стрелки, Delete и Ctrl+→ принадлежат полю. */
    var textFocused by mutableStateOf(false)
    var focusNewTask by mutableIntStateOf(0)
    var focusSearch by mutableIntStateOf(0)
    var breakDays by mutableIntStateOf(0)
    var searchIndex by mutableIntStateOf(0)

    fun searchHits(): List<app.stranitsa.core.SearchHit> = if (query.isBlank()) emptyList() else core.search(query, includeClosed)

    private var knownCurrent = snap.currentPage

    private fun publish(s: Snapshot) {
        snap = s
        if (s.currentPage != knownCurrent) {
            // Рабочая страница сменилась (дальше / отбрасывание / первая задача) — показываем её.
            knownCurrent = s.currentPage
            viewPage = s.currentPage
            selectedId = null
        }
        viewPage = viewPage.coerceIn(0, s.lastPage)
    }

    fun apply(f: (StCore, Long) -> Snapshot) = publish(f(core, clock()))
    fun refresh() = publish(core.snapshot())

    val page get() = viewPage.coerceIn(0, snap.lastPage)
    val onCurrent get() = page == snap.currentPage

    fun pageEntries(p: Int = page): List<IndexedValue<Entry>> {
        val from = p * snap.pageSize
        return snap.entries.withIndex().drop(from).take(snap.pageSize)
    }

    private fun activeOnPage() = pageEntries().filter { it.value.status == Status.ACTIVE }.map { it.value }

    fun moveSelection(delta: Int) {
        val act = activeOnPage()
        if (act.isEmpty()) return
        val i = act.indexOfFirst { it.id == selectedId }
        val n = if (i < 0) (if (delta > 0) 0 else act.lastIndex) else (i + delta).coerceIn(0, act.lastIndex)
        selectedId = act[n].id
    }

    private fun selected(): Entry? = snap.entries.firstOrNull { it.id == selectedId && it.status == Status.ACTIVE }

    /** После действия выделение переходит на следующую активную строку той же страницы. */
    private fun selectAfter(id: String) {
        val act = activeOnPage()
        val i = act.indexOfFirst { it.id == id }
        selectedId = act.getOrNull(i + 1)?.id ?: act.getOrNull(i - 1)?.id
    }

    fun done(e: Entry? = selected()) {
        e ?: return
        val next = activeOnPage().let { a -> a.getOrNull(a.indexOfFirst { it.id == e.id } + 1)?.id }
        apply { c, n -> c.done(e.id, n) }
        selectedId = next ?: activeOnPage().lastOrNull()?.id
        bar = Bar(S.barDone(e.text), true)
    }

    fun reenter(e: Entry? = selected()) {
        e ?: return
        val next = activeOnPage().let { a -> a.getOrNull(a.indexOfFirst { it.id == e.id } + 1)?.id }
        apply { c, n -> c.reenter(e.id, n) }
        selectedId = next ?: activeOnPage().lastOrNull()?.id
        bar = Bar(S.barReentered(e.text), true)
    }

    fun delete(e: Entry? = selected()) {
        e ?: return
        selectAfter(e.id)
        editingId = null
        apply { c, n -> c.cancel(e.id, n) }
        bar = Bar(S.barDeleted(e.text), true)
    }

    fun startEdit() { selected()?.let { editingId = it.id } }
    fun saveEdit(id: String, text: String) { if (text.isNotBlank()) apply { c, n -> c.edit(id, text, n) }; editingId = null; textFocused = false }

    fun add(text: String) {
        if (text.isBlank()) return
        val target = snap.entries.size / snap.pageSize
        val hadEntries = snap.entries.isNotEmpty()
        apply { c, n -> c.add(text, n) }
        if (hadEntries && target != page) bar = Bar(S.barAdded(pad2(target + 1)), true)
    }

    fun undo() { if (snap.canUndo) { apply { c, n -> c.undo(n) }; bar = null } }

    /** «Дальше»: с рабочей страницы — по правилам (может спросить об отбрасывании); с другой — вернуться. */
    fun next() {
        when {
            !onCurrent -> viewPage = snap.currentPage
            snap.activeTotal == 0 || (snap.nextPage == snap.currentPage && isPageActive(snap.currentPage)) -> {}
            snap.dismissApplies -> dialog = Dialog.Confirm
            else -> apply { c, n -> c.advance(false, n) }
        }
    }

    fun dismissPage() {
        val n = snap.pageActiveCount.getOrElse(snap.currentPage) { 0 }
        apply { c, t -> c.advance(true, t) }
        dialog = Dialog.None
        bar = Bar(S.barDismissed(n), true)
    }

    fun keepPage() { apply { c, t -> c.advance(false, t) }; dialog = Dialog.None }

    fun isPageActive(p: Int) = snap.pageActiveCount.getOrElse(p) { 0 } > 0

    fun open(entryId: String) {
        val idx = snap.entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return
        viewPage = idx / snap.pageSize
        selectedId = entryId.takeIf { snap.entries[idx].status == Status.ACTIVE }
        view = View.Page
    }

    fun switchList(id: String) {
        apply { c, _ -> c.switchList(id) }
        knownCurrent = snap.currentPage; viewPage = snap.currentPage; selectedId = null; view = View.Page
    }

    fun doneToday(): Int = core.doneSince(startOfToday())

    /** Перерыв: сколько полных дней приложение не открывали (как на телефоне — от трёх дней). */
    fun checkBreak() {
        val last = snap.settings.lastSeen
        val days = if (last > 0) ((clock() - last) / 86_400_000L).toInt() else 0
        breakDays = if (days >= 3 && snap.activeTotal > 0) days else 0
    }

    fun markSeen() = apply { c, n -> c.setSettings(snap.settings.copy(lastSeen = n)) }
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun pad2(n: Int) = if (n < 10) "0$n" else n.toString()
