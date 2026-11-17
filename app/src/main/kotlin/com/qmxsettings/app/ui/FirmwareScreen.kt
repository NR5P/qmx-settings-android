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
import com.qmxsettings.app.firmware.FirmwareVersion
import com.qmxsettings.app.firmware.InstallCheck
import com.qmxsettings.app.firmware.InstallNote
import com.qmxsettings.app.radio.GuideStep
import com.qmxsettings.app.radio.Guides
import com.qmxsettings.app.radio.UpdateMethod
import java.io.File

@Composable
fun FirmwareScreen(vm: AppViewModel, go: (Tab) -> Unit) {
    val fw = vm.fw
    val radio = vm.myRadio
    val model = radio.model
    val qfu = model.qfu ?: com.qmxsettings.app.radio.QMX_QFU
    val ctx = LocalContext.current
    var confirmInstall by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<File?>(null) }
    var exporting by remember { mutableStateOf<File?>(null) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(model) { if (model.update == UpdateMethod.QFU && fw.releases.isEmpty() && fw.releasesError == null && !fw.busy) fw.loadReleases() }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Firmware for: ${model.label}" + (radio.pcbRev?.let { " · PCB Rev $it" } ?: ""), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { go(Tab.RADIO) }) { Text("Change") }
        }
        Guide(Guides.revision(model, radio.pcbRev))

        if (model.update == UpdateMethod.AVR) {
            Section("How to update the ${model.label}") { Guide(Guides.update(model, null)) }
            return@Column
        }

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
            fw.image?.let { Text("Selected: ${it.fileName}  (${it.data.size / 1024} KB)", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
            fw.releasesError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = if (showAll) fw.releases else fw.releases.take(6)
            for (r in list) ReleaseRow(r, selected = fw.image?.version == r.version, onPhone = fw.stored.any { it.version == r.version }, enabled = !fw.busy) { fw.download(r) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fw.releases.size > 6) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "All ${fw.releases.size} versions") }
                TextButton(onClick = { fw.loadReleases() }, enabled = !fw.busy) { Text("Reload list") }
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !fw.busy) { Text("Use a file…") }
            }
            Text(qfu.pages.joinToString(" and ") { it.removePrefix("https://") }.let { "Official firmware from $it. " } +
                (if (qfu.ext == "QMX") "Beta versions may have more bugs; stable is the safer choice. The latest stable version is kept on this phone automatically so you can update without internet."
                else "QRP Labs advises using the latest QDX firmware; it is kept on this phone automatically. Older QDX downloads have been removed from the website."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fw.stored.isNotEmpty()) {
                Text("On this phone", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                for (img in fw.stored) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(img.version, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if (fw.image?.fileName == img.fileName) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    else TextButton(onClick = { fw.select(img) }, enabled = !fw.busy) { Text("Select") }
                    TextButton(onClick = { fw.deleteStored(img) }, enabled = !fw.busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        // 2. Update mode
        Section("2. Put the ${model.label} in update mode") {
            Guide(Guides.update(model, vm.firmware?.let { FirmwareVersion.parse(it)?.toString() }))
            Text("Keep the phone connected to the radio's USB port.", style = MaterialTheme.typography.bodySmall)
            if (vm.connected && vm.mode == LinkMode.USB) Text("The CAT connection will drop when the radio restarts — that's expected.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { fw.scan() }, enabled = !fw.busy) { Text("Find radio") }
            fw.driveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            fw.drive?.let { d ->
                Text("Found: ${d.label}", fontWeight = FontWeight.SemiBold)
                Text("Firmware on radio: ${d.firmwareFile ?: "?"} · ${qfu.eepromName}: ${d.eepromSize ?: "?"} bytes", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (d.ext != null && d.ext != qfu.ext) Text("This is a ${d.ext} update drive, but My radio is set to ${model.label}. Change My radio on the Radio tab.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (d.firmwareFile == null || d.eepromSize == null) Text("This doesn't look like a ${model.label} update drive (files: ${d.files}). Nothing will be written to it.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // 3. Install / backup
        val ready = fw.drive?.let { it.ext == qfu.ext && it.firmwareFile != null && it.eepromSize != null } == true
        Section("3. Back up and install") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fw.backupEeprom() }, enabled = ready && !fw.busy) { Text("Back up ${qfu.eepromName}") }
                Button(onClick = { confirmInstall = true }, enabled = ready && fw.image != null && !fw.busy) { Text("Install ${fw.image?.version ?: ""}") }
            }
            Text("Installing always saves a backup of the radio's configuration first. The radio installs the new firmware as soon as the file is copied, then restarts by itself.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Guide(Guides.recovery(model))
        }

        // Backups
        Section("Configuration backups on this phone") {
            Text("${qfu.eepromName} holds the radio's whole configuration. Restoring writes a backup back to the radio in update mode, and only onto the same radio type and firmware version it came from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fw.backups.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            for (f in fw.backups) {
                val problem = fw.drive?.let { d -> InstallCheck.restoreProblem(f.name, f.length(), d.ext ?: "?", FirmwareVersion.parse(d.firmwareFile), d.eepromSize) }
                Column {
                    Text(f.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.length()} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { exporting = f; export.launch(f.name) }) { Text("Export") }
                        TextButton(onClick = { restoring = f }, enabled = ready && problem == null && !fw.busy) { Text("Restore…") }
                        TextButton(onClick = { fw.deleteBackup(f) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    if (ready && problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (confirmInstall) fw.image?.let { img ->
        val notes = fw.installNotes()
        val blocked = notes.any { it.level == InstallNote.Level.BLOCK }
        AlertDialog(onDismissRequest = { confirmInstall = false }, title = { Text("Install ${img.version} on the ${model.label}?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in notes) Text((if (n.level == InstallNote.Level.INFO) "• " else "⚠ ") + n.text, style = MaterialTheme.typography.bodySmall,
                    color = when (n.level) { InstallNote.Level.BLOCK -> MaterialTheme.colorScheme.error; InstallNote.Level.WARN -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.onSurface })
                Text("The app backs up ${qfu.eepromName}, then copies ${img.fileName} to the radio (${fw.drive?.firmwareFile ?: "?"} is installed now). " +
                    "Don't unplug the radio or phone until it restarts.")
                Guide(Guides.recovery(model))
            } },
            confirmButton = { TextButton(enabled = !blocked, onClick = { confirmInstall = false; fw.install() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } })
    }
    restoring?.let { f ->
        AlertDialog(onDismissRequest = { restoring = null }, title = { Text("Restore configuration?") },
            text = { Text("Replaces ALL of the radio's configuration with ${f.name}. The current ${qfu.eepromName} is backed up first. " +
                "Only restore a backup taken from this radio.") },
            confirmButton = { TextButton(onClick = { restoring = null; fw.restoreEeprom(f) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } })
    }
}

/** Numbered/bulleted steps from the manuals; warnings in the accent colour. */
@Composable
fun Guide(steps: List<GuideStep>) {
    for (s in steps) Text((if (s.warning) "⚠ " else "• ") + s.text, style = MaterialTheme.typography.bodySmall,
        color = if (s.warning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            content()
        }
    }
}

@Composable
private fun ReleaseRow(r: FirmwareRelease, selected: Boolean, onPhone: Boolean, enabled: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.version, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(r.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.beta) AssistChip(onClick = {}, label = { Text("Beta") })
                else if (r.stable) AssistChip(onClick = {}, label = { Text("Stable") })
                if (r.qmxPlusOnly) AssistChip(onClick = {}, label = { Text("QMX+ only") })
                if (r.factoryReset) AssistChip(onClick = {}, label = { Text("Resets settings") })
            }
            Text(r.notes.removePrefix("Consider this a Beta firmware version").removePrefix("Stable version").trim(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (selected) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        else TextButton(onClick = onDownload, enabled = enabled) { Text(if (onPhone) "Use" else "Get") }
    }
}
