package app.stranitsa

import java.net.HttpURLConnection
import java.net.URL

/** Последний выпуск на GitHub. [apkUrl] — файл .apk, если он приложен к выпуску. */
data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Проверка обновлений через GitHub Releases (`/releases/latest`). Работает только для
 * публичного репозитория: у закрытого API без ключа отвечает 404 — тогда обновлений просто нет.
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

    /** Разбор ответа без JSON-библиотеки: нужны только тег, страница и ссылка на .apk. */
    fun parse(json: String): Release? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: "https://github.com/$REPO/releases/latest"
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)?.groupValues?.get(1)
        return Release(tag.removePrefix("v"), page, apk)
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
}
