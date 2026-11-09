package app.stranitsa

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import app.stranitsa.core.SearchHit
import app.stranitsa.core.Settings
import app.stranitsa.core.Snapshot
import app.stranitsa.core.ThemeMode
import kotlinx.coroutines.flow.StateFlow

/** Действия, которые экраны могут выполнять. Вся логика — в общем ядре (Rust). */
interface StActions {
    fun undo()
    fun add(text: String)
    fun done(id: String)
    fun reenter(id: String)
    fun cancel(id: String)
    fun edit(id: String, text: String)
    fun advance(dismiss: Boolean)
    fun revive(id: String, text: String)
    fun forget(id: String)
    fun setPageSize(size: Int)
    fun setShowHints(show: Boolean)
    fun setTheme(mode: ThemeMode)
    fun setPureBlack(on: Boolean)
    fun setDynamicColor(on: Boolean)
    fun setSystemBackup(on: Boolean)
    fun setAutoSync(minutes: Int)
    fun setCheckUpdates(on: Boolean)
    fun setShowCleanupUndo(on: Boolean)
    fun cleanupPreview(days: Int): Int
    fun cleanup(days: Int)
    fun switchList(id: String)
    fun addList(name: String)
    fun renameList(id: String, name: String)
    fun deleteList(id: String)
    fun search(query: String, includeClosed: Boolean): List<SearchHit>
    fun doneSince(since: Long): Int
    fun exportJson(): String
    /** @throws app.stranitsa.core.CoreException если файл — не копия «Страницы». */
    fun importJson(text: String)
}

class StViewModel(app: Application) : AndroidViewModel(app), StActions {

    private val repo = app.repository
    val snapshot: StateFlow<Snapshot> = repo.state
    /** Ядро — для экрана синхронизации. */
    val core get() = repo.core

    /** После синхронизации данные изменились в ядре — перечитать. */
    fun refresh() = repo.refresh()

    /** Сколько дней не открывали список (0 — перерыва не было или уже прочитали). */
    var breakDays by mutableIntStateOf(0)
        private set

    /** Обновление: вышла новая версия → загрузка → установка (проверка раз в 12 часов, если включена). */
    var update by mutableStateOf<UpdateStep?>(null)
        private set
    private var downloadJob: Job? = null

    /** Сколько строк убрала последняя очистка, пока после неё ничего не менялось (чтобы можно было отменить). */
    private var cleaned by mutableStateOf<Pair<Int, Int>?>(null)
    val cleanupUndo: Int? get() = cleaned?.takeIf { it.second == repo.revision }?.first
    /** Когда и с кем в последний раз прошла автосинхронизация. */
    var lastAutoSync by mutableStateOf<Pair<Long, String>?>(null)
        private set
    private var syncJob: Job? = null

    /** Описание сбоя прошлого запуска — предложить отправить отчёт. */
    var crash by mutableStateOf(Diagnostics.pendingCrash())
        private set

    /** Отчёт отправлен, скопирован или отклонён — больше не предлагать. */
    fun dismissCrash() { Diagnostics.clearCrash(); crash = null }

    /** Текст отчёта об ошибке (с описанием сбоя, если он был). */
    fun report(maxEvents: Int): String = Diagnostics.report(getApplication(), repo.core, crash, maxEvents)

    private fun now() = System.currentTimeMillis()
    private fun settings(f: (Settings) -> Settings) {
        repo.apply { c, _ -> c.setSettings(f(repo.state.value.settings)) }
    }

    /** Приложение открыто: замечаем долгий перерыв (Форстер: после перерыва прочитать все задачи). */
    fun onAppStart() {
        val s = snapshot.value
        val last = s.settings.lastSeen
        val days = if (last > 0) ((now() - last) / DAY).toInt() else 0
        if (days >= BREAK_DAYS && s.activeTotal > 0) breakDays = days
        markSeen()
        checkUpdates()
        startAutoSync()
    }

    /** Приложение ушло с экрана: автосинхронизация останавливается до возвращения. */
    fun onAppStop() { syncJob?.cancel(); syncJob = null }

    private fun startAutoSync() {
        syncJob?.cancel()
        val minutes = snapshot.value.settings.autoSyncMinutes
        if (minutes <= 0) return
        syncJob = viewModelScope.launch {
            while (isActive) {
                syncNow()
                delay(minutes * 60_000L)
            }
        }
    }

