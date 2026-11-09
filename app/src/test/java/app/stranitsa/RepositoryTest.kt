package app.stranitsa

import app.stranitsa.core.StCore
import app.stranitsa.core.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Проверяет связку Kotlin ↔ Rust через настоящую нативную библиотеку (собранную под компьютер):
 * вызовы доходят до ядра, данные пишутся в файл, старые файлы Android-версий переводятся.
 * Правила AF1 и слияние проверяются тестами самого ядра (`cargo test` в core/).
 */
class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun repo(file: File, flags: MutableList<Boolean> = mutableListOf()) =
        StRepository(StCore(file.path, clock), clock = { clock++ }, onBackupFlag = { flags += it })

    @Test fun mainScreenAndShareSeeOneListAndItIsSaved() {
        val f = File(tmp.root, "stranitsa.json")
        val r = repo(f)
        r.apply { c, n -> c.add("из приложения", n) }
        val listId = r.state.value.currentId
        r.apply { c, n -> c.addTo(listId, listOf("из «Поделиться» 1", "из «Поделиться» 2"), n) }
        r.apply { c, n -> c.add("снова из приложения", n) }
        r.flush()
        val reopened = StCore(f.path, clock).snapshot()
        assertEquals(
            listOf("из приложения", "из «Поделиться» 1", "из «Поделиться» 2", "снова из приложения"),
            reopened.entries.map { it.text },
        )
    }

    @Test fun undoRestoresListButKeepsSettings() {
        val r = repo(File(tmp.root, "stranitsa.json"))
        r.apply { c, n -> c.add("a", n) }
        r.apply { c, _ -> c.setSettings(r.state.value.settings.copy(pureBlack = true, systemBackup = true)) }
        val id = r.state.value.entries[0].id
        r.apply { c, n -> c.done(id, n) }
        val s = r.apply { c, n -> c.undo(n) }
        assertEquals(Status.ACTIVE, s.entries[0].status)
        assertTrue(s.settings.pureBlack && s.settings.systemBackup)
    }

    @Test fun backupFlagIsReportedOnStartAndOnChange() {
        val flags = mutableListOf<Boolean>()
        val r = repo(File(tmp.root, "stranitsa.json"), flags)
        r.apply { c, _ -> c.setSettings(r.state.value.settings.copy(systemBackup = true)) }
        r.apply { c, _ -> c.setSettings(r.state.value.settings.copy(systemBackup = false)) }
        assertEquals(listOf(false, true, false), flags)
    }

    /** Файл, который оставила Android-версия 1.7 (Kotlin): после обновления всё на месте. */
    @Test fun fileFromVersion17Migrates() {
        val f = File(tmp.root, "stranitsa.json")
        f.writeText(
            """{"notebooks":[{"id":1,"name":"","state":{"entries":[{"id":1,"text":"дом","status":"ACTIVE","createdAt":1,"closedAt":null,"reentries":0,"reviewed":false},
               {"id":2,"text":"сделано","status":"DONE","createdAt":2,"closedAt":3,"reentries":0,"reviewed":false}],"pageSize":25,"currentPage":0,"actedOnVisit":true,"nextId":3}},
               {"id":2,"name":"Работа","state":{"entries":[],"pageSize":30,"currentPage":0,"actedOnVisit":false,"nextId":1}}],
               "currentId":1,"nextNotebookId":3,"settings":{"showHints":false,"theme":"DARK","pureBlack":true,"dynamicColor":false,"systemBackup":false,"lastSeen":5}}"""
        )
        val s = repo(f).state.value
        assertEquals(listOf("дом", "сделано"), s.entries.map { it.text })
        assertEquals(Status.DONE, s.entries[1].status)
        assertEquals(listOf("", "Работа"), s.notebooks.map { it.name })
        assertTrue(s.actedOnVisit)
        assertTrue(s.settings.pureBlack && !s.settings.showHints)
    }

    @Test fun twoDevicesMerge() {
        val phone = repo(File(tmp.root, "phone.json"))
        val pc = repo(File(tmp.root, "pc.json"))
        phone.apply { c, n -> c.add("с телефона", n) }
        pc.apply { c, n -> c.importJson(phone.exportJson(), n) }
        pc.apply { c, n -> c.add("с ПК", n) }
        val s = phone.apply { c, n -> c.mergeJson(pc.exportJson(), n) }
        assertEquals(listOf("с телефона", "с ПК"), s.entries.map { it.text })
    }
}
