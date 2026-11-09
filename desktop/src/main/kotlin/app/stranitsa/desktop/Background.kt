package app.stranitsa.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.stranitsa.core.SyncHost
import app.stranitsa.core.syncKnownNearby
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
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
data class Release(
    val version: String,
    val pageUrl: String,
    val assetUrl: String?,
    val assetSha256: String? = null,
    val assetSize: Long? = null,
)

/** Что показывает плашка обновления. */
sealed interface UpdateStep {
    val release: Release
    data class Available(override val release: Release) : UpdateStep
    /** [percent] = -1, если размер неизвестен. */
    data class Downloading(override val release: Release, val percent: Int) : UpdateStep
    data class Ready(override val release: Release, val file: File) : UpdateStep
    data class Failed(override val release: Release, val message: String) : UpdateStep
}

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

    fun parse(json: String, os: String = System.getProperty("os.name").lowercase()): Release? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: "https://github.com/$REPO/releases/latest"
        val ext = when { os.contains("win") -> listOf(".msi", ".exe"); os.contains("mac") -> listOf(".dmg"); else -> listOf(".deb", ".rpm") }
        val urls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).toList()
        val m = ext.firstNotNullOfOrNull { e -> urls.firstOrNull { it.groupValues[1].endsWith(e) } }
        // В описании файла «size» и «digest» стоят ДО ссылки — после ссылки предыдущего файла.
        val before = m?.let {
            val head = json.substring(0, it.range.first)
            head.substring(head.lastIndexOf("\"browser_download_url\"").coerceAtLeast(0))
        }
        val sha = before?.let { Regex("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"").findAll(it).lastOrNull()?.groupValues?.get(1) }
        val size = before?.let { Regex("\"size\"\\s*:\\s*(\\d+)").findAll(it).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() }
        return Release(tag.removePrefix("v"), page, m?.groupValues?.get(1), sha?.lowercase(), size)
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

    /** Папка для скачанных установщиков. */
    fun dir(): File = File(System.getProperty("java.io.tmpdir"), "stranitsa-update")

    /** Имя файла по версии и расширению: старый файл не принимается за новый. */
    fun fileFor(dir: File, r: Release): File =
        File(dir, "Stranitsa-${r.version}" + (r.assetUrl?.substringAfterLast('.')?.let { ".$it" } ?: ""))

    fun cleanOld(dir: File, keep: Release?) {
        dir.listFiles()?.forEach { if (keep == null || it != fileFor(dir, keep)) it.delete() }
    }

    /**
     * Скачать установщик. Файл появляется под своим именем только после проверки размера и
     * SHA-256 (если GitHub их сообщил). [onProgress] получает (скачано, всего); всего = -1, если неизвестно.
     */
    fun download(r: Release, dir: File, cancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val url = r.assetUrl ?: throw IOException("no file")
        require(url.startsWith("https://")) { "only https" }
        dir.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Stranitsa")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.assetSize ?: -1
            return c.inputStream.use { copyVerified(it, total, fileFor(dir, r), r.assetSha256, r.assetSize, cancelled, onProgress) }
        } finally {
            c.disconnect()
        }
    }

    fun copyVerified(
        input: InputStream, total: Long, dest: File, sha256: String?, expectedSize: Long?,
        cancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val tmp = File(dest.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var last = -1L
                while (true) {
                    if (cancelled()) throw IOException("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done - last >= 256 * 1024 || done == total) { onProgress(done, total); last = done }
                }
            }
            onProgress(done, total)
            if (expectedSize != null && done != expectedSize) throw IOException("size $done, expected $expectedSize")
            if (total > 0 && done != total) throw IOException("incomplete download")
            if (sha256 != null && !md.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)) throw IOException("checksum mismatch")
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw IOException("cannot save the file") }
            return dest
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }
}
