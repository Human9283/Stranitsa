package app.stranitsa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Установка скачанного APK. Система сама спросит подтверждение и проверит, что подпись
 * совпадает с установленной версией, так что подменить приложение чужим файлом нельзя.
 */
object UpdateInstaller {
    const val DIR = "updates"

    fun dir(ctx: Context) = File(ctx.cacheDir, DIR)

    /**
     * Запустить установку. Возвращает false, если сначала нужно разрешить установку из
     * этого приложения: тогда открывается нужная страница настроек, а файл остаётся.
     */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
