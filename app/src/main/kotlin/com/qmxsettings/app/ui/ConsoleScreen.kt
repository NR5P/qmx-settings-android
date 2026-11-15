package com.qmxsettings.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun ConsoleScreen(vm: AppViewModel) {
    if (!vm.connected) return NotConnected()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.log.size) { if (vm.log.isNotEmpty()) listState.scrollToItem(vm.log.lastIndex) }

    fun send() {
        if (input.isNotBlank()) vm.sendRaw(input.trim())
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(vm.log) { l ->
                Text((if (l.outgoing) "→ " else "← ") + l.text, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (l.outgoing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in listOf("VN;", "IF;", "FA;", "MD;", "SM;", "LC;", "MM0?;", "ML3;")) AssistChip(onClick = { vm.sendRaw(c) }, label = { Text(c) })
        }
        Row {
            OutlinedTextField(input, { input = it.replace("\n", "") }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("CAT command, e.g. MMCW|CW Keyer|Keyer mode;") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }))
            Button(onClick = { send() }, Modifier.padding(start = 6.dp, top = 8.dp)) { Text("Send") }
        }
        Text("Semicolon-terminated; no line endings are ever sent (a CR would switch QMX to terminal mode).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
