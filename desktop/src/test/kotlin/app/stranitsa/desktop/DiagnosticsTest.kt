package app.stranitsa.desktop

import app.stranitsa.core.StCore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun reportHasNoUserText() {
        val core = StCore.inMemory(1)
        core.add("Секретная задача про Машу", 2)
        core.addList("Тайный проект", 3)
        val r = Diagnostics.report(core, crash = "Thread 'main'\njava.lang.IllegalStateException: boom")
        assertTrue(r.startsWith("Stranitsa "))
        assertTrue(r.contains("lists: 2"))
        assertTrue(r.contains("IllegalStateException: boom"))
        for (w in listOf("Секрет", "Маш", "Тайн")) assertFalse(r.contains(w), w)
    }

    @Test
    fun issueUrlFitsAndKeepsReport() {
        val url = Diagnostics.issueUrl("intro") { n -> "head\n" + (1..n).joinToString("\n") { "event $it ".repeat(8) } }
        assertNotNull(url)
        assertTrue(url.length <= Diagnostics.MAX_URL)
        assertTrue(java.net.URLDecoder.decode(url.substringAfter("body="), "UTF-8").contains("head"))
    }
}
