package app.stranitsa.ui

import app.stranitsa.core.Entry
import app.stranitsa.core.Snapshot
import kotlin.math.min

// Представление снимка для экранов: только нарезка на страницы, вся логика — в ядре.

fun Snapshot.pageEntries(page: Int): List<Entry> {
    val from = page * pageSize
    if (page < 0 || from >= entries.size) return emptyList()
    return entries.subList(from, min(from + pageSize, entries.size))
}

fun Snapshot.activeCountOnPage(page: Int): Int = pageActiveCount.getOrElse(page) { 0 }
fun Snapshot.isPageActive(page: Int): Boolean = activeCountOnPage(page) > 0
fun Snapshot.pageOfIndex(index: Int): Int = index / pageSize
