package app.stranitsa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DiagnosticsTest {
    @Test
    fun stackIsTrimmedButKeepsCauses() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("inner") else deep(n - 1)
        val e = try { deep(100) } catch (x: Throwable) { RuntimeException("outer", x) }
        val s = Diagnostics.stackOf(e)
        assertTrue(s.startsWith("java.lang.RuntimeException: outer"))
        assertTrue(s.contains("Caused by: java.lang.IllegalStateException: inner"))
        assertTrue(s.lines().size <= 4 * 42)
    }

    @Test
    fun issueUrlShrinksTheJournalToFitAndKeepsTheReport() {
        val url = Diagnostics.issueUrl("intro") { n -> "head\n" + (1..n).joinToString("\n") { "event $it ".repeat(8) } }
        assertNotNull(url)
        assertTrue(url!!.length <= Diagnostics.MAX_URL)
        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        assertTrue(body.startsWith("intro"))
        assertTrue(body.contains("head"))
    }

    @Test
    fun hugeReportFallsBackToNoReportInLink() {
        assertEquals(null, Diagnostics.issueUrl("intro") { "x".repeat(20_000) })
        val plain = Diagnostics.issueUrlWith("intro text", null)
        assertTrue(plain.endsWith("body=intro%20text"))
    }
}
