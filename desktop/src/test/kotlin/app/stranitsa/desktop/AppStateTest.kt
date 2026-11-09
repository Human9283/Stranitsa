package app.stranitsa.desktop

import app.stranitsa.core.StCore
import app.stranitsa.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Логика окна поверх настоящего ядра (в памяти): выбор, действия, «Дальше», отмена. */
class AppStateTest {
    private var t = 1_000L
    private fun state(n: Int, pageSize: Int = 10): AppState {
        val core = StCore.inMemory(0)
        core.setPageSize(pageSize)
        repeat(n) { core.add("t${it + 1}", it.toLong()) }
        return AppState(core) { t++ }
    }

    @Test fun arrowsSelectActiveRowsOfThePage() {
        val st = state(25)
        st.moveSelection(+1)
        assertEquals("t1", st.snap.entries.first { it.id == st.selectedId }.text)
        st.moveSelection(+1); st.moveSelection(+1)
        assertEquals("t3", st.snap.entries.first { it.id == st.selectedId }.text)
        repeat(20) { st.moveSelection(+1) }
        assertEquals("t10", st.snap.entries.first { it.id == st.selectedId }.text, "не уходит за страницу")
    }

    @Test fun doneMovesSelectionToNextAndCanBeUndone() {
        val st = state(25)
        st.moveSelection(+1); st.moveSelection(+1) // t2
        st.done()
        assertEquals(Status.DONE, st.snap.entries[1].status)
        assertEquals("t3", st.snap.entries.first { it.id == st.selectedId }.text)
        assertTrue(st.bar != null && st.snap.canUndo)
        st.undo()
        assertEquals(Status.ACTIVE, st.snap.entries[1].status)
    }

    @Test fun reenterAppendsToTheEnd() {
        val st = state(25)
        st.moveSelection(+1)
        st.reenter()
        assertEquals("t1", st.snap.entries.last().text)
        assertEquals(1, st.snap.entries.last().reentries)
    }

    @Test fun nextAsksBeforeDismissingAPageNothingStoodOutOn() {
        val st = state(25)
        st.next()
        assertEquals(Dialog.Confirm, st.dialog)
        st.dismissPage()
        assertEquals(1, st.snap.currentPage)
        assertEquals(1, st.page, "показываем новую рабочую страницу")
        assertTrue(st.snap.entries.take(10).all { it.status == Status.DISMISSED })
    }

    @Test fun nextAfterWorkingOnPageMovesOnWithoutAsking() {
        val st = state(25)
        st.moveSelection(+1); st.done()
        st.next()
        assertEquals(Dialog.None, st.dialog)
        assertEquals(1, st.snap.currentPage)
        assertNull(st.selectedId)
    }

    @Test fun viewingAnotherPageDoesNotChangeTheWorkingPage() {
        val st = state(25)
        st.viewPage = 2
        assertTrue(!st.onCurrent)
        st.next() // «К странице 01»
        assertEquals(0, st.page)
        assertEquals(0, st.snap.currentPage)
    }

    @Test fun deleteIsNotWorkOnThePage() {
        val st = state(25)
        st.moveSelection(+1); st.delete()
        assertTrue(st.snap.dismissApplies)
    }
}