    /** Синхронизироваться со знакомыми устройствами рядом сейчас. */
    suspend fun syncNow() {
        val reports = runCatching { AutoSync.runOnce(getApplication(), repo.core) }
            .onFailure { Diagnostics.log("autosync: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        if (reports.isNotEmpty()) {
            repo.refresh()
            lastAutoSync = now() to reports.joinToString { it.peerName }
        }
    }

    private fun checkUpdates() {
        if (!snapshot.value.settings.checkUpdates) { update = null; return }
        val prefs = getApplication<Application>().getSharedPreferences("updates", Context.MODE_PRIVATE)
        val last = prefs.getLong("checked", 0)
        val cached = prefs.getString("version", null)
        if (cached != null && Updates.isNewer(cached, BuildConfig.VERSION_NAME)) {
            val r = Release(
                cached, prefs.getString("page", "")!!, prefs.getString("apk", null),
                prefs.getString("sha", null), prefs.getLong("size", -1).takeIf { it > 0 },
            )
            if (update == null) showUpdate(r)
        } else {
            // Уже обновились (или обновлений нет): скачанное больше не нужно.
            Updates.cleanOld(UpdateInstaller.dir(getApplication()), null)
        }
        if (now() - last < 12 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { Updates.fetchLatest() } ?: return@launch
            prefs.edit().putLong("checked", now()).putString("version", r.version)
                .putString("page", r.pageUrl).putString("apk", r.apkUrl)
                .putString("sha", r.apkSha256).putLong("size", r.apkSize ?: -1).apply()
            if (Updates.isNewer(r.version, BuildConfig.VERSION_NAME)) { if (update == null) showUpdate(r) } else update = null
        }
    }

    /** Показать плашку; если файл этой версии уже скачан (и проверен) — сразу «Установить». */
    private fun showUpdate(r: Release) {
        val dir = UpdateInstaller.dir(getApplication())
        val file = Updates.fileFor(dir, r)
        update = if (r.apkUrl != null && file.isFile) UpdateStep.Ready(r, file) else UpdateStep.Available(r)
    }

    /** «Обновить»: скачать файл внутри приложения. */
    fun downloadUpdate() {
        val r = update?.release ?: return
        if (r.apkUrl == null || update is UpdateStep.Downloading) return
        update = UpdateStep.Downloading(r, 0)
        Diagnostics.log("update: download ${r.version}")
        downloadJob = viewModelScope.launch {
            val dir = UpdateInstaller.dir(getApplication())
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Updates.cleanOld(dir, r)
                    Updates.download(r, dir, cancelled = { downloadJob?.isActive != true }) { done, total ->
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        val cur = update
                        if (cur is UpdateStep.Downloading && cur.percent != p) update = UpdateStep.Downloading(r, p)
                    }
                }
            }
            result.onSuccess { if (update is UpdateStep.Downloading) update = UpdateStep.Ready(r, it) }
                .onFailure {
                    // Отмена: плашку уже поменяла кнопка «Отмена» или «Не сейчас».
                    if (it.message != "cancelled") {
                        Diagnostics.log("update: download failed (${it.message})")
                        update = UpdateStep.Failed(r, it.message ?: it.javaClass.simpleName)
                    }
                }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); downloadJob = null; update?.release?.let { update = UpdateStep.Available(it) } }

    fun dismissUpdate() { downloadJob?.cancel(); downloadJob = null; update = null }

    /** Файл пишется в фоне; уходя с экрана, дожидаемся, чтобы он точно оказался на диске. */
    fun flush() = repo.flush()

    fun markSeen() = settings { it.copy(lastSeen = now()) }
    fun dismissBreak() { breakDays = 0 }

    override fun undo() { repo.apply { c, n -> c.undo(n) } }
    override fun add(text: String) { repo.apply { c, n -> c.add(text, n) } }
    override fun done(id: String) { repo.apply { c, n -> c.done(id, n) } }
    override fun reenter(id: String) { repo.apply { c, n -> c.reenter(id, n) } }
    override fun cancel(id: String) { repo.apply { c, n -> c.cancel(id, n) } }
    override fun edit(id: String, text: String) { repo.apply { c, n -> c.edit(id, text, n) } }
    override fun advance(dismiss: Boolean) { repo.apply { c, n -> c.advance(dismiss, n) } }
    override fun revive(id: String, text: String) { repo.apply { c, n -> c.revive(id, text, n) } }
    override fun forget(id: String) { repo.apply { c, n -> c.forget(id, n) } }
    override fun setPageSize(size: Int) { repo.apply { c, _ -> c.setPageSize(size) } }
    override fun setShowHints(show: Boolean) = settings { it.copy(showHints = show) }
    override fun setTheme(mode: ThemeMode) = settings { it.copy(theme = mode) }
    override fun setPureBlack(on: Boolean) = settings { it.copy(pureBlack = on) }
    override fun setDynamicColor(on: Boolean) = settings { it.copy(dynamicColor = on) }
    override fun setSystemBackup(on: Boolean) = settings { it.copy(systemBackup = on) }
    override fun setAutoSync(minutes: Int) { settings { it.copy(autoSyncMinutes = minutes) }; startAutoSync() }
    override fun setShowCleanupUndo(on: Boolean) = settings { it.copy(showCleanupUndo = on) }
    override fun setCheckUpdates(on: Boolean) { settings { it.copy(checkUpdates = on) }; if (on) checkUpdates() else update = null }
    override fun switchList(id: String) { breakDays = 0; repo.apply { c, _ -> c.switchList(id) } }
    override fun addList(name: String) { repo.apply { c, n -> c.addList(name, n) } }
    override fun renameList(id: String, name: String) { repo.apply { c, n -> c.renameList(id, name, n) } }
    override fun deleteList(id: String) { repo.apply { c, n -> c.deleteList(id, n) } }
    override fun cleanupPreview(days: Int) = repo.core.cleanupPreview(days, now())
    override fun cleanup(days: Int) {
        val count = repo.core.cleanupPreview(days, now())
        repo.apply { c, n -> c.cleanup(days, n) }
        cleaned = count to repo.revision
    }
    override fun search(query: String, includeClosed: Boolean) = repo.search(query, includeClosed)
    override fun doneSince(since: Long) = repo.doneSince(since)
    override fun exportJson(): String = repo.exportJson()
    override fun importJson(text: String) { repo.apply { c, n -> c.importJson(text, n) } }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        const val BREAK_DAYS = 3
    }
}
