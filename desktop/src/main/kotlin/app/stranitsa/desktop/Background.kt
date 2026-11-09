package app.stranitsa.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.stranitsa.core.SyncHost
import app.stranitsa.core.syncKnownNearby
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.swing.SwingUtilities

/**
 * Автосинхронизация на компьютере. Пока программа запущена, она видна в сети и сама
 * принимает знакомые устройства (незнакомым отказывает — знакомство только через окно
 * «Синхронизация»). Раз в N минут ещё и сама ищет знакомые устройства рядом.
 */
class AutoSync(private val st: AppState) {
    var lastSync by mutableStateOf<Pair<Long, String>?>(null); private set
    @Volatile private var host: SyncHost? = null
    @Volatile private var minutes = 0
    private var listener: Thread? = null
    private var ticker: Thread? = null

    private fun onReports(names: List<String>) {
        if (names.isEmpty()) return
        SwingUtilities.invokeLater { st.refresh(); lastSync = System.currentTimeMillis() to names.joinToString() }
    }

    /** Разовая синхронизация со знакомыми устройствами рядом (меню «Синхронизировать сейчас»). */
    fun syncNow() {
        Thread { runCatching { onReports(syncKnownNearby(st.core, deviceName(), 3000).map { it.peerName }) } }
            .apply { isDaemon = true }.start()
    }

    /** Включить/выключить по настройке; 0 — выключено. */
    @Synchronized
    fun configure(newMinutes: Int) {
        if (newMinutes == minutes && (newMinutes == 0 || host != null)) return
        stop()
        minutes = newMinutes
        if (newMinutes <= 0) return
        resume()
        ticker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { onReports(syncKnownNearby(st.core, deviceName(), 3000).map { it.peerName }) }
                try { Thread.sleep(minutes * 60_000L) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "stranitsa-autosync"; start() }
    }

    /** Окно «Синхронизация» открывает свой приём — фоновый на это время останавливается. */
    @Synchronized
    fun pause() {
        listener?.interrupt(); listener = null
        runCatching { host?.stop() }; host = null
    }

    @Synchronized
    fun resume() {
        if (minutes <= 0 || host != null) return
        val h = runCatching { SyncHost.start(st.core, deviceName(), true) }.getOrNull() ?: return
        host = h
        listener = Thread {
            while (!Thread.currentThread().isInterrupted && host === h) {
                val r = runCatching { h.acceptKnown(1000) }.getOrNull()
                if (r != null) onReports(listOf(r.peerName))
            }
        }.apply { isDaemon = true; name = "stranitsa-listen"; start() }
    }

    @Synchronized
    fun stop() {
        ticker?.interrupt(); ticker = null
        pause()
    }
}

/** Последний выпуск на GitHub; [assetUrl] — установщик для этой системы, если приложен. */
data class Release(val version: String, val pageUrl: String, val assetUrl: String?)

object Updates {
    /** Откуда приходят обновления. Для закрытого репозитория GitHub без ключа отвечает 404. */
    val REPO: String = System.getProperty("stranitsa.updateRepo") ?: "Human9283/Stranitsa"

    /** Версия установленной программы; при запуске из исходников (gradle run) проверка не нужна — "0". */
    val current: String = if (System.getProperty("jpackage.app-path") != null || System.getProperty("jpackage.app-version") != null)
        System.getProperty("stranitsa.version") ?: System.getProperty("jpackage.app-version") ?: "0" else "0"

    fun fetchLatest(): Release? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Stranitsa")
        if (c.responseCode != 200) return null
        parse(c.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()

    fun parse(json: String): Release? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: "https://github.com/$REPO/releases/latest"
        val os = System.getProperty("os.name").lowercase()
        val ext = when { os.contains("win") -> listOf(".msi", ".exe"); os.contains("mac") -> listOf(".dmg"); else -> listOf(".deb", ".rpm") }
        val urls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val asset = ext.firstNotNullOfOrNull { e -> urls.firstOrNull { it.endsWith(e) } }
        return Release(tag.removePrefix("v"), page, asset)
    }

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun open(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
}
