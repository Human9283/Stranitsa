package app.stranitsa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stranitsa.R
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status

/**
 * Все невыполненные задачи подряд — только чтение.
 * Форстер: после долгого перерыва прочитайте весь список, чтобы голова получила общую картину.
 */
@Composable
fun ReviewScreen(state: Snapshot, onBack: () -> Unit, onOpenEntry: (String) -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val byPage = remember(state.entries, state.pageSize) {
        state.entries.withIndex()
            .filter { it.value.status == Status.ACTIVE }
            .groupBy { state.pageOfIndex(it.index) }
            .toSortedMap()
    }
    SubScreen(stringResource(R.string.review_title), onBack, scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Txt(
                    stringResource(R.string.review_lead), t.body, c.muted,
                    Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            byPage.forEach { (page, rows) ->
                item(key = "p$page") {
                    SectionLabel(stringResource(R.string.review_page, pad2(page + 1)))
                    Hairline()
                }
                items(rows, key = { it.value.id }) { (i, e) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenEntry(e.id) }
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
                    ) {
                        Txt((i + 1).toString(), t.meta, c.muted, Modifier.width(38.dp).alignByBaseline())
                        Txt(e.text, t.task, modifier = Modifier.weight(1f).alignByBaseline())
                        if (e.reentries > 0) Txt("×${e.reentries}", t.meta, c.muted, Modifier.padding(start = 12.dp).alignByBaseline())
                    }
                }
            }
            item {
                Hairline(Modifier.padding(top = 8.dp))
                FilledAction(
                    stringResource(R.string.review_done), onBack,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}
