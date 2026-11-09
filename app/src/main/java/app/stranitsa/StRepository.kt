package app.stranitsa

import app.stranitsa.core.StCore
import app.stranitsa.core.SearchHit
import app.stranitsa.core.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единственный владелец данных на всё приложение — тонкая обёртка над общим Rust-ядром.
 * Ядро само хранит состояние, ведёт отмену и атомарно пишет файл после каждого изменения;
 * здесь — только поток «снимков» для экранов. Главный экран и «Поделиться» видят одно и то же.
 */
class StRepository(
    /** Ядро — для синхронизации, которой нужно работать с ним напрямую. */
    val core: StCore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Вызывается, когда меняется согласие на системную резервную копию. */
    private val onBackupFlag: (Boolean) -> Unit = {},
    /** Вызывается после каждого нового снимка — например, чтобы перерисовать виджет. */
    private val onChanged: (Snapshot) -> Unit = {},
) {
    private val _state = MutableStateFlow(core.snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Растёт при каждом изменении данных — по нему видно, что после очистки уже что-то менялось. */
    var revision by mutableIntStateOf(0)
        private set

    init {
        onBackupFlag(_state.value.settings.systemBackup)
    }

    /** Выполнить действие ядра и опубликовать новый снимок. */
    @Synchronized
    fun apply(action: (core: StCore, now: Long) -> Snapshot): Snapshot {
        val old = _state.value
        val new = action(core, clock())
        _state.value = new
        revision++
        onChanged(new)
        if (new.settings.systemBackup != old.settings.systemBackup) onBackupFlag(new.settings.systemBackup)
        return new
    }

    /** Перечитать состояние ядра (после синхронизации, которая меняет данные в обход [apply]). */
    @Synchronized
    fun refresh() {
        _state.value = core.snapshot()
        revision++
        onChanged(_state.value)
    }

    fun search(query: String, includeClosed: Boolean): List<SearchHit> = core.search(query, includeClosed)
    fun doneSince(since: Long): Int = core.doneSince(since)
    fun exportJson(): String = core.exportJson()

    /** Дождаться записи файла: запись идёт в фоне, при уходе с экрана нужно убедиться, что она закончилась. */
    fun flush() = core.flush()
}
