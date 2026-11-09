package app.stranitsa.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.stranitsa.StActions
import app.stranitsa.R
import app.stranitsa.core.Snapshot

/** Несколько списков: Форстер советует отдельную тетрадь для каждого места — дом, работа. */
@Composable
fun ListsScreen(lib: Snapshot, vm: StActions, onBack: () -> Unit, onPicked: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    val defaultName = stringResource(R.string.list_default)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    SubScreen(stringResource(R.string.menu_lists), onBack, scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Txt(
                    stringResource(R.string.lists_lead), t.body, c.muted,
                    Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 22.dp),
                )
                Hairline()
            }
            items(lib.notebooks, key = { it.id }) { nb ->
                val name = nb.name.ifBlank { defaultName }
                val current = nb.id == lib.currentId
                val accent = c.accent
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current) c.tint else c.bg)
                ) {
                    when (nb.id) {
                        editing -> NameEditor(
                            initial = nb.name.ifBlank { defaultName },
                            onSave = { vm.renameList(nb.id, it); editing = null },
                            onCancel = { editing = null },
                            onDelete = if (lib.notebooks.size > 1) ({ editing = null; deleting = nb.id }) else null,
                        )
                        deleting -> Column(Modifier.padding(start = 20.dp, end = 14.dp, top = 14.dp)) {
                            Txt(stringResource(R.string.list_delete_confirm, name), t.sheetTitle.copy(fontSize = 19.sp, lineHeight = 26.sp))
                            Row(Modifier.padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledAction(stringResource(R.string.act_delete), { vm.deleteList(nb.id); deleting = null })
                                Spacer(Modifier.width(4.dp))
                                TextAction(stringResource(R.string.act_cancel), { deleting = null })
                            }
                        }
                        else -> Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp)
                                .clickable(role = Role.Button) { vm.switchList(nb.id); onPicked() }
                                .semantics { selected = current }
                                .padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                Txt(name, t.task.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal))
                                Txt(
                                    stringResource(R.string.list_meta, nb.activeTotal, pad2(nb.currentPage + 1), pad2(nb.lastPage + 1)),
                                    t.stats, c.muted,
                                )
                            }
                            if (current) GlyphIcon(Glyph.Check, c.accent)
                            TextAction(stringResource(R.string.act_edit), { editing = nb.id; deleting = null }, color = c.accent)
                        }
                    }
                }
                Hairline()
            }
            item { NewListRow { vm.addList(it); onPicked() } }
        }
    }
}

@Composable
private fun NameEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)?) {
    val c = Ui.c
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(start = 20.dp, end = 14.dp, top = 14.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { v -> if (v.text.contains('\n')) onSave(value.text) else value = v },
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 6.dp)
                .focusRequester(focus)
                .drawBehind {
                    val y = size.height + 3.dp.toPx()
                    drawLine(c.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                },
            textStyle = Ui.t.setting.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
        )
        Row(Modifier.padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledAction(stringResource(R.string.act_save), { onSave(value.text) }, enabled = value.text.isNotBlank())
            Spacer(Modifier.width(4.dp))
            TextAction(stringResource(R.string.act_cancel), onCancel)
            Spacer(Modifier.weight(1f))
            if (onDelete != null) TextAction(stringResource(R.string.act_delete), onDelete, color = c.muted)
        }
    }
}

@Composable
private fun NewListRow(onCreate: (String) -> Unit) {
    val c = Ui.c
    val t = Ui.t
    var text by rememberSaveable { mutableStateOf("") }
    val placeholder = stringResource(R.string.lists_new)
    fun submit() { if (text.isNotBlank()) { onCreate(text); text = "" } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(38.dp)) { GlyphIcon(Glyph.Plus, c.accent, 22.dp) }
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (text.isEmpty()) Txt(placeholder, t.setting, c.accent)
            BasicTextField(
                value = text,
                onValueChange = { v -> if (v.contains('\n')) submit() else text = v },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                textStyle = t.setting.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        if (text.isNotBlank()) TextAction(stringResource(R.string.list_create), ::submit, color = c.accent)
    }
}
