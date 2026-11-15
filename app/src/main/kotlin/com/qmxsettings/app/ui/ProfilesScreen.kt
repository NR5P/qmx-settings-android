package com.qmxsettings.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qmxsettings.app.profiles.PlannedChange
import com.qmxsettings.app.profiles.Profile
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfilesScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportJson(p).toByteArray()) }
            vm.message = "Exported \"${p.name}\""
        } catch (e: Exception) { vm.message = "Export failed: ${e.message}" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openInputStream(uri)?.use { vm.importJson(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (e: Exception) { vm.message = "Import failed: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profiles remember a set of radio settings (e.g. POTA CW, FT8 home, SSB portable) so you can switch with one tap. Applying shows exactly what will change first.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }, enabled = vm.connected && !vm.busy) { Text("Save current…") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
        }
        if (vm.profiles.isEmpty()) Text("No profiles yet.", Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.profiles, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        if (p.notes.isNotBlank()) Text(p.notes, style = MaterialTheme.typography.bodySmall)
                        Text("${p.entries.size} settings · ${p.radio} ${p.firmware} · ${DateFormat.getDateInstance().format(Date(p.createdAt))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { applying = p }, enabled = vm.connected && !vm.busy) { Text("Apply…") }
                            TextButton(onClick = { renaming = p }) { Text("Rename") }
                            TextButton(onClick = { exporting = p; exportLauncher.launch("${p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.json") }) { Text("Export") }
                            TextButton(onClick = { deleting = p }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (creating) CreateProfileDialog(vm) { creating = false }
    applying?.let { p -> ApplyDialog(vm, p) { applying = null } }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This removes the saved profile from this phone. Radio settings are not changed.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(p); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var notes by remember(p.id) { mutableStateOf(p.notes) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Edit profile") },
            text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }) } },
            confirmButton = { TextButton(onClick = { vm.renameProfile(p, name, notes); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}

@Composable
private fun CreateProfileDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val sections = vm.sections()
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    // Frequency presets and Band config hold per-radio calibration-ish data; off by default.
    var chosen by remember { mutableStateOf(sections.map { it.key }.filterNot { it.startsWith("Band config") || it.startsWith("Hardware") }.toSet()) }
    val catChoices = vm.params.filter { !it.readOnly && vm.catValues[it.code] != null }
    var cat by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save current settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. POTA CW") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") })
                if (sections.isNotEmpty()) {
                    Text("Menu sections (saved to EEPROM when applied)", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    for (s in sections) CheckRow(s.name + "  (${s.settings().size})", s.key in chosen) {
                        chosen = if (it) chosen + s.key else chosen - s.key
                    }
                }
                if (catChoices.isNotEmpty()) {
                    Text("Session controls (read on Controls tab)", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    for (p in catChoices) CheckRow("${p.label}: ${p.display(vm.catValues[p.code])}", p.code in cat) {
                        cat = if (it) cat + p.code else cat - p.code
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (chosen.isNotEmpty() || cat.isNotEmpty()), onClick = {
                vm.createProfile(name.trim(), notes.trim(), chosen, cat); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApplyDialog(vm: AppViewModel, p: Profile, onDismiss: () -> Unit) {
    val plan = remember(p.id, vm.treeVersion) { vm.plan(p) }
    val changes = plan.filter { it.found && it.changes }
    val missing = plan.filter { !it.found }
    var selected by remember(plan) { mutableStateOf(changes.map { it.entry.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply \"${p.name}\"") },
        text = {
            Column {
                Text(if (changes.isEmpty()) "The radio already matches this profile." else "${changes.size} of ${plan.size} settings differ from the radio:",
                    style = MaterialTheme.typography.bodyMedium)
                if (missing.isNotEmpty()) Text("${missing.size} setting(s) don't exist on this radio/firmware and will be skipped.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(changes, key = { it.entry.id }) { c: PlannedChange ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.entry.id in selected, { on -> selected = if (on) selected + c.entry.id else selected - c.entry.id })
                            Column {
                                Text(c.entry.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${c.current ?: "?"}  →  ${c.entry.value}", fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty() && !vm.busy, onClick = {
                vm.apply(changes.filter { it.entry.id in selected }); onDismiss()
            }) { Text("Write ${selected.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
