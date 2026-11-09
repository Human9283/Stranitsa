package app.stranitsa.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Английские и русские строки должны совпадать по ключам и плейсхолдерам. */
class StringsParityTest {
    private val entry = Regex("""<(string|plurals|string-array) name="([^"]+)"([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    private fun load(dir: String): Map<String, Set<String>> =
        entry.findAll(File("src/main/res/$dir/strings.xml").readText())
            .filterNot { it.groupValues[3].contains("translatable=\"false\"") }
            .associate { m ->
                val key = m.groupValues[1] + ":" + m.groupValues[2]
                key to placeholder.findAll(m.groupValues[4]).map { it.value.replace("1$", "") }.toSet()
            }

    @Test fun sameKeysAndPlaceholders() {
        val en = load("values")
        val ru = load("values-ru")
        assertEquals("ключи", en.keys.sorted(), ru.keys.sorted())
        val mismatched = en.keys.filter { en[it] != ru[it] }.map { "$it: en=${en[it]} ru=${ru[it]}" }
        assertEquals(emptyList<String>(), mismatched)
    }

    /** aapt обрезает пробелы по краям строки, если она не в кавычках. */
    @Test fun edgeSpacesAreQuoted() {
        val bad = listOf("values", "values-ru").flatMap { dir ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""").findAll(File("src/main/res/$dir/strings.xml").readText())
                .filter { m -> val v = m.groupValues[2]; (v.startsWith(" ") || v.endsWith(" ")) }
                .map { "$dir/${it.groupValues[1]}" }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun arraysHaveSameLength() {
        fun count(dir: String, name: String) =
            Regex("""<string-array name="$name">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
                .find(File("src/main/res/$dir/strings.xml").readText())!!.groupValues[1].split("<item>").size - 1
        for (a in listOf("rules", "first_steps")) assertEquals(a, count("values", a), count("values-ru", a))
    }

    @Test fun noCyrillicLeftInCode() {
        val cyr = Regex(""""[^"\n]*[А-Яа-яЁё][^"\n]*"""")
        val hits = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> cyr.containsMatchIn(l) }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
