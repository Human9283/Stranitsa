package app.stranitsa

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Последний выпуск на GitHub. [apkUrl] — файл .apk, если он приложен к выпуску;
 * [apkSha256] и [apkSize] GitHub сообщает сам — по ним проверяется скачанный файл.
 */
data class Release(
    val version: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSha256: String? = null,
    val apkSize: Long? = null,
)

/** Что показывает плашка обновления на главном экране. */
sealed interface UpdateStep {
    val release: Release

    /** Вышла новая версия. */
    data class Available(override val release: Release) : UpdateStep

    /** Идёт загрузка; [percent] = -1, если размер неизвестен. */
    data class Downloading(override val release: Release, val percent: Int) : UpdateStep

    /** Файл скачан и проверен — можно ставить. */
    data class Ready(override val release: Release, val file: File) : UpdateStep

    data class Failed(override val release: Release, val message: String) : UpdateStep
}

/**
 * Проверка обновлений через GitHub Releases (`/releases/latest`) и загрузка APK внутри
 * приложения. Работает только для публичного репозитория: у закрытого API без ключа
 * отвечает 404 — тогда обновлений просто нет.
 */
object Updates {
    const val REPO = BuildConfig.UPDATE_REPO

    fun fetchLatest(): Release? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Stranitsa")
        if (c.responseCode != 200) return null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        parse(body)
    }.getOrNull()

    /** Разбор ответа без JSON-библиотеки: нужны тег, страница и сведения о файле .apk. */
    fun parse(json: String): Release? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: "https://github.com/$REPO/releases/latest"
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)
        val url = apk?.groupValues?.get(1)
        // В описании файла «size» и «digest» стоят ДО ссылки — после ссылки предыдущего файла.
        val before = apk?.let { m ->
            val head = json.substring(0, m.range.first)
            head.substring(head.lastIndexOf("\"browser_download_url\"").coerceAtLeast(0))
        }
        val sha = before?.let { Regex("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"").findAll(it).lastOrNull()?.groupValues?.get(1) }
        val size = before?.let { Regex("\"size\"\\s*:\\s*(\\d+)").findAll(it).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() }
        return Release(tag.removePrefix("v"), page, url, sha?.lowercase(), size)
    }

    /** «1.10.0» новее «1.9»? Сравнение по числам, недостающие части — нули. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Имя файла в кэше: по версии, чтобы старый файл не принимался за новый. */
    fun fileFor(dir: File, release: Release) = File(dir, "Stranitsa-${release.version}.apk")

    /** Удалить скачанное, кроме файла этой версии (если он ещё нужен). */
    fun cleanOld(dir: File, keep: Release?) {
        dir.listFiles()?.forEach { if (keep == null || it != fileFor(dir, keep)) it.delete() }
    }

    /**
     * Скачать APK в [dir]. Файл появляется под своим именем только после проверки размера и
     * SHA-256 (если GitHub их сообщил), поэтому существующий файл всегда целый.
     * [onProgress] получает (скачано, всего); всего = -1, если размер неизвестен.
     * [cancelled] проверяется между кусками.
     */
    fun download(
        release: Release,
        dir: File,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val url = release.apkUrl ?: throw IOException("no file")
        require(url.startsWith("https://")) { "only https" }
        dir.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Stranitsa")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize ?: -1
            return c.inputStream.use { copyVerified(it, total, fileFor(dir, release), release.apkSha256, release.apkSize, cancelled, onProgress) }
        } finally {
            c.disconnect()
        }
    }

    /** Записать поток в [dest] через временный файл и проверить размер и хеш. Вынесено ради тестов. */
    fun copyVerified(
        input: InputStream,
        total: Long,
        dest: File,
        sha256: String?,
        expectedSize: Long?,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
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
                    if (done - last >= 128 * 1024 || done == total) { onProgress(done, total); last = done }
                }
            }
            onProgress(done, total)
            if (expectedSize != null && done != expectedSize) throw IOException("size $done, expected $expectedSize")
            if (total > 0 && done != total) throw IOException("incomplete download")
            if (sha256 != null) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(sha256, ignoreCase = true)) throw IOException("checksum mismatch")
            }
            if (!tmp.renameTo(dest)) throw IOException("cannot save the file")
            return dest
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }
}
