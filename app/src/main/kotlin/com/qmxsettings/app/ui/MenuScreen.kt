package com.qmxsettings.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.qmxsettings.app.menu.MenuNode
import com.qmxsettings.app.menu.MenuType
import com.qmxsettings.app.menu.validateValue

@Composable
fun NotConnected(text: String = "Connect to a radio (or start the demo) on the Radio tab.") {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MenuScreen(vm: AppViewModel) {
    val tree = vm.tree
    if (!vm.connected) return NotConnected()
    if (tree == null) return NotConnected(if (vm.menuManager) "Reading menu…" else "Menu Manager not available on this radio — see Controls.")

    val stack = remember { mutableStateListOf<MenuNode>() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<MenuNode, Int?>?>(null) }
    var column by remember { mutableIntStateOf(0) }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    val current = stack.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search all settings") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) IconButton(onClick = { stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(current?.key ?: "Configuration menu", Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { if (current != null) vm.refresh(current) else vm.loadMenu() }, enabled = !vm.busy) {
                    Icon(Icons.Filled.Refresh, "Re-read")
                }
            }
        }
        HorizontalDivider()

        val grid = current?.columns
        if (query.isBlank() && grid != null && current != null) {
            // Band config.: pick a column (band), then list the rows for it.
            val bandNames = current.children.firstOrNull()?.values
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until grid) {
                    val label = bandNames?.getOrNull(c)?.takeIf { it.isNotBlank() && it != "0" }?.let { "${it}m" } ?: "#${c + 1}"
                    FilterChip(selected = column == c, onClick = { column = c }, label = { Text(label) })
                }
            }
        }

        val rows: List<MenuNode> = if (query.isNotBlank()) {
            tree.all().filter { it.hasValue && it.key.contains(query, ignoreCase = true) }.toList()
        } else current?.children ?: tree.roots

        key(current?.indexPath, query.isBlank()) { LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.indexPath }) { n ->
                val col = if (n.isGridRow) column.coerceIn(0, (n.gridColumns ?: 1) - 1) else null
                MenuRow(n, col, { vm.treeVersion }, vm.help[n.key.lowercase()], showPath = query.isNotBlank()) {
                    when {
                        n.isContainer -> { stack.add(n); query = "" }
                        n.type.editable -> editing = n to col
                        else -> {}
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        } }
    }

    editing?.let { (node, col) ->
        EditDialog(vm, node, col, onDismiss = { editing = null })
    }
}

@Composable
private fun MenuRow(n: MenuNode, column: Int?, version: () -> Int, help: String?, showPath: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = n.isContainer || n.type.editable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (showPath) n.key else n.name, style = MaterialTheme.typography.bodyLarge)
            val sub = when (n.type) {
                MenuType.ACTION -> "Application — run it on the radio"
                MenuType.SUBMENU -> if (n.columns != null) "Table · ${n.columns} columns" else null
                else -> help
            }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        if (n.isContainer) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        } else if (n.hasValue) {
            version() // subscribe this row to value changes (nodes are plain objects)
            val v = if (n.isGridRow) n.displayValue(column) else n.displayValue()
            Text(v, fontFamily = FontFamily.Monospace,
                color = if (n.type.editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
    }
}

@Composable
fun EditDialog(vm: AppViewModel, node: MenuNode, column: Int?, onDismiss: () -> Unit) {
    val lists = vm.tree?.lists ?: emptyMap()
    val current = if (column != null) node.values?.getOrNull(column) else node.value
    var text by remember { mutableStateOf(current ?: "") }
    val options = node.listType?.let { lists[it] }
    val error = validateValue(node, text, lists)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name + (column?.let { " [${it + 1}]" } ?: "")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.help[node.key.lowercase()]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Current: ${current ?: "unknown"}", fontFamily = FontFamily.Monospace)
                if (options != null) {
                    Column(Modifier.heightIn(max = 360.dp)) {
                        for (o in options) Row(
                            Modifier.fillMaxWidth().selectable(selected = o.equals(text, true), onClick = { text = o }).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = o.equals(text, true), onClick = { text = o })
                            Text(o)
                        }
                    }
                } else {
                    val numeric = node.type == MenuType.NUMBER || node.type == MenuType.BYTE
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        isError = error != null,
                        supportingText = { Text(error ?: when (node.type) {
                            MenuType.STRING -> "Up to ${node.param} characters"
                            MenuType.NUMBER -> "Up to ${node.param} digits"
                            else -> "0-255"
                        }) },
                        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                    )
                }
                Text("Saved to the radio's EEPROM.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && text != current && !vm.busy, onClick = {
                vm.write(node, text, column); onDismiss()
            }) { Text("Write") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
