package app.stranitsa

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import app.stranitsa.core.StCore
import app.stranitsa.core.diagClearCrash
import app.stranitsa.core.diagEvents
import app.stranitsa.core.diagInit
import app.stranitsa.core.diagLog
import app.stranitsa.core.diagPendingCrash
import app.stranitsa.core.diagRecordCrash
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/**
 * Отчёт об ошибке. Ничего не отправляется само: человек видит отчёт целиком и сам решает,
 * открыть ли страницу нового обращения на GitHub, скопировать или переслать его.
 *
 * В отчёт попадают версия, система, настройки и числа (сколько списков и строк), служебный
 * журнал ядра и описание сбоя. Тексты задач, названия списков, имена и адреса устройств — нет.
 */
object Diagnostics {

    /** Как можно раньше при запуске: папка для файла о сбое и перехват исключений. */
    fun install(ctx: Context) {
        runCatching { diagInit(ctx.filesDir.path) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { diagRecordCrash("Thread '${thread.name}'\n" + stackOf(e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Событие для журнала: только вид, без пользовательских данных. */
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

    /** Полный текст отчёта. [maxEvents] — сколько последних событий журнала включить. */
    fun report(ctx: Context, core: StCore?, crash: String?, maxEvents: Int = 200): String = buildString {
        val res = ctx.resources
        val cfg = res.configuration
        val dm = res.displayMetrics
        appendLine("Stranitsa ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) for Android, ${if (BuildConfig.DEBUG) "debug" else "release"}")
        appendLine("System: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Language: ${cfg.locales[0].toLanguageTag()}, system default ${Locale.getDefault().toLanguageTag()}")
        appendLine(
            "Screen: ${cfg.screenWidthDp}×${cfg.screenHeightDp} dp, density ${dm.densityDpi} dpi, " +
                "font scale ${cfg.fontScale}, system dark ${if (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "on" else "off"}",
        )
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

    /**
     * Ссылка на новое обращение с уже вставленным отчётом. Браузеры и GitHub не принимают
     * очень длинные ссылки, поэтому журнал укорачивается; null — даже короткий не влезает
     * (тогда отчёт копируется в буфер, а ссылка открывается без него).
     */
    fun issueUrl(intro: String, build: (maxEvents: Int) -> String): String? {
        for (n in listOf(200, 80, 30, 10, 0)) {
            val url = issueUrlWith(intro, build(n))
            if (url.length <= MAX_URL) return url
        }
        return null
    }

    fun issueUrlWith(intro: String, report: String?): String {
        val body = if (report == null) intro else "$intro\n\n<details><summary>Report</summary>\n\n```\n$report\n```\n</details>\n"
        return "https://github.com/${Updates.REPO}/issues/new?body=" + java.net.URLEncoder.encode(body, "UTF-8").replace("+", "%20")
    }

    /** С запасом: GitHub отвечает ошибкой на ссылки длиннее ~8 000 символов. */
    const val MAX_URL = 7500
}
