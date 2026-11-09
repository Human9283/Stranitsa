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

    /** Вышла новая версия (проверка раз в 12 часов, если включена в настройках). */
    var update by mutableStateOf<Release?>(null)
        private set
    /** Когда и с кем в последний раз прошла автосинхронизация. */
    var lastAutoSync by mutableStateOf<Pair<Long, String>?>(null)
        private set
    private var syncJob: Job? = null

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
        val reports = runCatching { AutoSync.runOnce(getApplication(), repo.core) }.getOrDefault(emptyList())
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
            update = Release(cached, prefs.getString("page", "")!!, prefs.getString("apk", null))
        }
        if (now() - last < 12 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { Updates.fetchLatest() } ?: return@launch
            prefs.edit().putLong("checked", now()).putString("version", r.version)
                .putString("page", r.pageUrl).putString("apk", r.apkUrl).apply()
            update = r.takeIf { Updates.isNewer(it.version, BuildConfig.VERSION_NAME) }
        }
    }

    fun dismissUpdate() { update = null }

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
    override fun setCheckUpdates(on: Boolean) { settings { it.copy(checkUpdates = on) }; if (on) checkUpdates() else update = null }
    override fun switchList(id: String) { breakDays = 0; repo.apply { c, _ -> c.switchList(id) } }
    override fun addList(name: String) { repo.apply { c, n -> c.addList(name, n) } }
    override fun renameList(id: String, name: String) { repo.apply { c, n -> c.renameList(id, name, n) } }
    override fun deleteList(id: String) { repo.apply { c, n -> c.deleteList(id, n) } }
    override fun search(query: String, includeClosed: Boolean) = repo.search(query, includeClosed)
    override fun doneSince(since: Long) = repo.doneSince(since)
    override fun exportJson(): String = repo.exportJson()
    override fun importJson(text: String) { repo.apply { c, n -> c.importJson(text, n) } }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        const val BREAK_DAYS = 3
    }
}
