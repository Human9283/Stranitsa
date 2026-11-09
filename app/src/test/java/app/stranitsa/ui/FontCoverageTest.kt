package app.stranitsa.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Шрифты урезаны (pyftsubset). Тест не даёт при пересборке потерять нужные алфавиты. */
class FontCoverageTest {
    private val samples = listOf(
        "The quick brown fox", "Größe Straße", "Œuvre ça où", "Zażółć gęślą jaźń",
        "Příliš žluťoučký kůň", "Çalışma ğüşıöİ", "Știință țară", "őrült ű", "Tiếng Việt chất lượng",
        "Съешь ещё этих мягких булок", "Їжак ґанок є", "Қазақ әліпбиі ұғым", "Ђорђе Љубав",
        "«—…»№€₽",
    )

    @Test fun allFontsCoverLatinAndCyrillic() {
        val names = listOf(
            "serif_regular", "serif_italic", "serif_semibold",
            "plex_sans_regular", "plex_sans_medium", "plex_sans_semibold",
        )
        val missing = names.flatMap { n ->
            val cmap = readCmap(n)
            samples.joinToString("").filter { it != ' ' }.toSet()
                .filter { it.code !in cmap }.map { "$n: '$it'" }
        }
        assertEquals(emptyList<String>(), missing)
    }

    /** Знаки из строк интерфейса (стрелки, «×», точка-разделитель) есть в шрифте подписей. */
    @Test fun sansHasInterfaceSymbols() {
        val used = listOf("src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml")
            .joinToString("") { File(it).readText() }
            .filter { it.code > 0x2000 }.toSet()
        listOf("plex_sans_regular", "plex_sans_medium").forEach { n ->
            val cmap = readCmap(n)
            assertEquals(n, "", used.filter { it.code !in cmap }.joinToString(""))
        }
    }

    /** Минимальное чтение таблицы cmap (форматы 4 и 12) — без зависимостей. */
    private fun readCmap(name: String): Set<Int> {
        val b = ByteBuffer.wrap(File("src/main/res/font/$name.ttf").readBytes())
        val numTables = b.getShort(4).toInt() and 0xFFFF
        val cmapOff = (0 until numTables).map { 12 + it * 16 }
            .first { String(ByteArray(4) { i -> b.get(it + i) }) == "cmap" }
            .let { b.getInt(it + 8) }
        val n = b.getShort(cmapOff + 2).toInt() and 0xFFFF
        val subs = (0 until n).map { i ->
            val r = cmapOff + 4 + i * 8
            Triple(b.getShort(r).toInt(), b.getShort(r + 2).toInt(), cmapOff + b.getInt(r + 4))
        }
        val out = HashSet<Int>()
        subs.firstOrNull { b.getShort(it.third).toInt() == 12 }?.let { (_, _, off) ->
            val groups = b.getInt(off + 12)
            for (g in 0 until groups) {
                val p = off + 16 + g * 12
                for (cp in b.getInt(p)..b.getInt(p + 4)) out += cp
            }
            return out
        }
        val off = subs.first { b.getShort(it.third).toInt() == 4 }.third
        val segX2 = b.getShort(off + 6).toInt() and 0xFFFF
        val ends = off + 14
        val starts = ends + segX2 + 2
        val deltas = starts + segX2
        val ranges = deltas + segX2
        for (s in 0 until segX2 / 2) {
            val end = b.getShort(ends + s * 2).toInt() and 0xFFFF
            val start = b.getShort(starts + s * 2).toInt() and 0xFFFF
            val delta = b.getShort(deltas + s * 2).toInt()
            val rangeOff = b.getShort(ranges + s * 2).toInt() and 0xFFFF
            for (cp in start..end) {
                if (cp == 0xFFFF) continue
                val glyph = if (rangeOff == 0) (cp + delta) and 0xFFFF else {
                    val gi = b.getShort(ranges + s * 2 + rangeOff + (cp - start) * 2).toInt() and 0xFFFF
                    if (gi == 0) 0 else (gi + delta) and 0xFFFF
                }
                if (glyph != 0) out += cp
            }
        }
        return out
    }
}
