package com.qmxsettings.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qmxsettings.app.cat.CatParam
import com.qmxsettings.app.cat.ParamKind

@Composable
fun ControlsScreen(vm: AppViewModel) {
    if (!vm.connected) return NotConnected()
    var editing by remember { mutableStateOf<CatParam?>(null) }
    val groups = vm.params.groupBy { it.group }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.readCat() }, enabled = !vm.busy) { Text("Read all") }
            OutlinedButton(onClick = { vm.syncClock() }, enabled = !vm.busy) { Text("Set clock to UTC") }
        }
        Text("Two-letter and Q CAT commands. These change the current session only and are not saved in EEPROM (use Settings for permanent changes).",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            for ((g, list) in groups) {
                item(key = "h:$g") {
                    Text(g, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                }
                items(list, key = { it.code }) { p ->
                    val v = vm.catValues[p.code]
                    val missing = p.code in vm.unsupported
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !p.readOnly && !missing) { editing = p }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label)
                            Text("${p.code} · ${p.help}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (missing) "not in this firmware" else p.display(v), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 170.dp),
                            color = if (p.readOnly || missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    editing?.let { p -> CatEditDialog(vm, p) { editing = null } }
}

@Composable
private fun CatEditDialog(vm: AppViewModel, p: CatParam, onDismiss: () -> Unit) {
    val cur = vm.catValues[p.code]
    val normalized = cur?.let { c -> p.options.firstOrNull { it.first == c || it.first == c.trimStart('0').ifEmpty { "0" } }?.first ?: c.trimStart('0').ifEmpty { "0" } }
    var text by remember { mutableStateOf(normalized ?: "") }
    val err = p.validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.help, style = MaterialTheme.typography.bodySmall)
                Text("Current: ${p.display(cur)}", fontFamily = FontFamily.Monospace)
                if (p.kind == ParamKind.LIST) {
                    for ((value, label) in p.options) Row(
                        Modifier.fillMaxWidth().selectable(selected = text == value, onClick = { text = value }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = text == value, onClick = { text = value })
                        Text(label)
                    }
                } else {
                    OutlinedTextField(text, { text = it }, singleLine = true, isError = err != null,
                        supportingText = { Text(err ?: listOfNotNull(p.min?.let { "min $it" }, p.max?.let { "max $it" }, p.unit.ifEmpty { null }).joinToString(" · ")) },
                        keyboardOptions = KeyboardOptions(keyboardType = if (p.kind == ParamKind.TEXT) KeyboardType.Text else KeyboardType.Number))
                }
                Text("Command: ${p.setCommand(text)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                if (p.storedInEeprom) Text("Saved to EEPROM.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = err == null && !vm.busy, onClick = { vm.writeCat(p, text); onDismiss() }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
