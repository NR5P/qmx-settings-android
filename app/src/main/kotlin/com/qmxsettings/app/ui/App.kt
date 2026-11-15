package com.qmxsettings.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qmxsettings.app.cat.RadioFamily

private val colors = darkColorScheme(
    primary = Color(0xFF7FD3C9),
    secondary = Color(0xFFFFB547),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C252C),
)

enum class Tab(val label: String, val icon: ImageVector) {
    RADIO("Radio", Icons.Filled.Cable),
    MENU("Settings", Icons.Filled.ViewList),
    CONTROLS("Controls", Icons.Filled.Tune),
    PROFILES("Profiles", Icons.Filled.Bookmarks),
    CONSOLE("Console", Icons.Filled.Terminal),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(Tab.RADIO) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.message) {
            vm.message?.let { snack.showSnackbar(it); vm.message = null }
        }
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("QMX Settings", fontWeight = FontWeight.SemiBold)
                        Text(statusLine(vm), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    for (t in Tab.entries) NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                vm.progress?.let { p ->
                    if (p.total != null && p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(p.phase, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                when (tab) {
                    Tab.RADIO -> RadioScreen(vm) { tab = it }
                    Tab.MENU -> MenuScreen(vm)
                    Tab.CONTROLS -> ControlsScreen(vm)
                    Tab.PROFILES -> ProfilesScreen(vm)
                    Tab.CONSOLE -> ConsoleScreen(vm)
                }
            }
        }
    }
}

private fun statusLine(vm: AppViewModel): String = when (val c = vm.conn) {
    ConnState.Disconnected -> "Not connected"
    ConnState.Connecting -> "Connecting…"
    is ConnState.Failed -> "Error: ${c.message}"
    ConnState.Connected -> buildString {
        append(if (vm.mode == LinkMode.DEMO) "DEMO (simulated QMX)" else vm.family.label)
        vm.firmware?.let { append(" · fw $it") }
        if (vm.menuManager) append(" · Menu Manager")
    }
}

@Composable
fun RadioScreen(vm: AppViewModel, go: (Tab) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (vm.conn == ConnState.Connected || vm.conn == ConnState.Connecting) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(statusLine(vm), style = MaterialTheme.typography.titleMedium)
                    if (vm.conn == ConnState.Connected) {
                        if (vm.menuManager) {
                            Text("${vm.tree?.settingCount() ?: 0} menu settings read.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { go(Tab.MENU) }) { Text("View settings") }
                                OutlinedButton(onClick = { vm.loadMenu() }, enabled = !vm.busy) { Text("Re-read all") }
                            }
                        } else {
                            Text("This radio didn't answer Menu Manager (MM) queries. Configuration items available over CAT are on the Controls tab. QMX needs firmware 1_02_006 or newer for full menu access.")
                            Button(onClick = { go(Tab.CONTROLS) }) { Text("Open controls") }
                        }
                    }
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                }
            }
        }

        (vm.conn as? ConnState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }

        Text("USB", style = MaterialTheme.typography.titleMedium)
        Text("Plug the radio into the phone's USB-C port (OTG). Nothing is sent to the radio until you tap Connect. Only one app at a time can use the radio's USB port.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.ports.isEmpty()) {
            Text("No USB serial radios found.", style = MaterialTheme.typography.bodyMedium)
        }
        for (p in vm.ports) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Usb, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(p.id + if (p.looksLikeQrpLabs) " · QRP Labs" else "", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { vm.connectUsb(p) }, enabled = vm.conn != ConnState.Connecting) { Text("Connect") }
                }
            }
        }
        OutlinedButton(onClick = { vm.refreshPorts() }) { Text("Rescan USB") }

        Spacer(Modifier.height(8.dp))
        Text("Radio type", style = MaterialTheme.typography.titleMedium)
        Text("Detected automatically from the firmware version; override here if detection fails.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in RadioFamily.entries) FilterChip(selected = vm.family == f, onClick = { vm.family = f }, label = { Text(f.label) })
        }

        Spacer(Modifier.height(8.dp))
        Text("Demo", style = MaterialTheme.typography.titleMedium)
        Text("Explore the app with a simulated QMX built from the operating manual. No USB is used.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.connectDemo() }) { Text("Start demo radio") }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apply edits immediately")
                Text("Sends MU; after each change so it takes effect even when the radio's \"MM Effect\" is On demand.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.reloadAfterEdit, onCheckedChange = { vm.reloadAfterEdit = it })
        }
        if (vm.busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
}
