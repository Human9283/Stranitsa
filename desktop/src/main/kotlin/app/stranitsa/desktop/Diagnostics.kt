package app.stranitsa.desktop

import app.stranitsa.core.StCore
import app.stranitsa.core.diagClearCrash
import app.stranitsa.core.diagEvents
import app.stranitsa.core.diagInit
import app.stranitsa.core.diagLog
import app.stranitsa.core.diagPendingCrash
import app.stranitsa.core.diagRecordCrash
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.util.Locale

/**
 * Отчёт об ошибке. Ничего не отправляется само: человек видит отчёт целиком и сам решает,
 * открыть ли обращение на GitHub, скопировать или сохранить его в файл.
 * Тексты задач, названия списков, имена и адреса устройств в отчёт не попадают.
 */
object Diagnostics {
    /** Версия программы: из сборки, при запуске из исходников — «dev». */
    val version: String = System.getProperty("stranitsa.version") ?: "dev"

    /** Как можно раньше при запуске: папка для файла о сбое и перехват исключений. */
    fun install(dir: File) {
        runCatching { diagInit(dir.path) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            recordCrash(thread.name, e)
            previous?.uncaughtException(thread, e) ?: e.printStackTrace()
        }
    }

    fun recordCrash(thread: String, e: Throwable) {
        runCatching { diagRecordCrash("Thread '$thread'\n" + stackOf(e)) }
    }

    fun log(event: String) { runCatching { diagLog(event) } }
    fun pendingCrash(): String? = runCatching { diagPendingCrash() }.getOrNull()
    fun clearCrash() { runCatching { diagClearCrash() } }

    /** Стек без лишнего: не больше 40 строк на исключение и 3 вложенных причин. */
    fun stackOf(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val out = mutableListOf<String>()
        var blocks = 0
        var inBlock = 0
        for (line in sw.toString().lines()) {
            if (line.isBlank()) continue
            if (out.isEmpty() || line.startsWith("Caused by")) {
                blocks++; inBlock = 0
                if (blocks > 4) break
            }
            if (inBlock < 40) out += line else if (inBlock == 40) out += "\t..."
            inBlock++
        }
        return out.joinToString("\n")
    }

    fun report(core: StCore?, crash: String?, maxEvents: Int = 200): String = buildString {
        appendLine("Stranitsa $version for desktop")
        appendLine("System: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), Java ${System.getProperty("java.version")}")
        appendLine("Language: ${Locale.getDefault().toLanguageTag()}")
        runCatching {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            if (!ge.isHeadlessInstance) {
                val screens = ge.screenDevices.joinToString { d ->
                    val m = d.displayMode
                    val scale = d.defaultConfiguration.defaultTransform.scaleX
                    "${m.width}×${m.height} @${"%.2f".format(Locale.ROOT, scale)}"
                }
                appendLine("Screens: $screens")
            }
        }
        if (core != null) {
            appendLine()
            runCatching { core.diagnostics() }.getOrDefault(emptyList()).forEach { appendLine(it) }
        }
        if (!crash.isNullOrBlank()) {
            appendLine()
            appendLine("Crash on a previous run:")
            appendLine(crash.trimEnd())
        }
        val events = runCatching { diagEvents() }.getOrDefault(emptyList()).takeLast(maxEvents)
        if (events.isNotEmpty()) {
            appendLine()
            appendLine("Events (UTC):")
            events.forEach { appendLine(it) }
        }
    }.trimEnd()

    /** Ссылка на новое обращение с отчётом; журнал укорачивается, чтобы ссылка не была слишком длинной. */
    fun issueUrl(intro: String, build: (maxEvents: Int) -> String): String? {
        for (n in listOf(200, 80, 30, 10, 0)) {
            val url = issueUrlWith(intro, build(n))
            if (url.length <= MAX_URL) return url
        }
        return null
    }

    fun issueUrlWith(intro: String, report: String?): String {
        val body = if (report == null) intro else "$intro\n\n<details><summary>Report</summary>\n\n```\n$report\n```\n</details>\n"
        return "https://github.com/${Updates.REPO}/issues/new?body=" + URLEncoder.encode(body, "UTF-8").replace("+", "%20")
    }

    const val MAX_URL = 7500
}
