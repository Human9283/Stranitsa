package app.stranitsa

import android.app.Application
import android.content.Context
import app.stranitsa.core.StCore
import java.io.File

class StApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Раньше всего остального: сбой при открытии данных тоже должен попасть в отчёт.
        Diagnostics.install(this)
        Diagnostics.log("app: start ${BuildConfig.VERSION_NAME}")
    }

    val repository: StRepository by lazy {
        // Ядро само переводит файлы старых версий (1.0–1.7) в новый формат.
        val core = StCore(File(filesDir, DATA_FILE).path, System.currentTimeMillis())
        StRepository(core, onBackupFlag = { setBackupAllowed(this, it) }, onChanged = { StWidget.updateAll(this, it) })
    }

    companion object {
        const val DATA_FILE = "stranitsa.json"
        private const val PREFS = "backup"
        private const val KEY_ALLOWED = "allowed"

        /**
         * Флаг для агента резервного копирования. Агент запускается в «урезанном» процессе без
         * нашего Application, поэтому читает отдельный маленький файл настроек, а не весь список.
         */
        fun setBackupAllowed(ctx: Context, allowed: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALLOWED, allowed).apply()
        }

        fun isBackupAllowed(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALLOWED, false)
    }
}

val Context.repository: StRepository get() = (applicationContext as StApplication).repository
