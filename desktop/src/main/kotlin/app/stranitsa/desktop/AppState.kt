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
enum class Dialog { None, Confirm, Settings, Sync, Keys, HowTo, About, Report }

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
    val autoSync = AutoSync(this)
    var update by mutableStateOf<UpdateStep?>(null)
    @Volatile private var downloading = false
    /** Номер загрузки: отмена и новая загрузка не должны мешать друг другу. */
    @Volatile private var generation = 0
    /** Закрыть программу целиком — нужно Windows, чтобы установщик мог заменить файлы. Задаётся в Main. */
    var quit: () -> Unit = {}

    /** Растёт при каждом изменении данных: по нему видно, что после очистки уже что-то менялось. */
    var revision by mutableIntStateOf(0); private set
    private var cleaned by mutableStateOf<Pair<Int, Int>?>(null)
    /** Сколько строк убрала очистка, если её ещё можно отменить. */
    val cleanupUndo: Int? get() = cleaned?.takeIf { it.second == revision }?.first

    fun cleanup(days: Int) {
        val count = core.cleanupPreview(days, clock())
        apply { c, n -> c.cleanup(days, n) }
        cleaned = count to revision
    }
    /** Описание сбоя прошлого запуска — при запуске предлагаем отправить отчёт. */
    var crash by mutableStateOf(Diagnostics.pendingCrash())

    fun report(maxEvents: Int): String = Diagnostics.report(core, crash, maxEvents)

    /** Отчёт отправлен, сохранён или отклонён — больше не предлагать. */
    fun dismissCrash() { Diagnostics.clearCrash(); crash = null }

    /** Раз в запуск: не вышла ли новая версия (если включено в настройках). */
    fun checkUpdates() {
        if (!snap.settings.checkUpdates || Updates.current == "0") return
        Thread {
            val r = Updates.fetchLatest()
            javax.swing.SwingUtilities.invokeLater {
                if (r != null && Updates.isNewer(r.version, Updates.current)) {
                    // Файл этой версии уже скачан раньше (и проверен): сразу «Установить».
                    val f = Updates.fileFor(Updates.dir(), r)
                    update = if (r.assetUrl != null && f.isFile) UpdateStep.Ready(r, f) else UpdateStep.Available(r)
                } else Updates.cleanOld(Updates.dir(), null)
            }
        }.apply { isDaemon = true }.start()
    }

    /** «Обновить»: скачать установщик внутри программы. */
    fun downloadUpdate() {
        val r = update?.release ?: return
        if (r.assetUrl == null || downloading) return
        downloading = true
        val mine = ++generation
        update = UpdateStep.Downloading(r, 0)
        Diagnostics.log("update: download ${r.version}")
        Thread {
            val result = runCatching {
                Updates.cleanOld(Updates.dir(), r)
                Updates.download(r, Updates.dir(), cancelled = { generation != mine }) { done, total ->
                    val p = if (total > 0) (done * 100 / total).toInt() else -1
                    javax.swing.SwingUtilities.invokeLater { if ((update as? UpdateStep.Downloading)?.percent != p && update is UpdateStep.Downloading) update = UpdateStep.Downloading(r, p) }
                }
            }
            javax.swing.SwingUtilities.invokeLater {
                val current = generation == mine
                if (current) downloading = false
                result.onSuccess { if (current && update is UpdateStep.Downloading) update = UpdateStep.Ready(r, it) }
                    .onFailure {
                        // Отмену уже обработали кнопки «Отмена» и «Не сейчас».
                        if (current && it.message != "cancelled") {
                            Diagnostics.log("update: download failed (${it.message})")
                            update = UpdateStep.Failed(r, it.message ?: it.javaClass.simpleName)
                        }
                    }
            }
        }.apply { isDaemon = true; name = "stranitsa-update" }.start()
    }

    fun cancelDownload() { generation++; downloading = false; update?.release?.let { update = UpdateStep.Available(it) } }
    fun dismissUpdate() { generation++; downloading = false; update = null }

    /** Открыть скачанный установщик. На Windows программа закрывается, чтобы установщик мог заменить файлы. */
    fun installUpdate(file: File): Boolean {
        val ok = runCatching { java.awt.Desktop.getDesktop().open(file) }.isSuccess
        if (!ok) { runCatching { java.awt.Desktop.getDesktop().open(file.parentFile) }; return false }
        if (System.getProperty("os.name").lowercase().contains("win")) quit()
        return true
    }

    fun searchHits(): List<app.stranitsa.core.SearchHit> = if (query.isBlank()) emptyList() else core.search(query, includeClosed)

    private var knownCurrent = snap.currentPage

    private fun publish(s: Snapshot) {
        snap = s
        revision++
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
