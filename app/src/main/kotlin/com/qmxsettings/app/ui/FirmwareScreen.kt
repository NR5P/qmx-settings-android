package com.qmxsettings.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qmxsettings.app.firmware.FirmwareRelease
import java.io.File

@Composable
fun FirmwareScreen(vm: AppViewModel) {
    val fw = vm.fw
    val ctx = LocalContext.current
    var confirmInstall by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<File?>(null) }
    var exporting by remember { mutableStateOf<File?>(null) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (fw.releases.isEmpty() && fw.releasesError == null && !fw.busy) fw.loadReleases() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } ?: "firmware"
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            fw.useFile(name, bytes)
        } catch (e: Exception) { vm.message = "Couldn't read file: ${e.message}" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = exporting; exporting = null
        if (uri != null && f != null) try {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(f.readBytes()) }
            vm.message = "Saved ${f.name}"
        } catch (e: Exception) { vm.message = "Export failed: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fw.op?.let { o ->
            Column {
                if (o.fraction != null) LinearProgressIndicator(progress = { o.fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(o.text + (o.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.labelMedium)
            }
        }
        fw.lastResult?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }

        // 1. Choose firmware
        Section("1. Choose firmware") {
            val current = vm.firmware?.takeIf { vm.mode == LinkMode.USB }
            Text(if (current != null) "Radio is running $current." else "Connect on the Radio tab to see the radio's current version.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fw.image?.let { Text("Selected: ${it.version}  (${it.data.size / 1024} KB)", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
            fw.releasesError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = if (showAll) fw.releases else fw.releases.take(6)
            for (r in list) ReleaseRow(r, selected = fw.image?.version == r.version, enabled = !fw.busy) { fw.download(r) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fw.releases.size > 6) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "All ${fw.releases.size} versions") }
                TextButton(onClick = { fw.loadReleases() }, enabled = !fw.busy) { Text("Reload list") }
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !fw.busy) { Text("Use a file…") }
            }
            Text("Firmware comes from qrp-labs.com/qmx.html. Beta versions may have more bugs; stable is the safer choice.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 2. Update mode
        Section("2. Put the QMX in update mode") {
            Text("On the radio: long-press the left centre button (Menu), turn to “Update firmware”, press Select and confirm. " +
                "The screen goes dark and the QMX appears as a USB drive. Keep the phone connected to the radio's USB port. " +
                "(If the radio switches off instead, press the Volume button.)", style = MaterialTheme.typography.bodySmall)
            if (vm.connected && vm.mode == LinkMode.USB) Text("The CAT connection will drop when the radio restarts — that's expected.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { fw.scan() }, enabled = !fw.busy) { Text("Find radio") }
            fw.driveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            fw.drive?.let { d ->
                Text("Found: ${d.label}", fontWeight = FontWeight.SemiBold)
                Text("Firmware on radio: ${d.firmwareFile ?: "?"} · EEPROM: ${d.eepromSize ?: "?"} bytes", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (d.firmwareFile == null || d.eepromSize == null) Text("This doesn't look like a QMX update drive (files: ${d.files}). Nothing will be written to it.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // 3. Install / backup
        val ready = fw.drive?.let { it.firmwareFile != null && it.eepromSize != null } == true
        Section("3. Back up and install") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fw.backupEeprom() }, enabled = ready && !fw.busy) { Text("Back up EEPROM") }
                Button(onClick = { confirmInstall = true }, enabled = ready && fw.image != null && !fw.busy) { Text("Install ${fw.image?.version ?: ""}") }
            }
            Text("Installing always saves an EEPROM backup first. The radio installs the new firmware as soon as the file is copied, then restarts by itself.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Backups
        Section("EEPROM backups on this phone") {
            Text("The EEPROM file holds the radio's whole configuration. Restoring writes a backup back to the radio in update mode.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fw.backups.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            for (f in fw.backups) {
                Column {
                    Text(f.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.length()} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { exporting = f; export.launch(f.name) }) { Text("Export") }
                        TextButton(onClick = { restoring = f }, enabled = ready && !fw.busy) { Text("Restore…") }
                        TextButton(onClick = { fw.deleteBackup(f) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (confirmInstall) fw.image?.let { img ->
        AlertDialog(onDismissRequest = { confirmInstall = false }, title = { Text("Install ${img.version}?") },
            text = { Text("The app backs up the EEPROM, then copies ${img.fileName} to the radio (${fw.drive?.firmwareFile ?: "?"} is installed now). " +
                "Don't unplug the radio or phone until it restarts.\n\nIf anything goes wrong, the radio can be put back in update mode and the install repeated.") },
            confirmButton = { TextButton(onClick = { confirmInstall = false; fw.install() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } })
    }
    restoring?.let { f ->
        AlertDialog(onDismissRequest = { restoring = null }, title = { Text("Restore EEPROM?") },
            text = { Text("Replaces ALL of the radio's configuration with ${f.name}. The current EEPROM is backed up first. " +
                "Only restore a backup taken from this radio; a backup from a different firmware version may not match its settings layout.") },
            confirmButton = { TextButton(onClick = { restoring = null; fw.restoreEeprom(f) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            content()
        }
    }
}

@Composable
private fun ReleaseRow(r: FirmwareRelease, selected: Boolean, enabled: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.version, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(r.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.beta) AssistChip(onClick = {}, label = { Text("Beta") })
                else if (r.stable) AssistChip(onClick = {}, label = { Text("Stable") })
            }
            Text(r.notes.removePrefix("Consider this a Beta firmware version").removePrefix("Stable version").trim(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (selected) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        else TextButton(onClick = onDownload, enabled = enabled) { Text("Get") }
    }
}
