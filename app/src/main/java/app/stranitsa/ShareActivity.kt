package app.stranitsa

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stranitsa.core.joinSharedText
import app.stranitsa.core.splitSharedText
import app.stranitsa.ui.StTheme
import app.stranitsa.ui.Hairline
import app.stranitsa.ui.TextAction
import app.stranitsa.ui.FilledAction
import app.stranitsa.ui.Segmented
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import app.stranitsa.ui.ToggleRow
import app.stranitsa.ui.Txt
import app.stranitsa.ui.Ui
import app.stranitsa.ui.isDark

/**
 * «Поделиться → Страница» и «В Страницу» в меню выделенного текста.
 * Небольшая панель поверх текущего приложения: поправить текст и записать в конец списка.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = sharedText(intent)
        if (incoming.isBlank() && intent.action != StWidget.ACTION_QUICK_ADD) {
            finish(); return
        }
        val repo = repository
        setContent {
            val lib by repo.state.collectAsStateWithLifecycle()
            val settings = lib.settings
            val dark = isDark(settings.theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            StTheme(mode = settings.theme, pureBlack = settings.pureBlack, dynamic = settings.dynamicColor) {
                val defaultName = stringResource(R.string.list_default)
                ShareSheet(
                    initial = incoming,
                    lists = lib.notebooks.map { it.id to it.name.ifBlank { defaultName } },
                    initialList = lib.currentId,
                    onCancel = { finish() },
                    onAdd = { listId, tasks ->
                        val snap = repo.apply { c, now -> c.addTo(listId, tasks, now) }
                        val page = (snap.notebooks.firstOrNull { it.id == listId }?.lastPage ?: 0) + 1
                        Toast.makeText(
                            this,
                            resources.getQuantityString(R.plurals.share_added, tasks.size, tasks.size, page),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // Ядро уже записало файл (запись синхронная) — можно сразу закрываться.
                        finish()
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        repository.flush()
    }

    @Suppress("DEPRECATION")
    private fun sharedText(intent: Intent?): String {
        if (intent == null) return ""
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        // Браузеры присылают заголовок страницы в SUBJECT и ссылку в TEXT.
        return when {
            subject.isEmpty() -> text
            text.isEmpty() -> subject
            text.contains(subject) -> text
            else -> "$subject — $text"
        }
    }
}

@Composable
fun ShareSheet(
    initial: String,
    onCancel: () -> Unit,
    onAdd: (listId: String, tasks: List<String>) -> Unit,
    lists: List<Pair<String, String>> = emptyList(),
    initialList: String = lists.firstOrNull()?.first ?: "",
) {
    val c = Ui.c
    val t = Ui.t
    var text by rememberSaveable { mutableStateOf(initial) }
    val lines = splitSharedText(text)
    var split by rememberSaveable { mutableStateOf(true) }
    val multi = lines.size > 1
    val tasks = if (multi && split) lines else listOfNotNull(joinSharedText(text).ifBlank { null })
    val focus = remember { FocusRequester() }
    // Быстрая запись из виджета: текста ещё нет — сразу открываем клавиатуру.
    LaunchedEffect(Unit) { if (initial.isBlank()) focus.requestFocus() }
    var listId by rememberSaveable { mutableStateOf(initialList) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.bg)
                // поглощаем нажатия, чтобы панель не закрывалась от касания по ней
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            Box(
                Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.rule)
            )
            Txt(
                stringResource(R.string.share_title), t.label, c.muted,
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    .focusRequester(focus),
                textStyle = t.task.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (multi) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                    Hairline()
                    ToggleRow(
                        stringResource(R.string.share_split),
                        stringResource(R.string.share_split_hint, lines.size),
                        split,
                    ) { split = it }
                }
            }
            if (lists.size > 1) {
                Txt(stringResource(R.string.share_to_list), t.label, c.muted, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
                if (lists.size <= 3) {
                    Segmented(
                        lists.map { it.second }, lists.indexOfFirst { it.first == listId }.coerceAtLeast(0),
                        { listId = lists[it].first }, Modifier.padding(horizontal = 20.dp),
                    )
                } else {
                    // Списков много — строка с прокруткой, выбранный подчёркнут.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        lists.forEach { (id, name) ->
                            TextAction(
                                name, { listId = id },
                                color = if (id == listId) c.accent else c.ink,
                                style = if (id == listId) t.action.copy(textDecoration = TextDecoration.Underline) else t.action,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.act_cancel), onCancel)
                Spacer(Modifier.width(4.dp))
                FilledAction(stringResource(R.string.add), { onAdd(listId, tasks) }, enabled = tasks.isNotEmpty())
            }
        }
    }
}
