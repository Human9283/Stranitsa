package app.stranitsa.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stranitsa.R
import app.stranitsa.UpdateInstaller
import app.stranitsa.UpdateStep

/**
 * Плашка обновления: «Доступна версия» → «Загрузка…» → «Установить». Файл скачивается
 * внутри приложения; браузер нужен только если к выпуску не приложен APK.
 */
@Composable
fun UpdateBanner(
    step: UpdateStep,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ui.c
    val t = Ui.t
    val ctx = LocalContext.current
    var needAllow by remember(step.release.version) { mutableStateOf(false) }
    val v = step.release.version

    Hairline(Modifier.padding(top = 10.dp))
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp)) {
        when (step) {
            is UpdateStep.Available -> {
                Txt(stringResource(R.string.update_available, v), t.hint, c.ink)
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step.release.apkUrl != null) {
                        FilledAction(stringResource(R.string.update_do), onDownload)
                    } else {
                        // К выпуску не приложен APK (например, у форка): откроем его страницу.
                        FilledAction(stringResource(R.string.update_open_page), {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(step.release.pageUrl))) }
                            onDismiss()
                        })
                    }
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.break_later), onDismiss, color = c.ink)
                }
            }
            is UpdateStep.Downloading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.update_downloading, v), t.hint, c.ink, Modifier.weight(1f))
                    if (step.percent >= 0) Txt(stringResource(R.string.update_percent, step.percent), t.stats, c.muted)
                }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(c.rule)) {
                    val f = if (step.percent >= 0) step.percent / 100f else 0.15f
                    Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(4.dp).background(c.accent))
                }
                Row(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                    TextAction(stringResource(R.string.update_cancel), onCancel, color = c.ink)
                }
            }
            is UpdateStep.Ready -> {
                Txt(
                    if (needAllow) stringResource(R.string.update_allow) else stringResource(R.string.update_ready, v),
                    t.hint, c.ink,
                )
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledAction(stringResource(R.string.update_install), {
                        needAllow = !runCatching { UpdateInstaller.install(ctx, step.file) }.getOrDefault(true)
                    })
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.break_later), onDismiss, color = c.ink)
                }
            }
            is UpdateStep.Failed -> {
                Txt(stringResource(R.string.update_failed, step.message), t.hint, c.ink)
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledAction(stringResource(R.string.update_retry), onDownload)
                    Spacer(Modifier.width(4.dp))
                    TextAction(stringResource(R.string.update_close), onDismiss, color = c.ink)
                }
            }
        }
    }
}
