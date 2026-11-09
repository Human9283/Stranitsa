package app.stranitsa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stranitsa.Diagnostics
import app.stranitsa.R

/**
 * Отчёт об ошибке: человек видит текст целиком и сам выбирает — открыть обращение на GitHub
 * с уже вставленным отчётом, скопировать или переслать. Само ничего не отправляется.
 *
 * [build] собирает отчёт с не более чем N последними событиями журнала.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(build: (maxEvents: Int) -> String, onSent: () -> Unit, onBack: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val ctx = LocalContext.current
    val report = remember { build(200) }
    var status by remember { mutableStateOf<String?>(null) }
    val intro = stringResource(R.string.report_intro)
    val copied = stringResource(R.string.report_copied)
    val pasteHint = stringResource(R.string.report_pasted_hint)

    SubScreen(stringResource(R.string.report_title), onBack) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Txt(stringResource(R.string.report_lead), t.body, c.muted, Modifier.padding(top = 4.dp, bottom = 12.dp))
            // При крупном шрифте три кнопки не помещаются в строку — переносятся.
            FlowRow(verticalArrangement = Arrangement.Center) {
                FilledAction(stringResource(R.string.report_open), {
                    val url = Diagnostics.issueUrl(intro, build)
                        ?: Diagnostics.issueUrlWith(intro, null).also { copy(ctx, report); status = pasteHint }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    onSent()
                })
                Spacer(Modifier.width(4.dp))
                TextAction(stringResource(R.string.report_copy), { copy(ctx, report); status = copied; onSent() }, color = c.accent)
                TextAction(stringResource(R.string.report_share), {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                    onSent()
                }, color = c.accent)
            }
            Txt(stringResource(R.string.report_github_hint), t.stats, c.muted, Modifier.padding(top = 6.dp))
            status?.let { Txt(it, t.stats, c.ink, Modifier.padding(top = 6.dp)) }
            Hairline(Modifier.padding(top = 14.dp))
            // Строки стека длинные: не переносим, а прокручиваем вбок — так их легче читать.
            SelectionContainer {
                Txt(
                    report,
                    t.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    c.ink,
                    Modifier.padding(top = 12.dp, bottom = 32.dp).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Stranitsa report", text))
    }
}
