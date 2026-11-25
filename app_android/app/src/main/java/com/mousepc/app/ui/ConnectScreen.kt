package com.mousepc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mousepc.app.AppSettings
import com.mousepc.app.ConnState
import com.mousepc.app.PcConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(conn: PcConnection, settings: AppSettings) {
    var host by remember { mutableStateOf(settings.lastHost) }
    var pin by remember { mutableStateOf(settings.pin) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) {
        if (conn.state == ConnState.DISCONNECTED) conn.discover(settings.port)
    }

    fun doConnect() {
        val p = port.toIntOrNull() ?: 5005
        settings.lastHost = host.trim()
        settings.pin = pin.trim()
        settings.port = p
        focus.clearFocus()
        conn.connect(host, p, pin.trim())
    }

    val error = when (conn.state) {
        ConnState.BAD_PIN -> "PIN incorrecto. Míralo en el icono de MousePC del PC."
        ConnState.BLOCKED -> "Demasiados intentos. Espera un minuto."
        ConnState.NO_ANSWER ->
            "El PC no responde. ¿Está MousePC abierto, en la misma WiFi y permitido en el firewall?"
        else -> null
    }
    val connecting = conn.state == ConnState.CONNECTING

    Scaffold(topBar = { TopAppBar(title = { Text("MousePC") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PCs en tu red", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (conn.discovering) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { conn.discover(port.toIntOrNull() ?: 5005) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Buscar de nuevo")
                    }
                }
            }
            if (conn.found.isEmpty() && !conn.discovering) {
                Text(
                    "No se encontró ninguno. Escribe la IP a mano (aparece en el menú del icono en el PC).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            conn.found.forEach { pc ->
                val selected = host == pc.ip
                Card(
                    onClick = { host = pc.ip },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(Icons.Default.Computer, null, tint = if (pc.enabled) Color(0xFF69F0AE) else Color.Gray)
                        },
                        headlineContent = { Text(pc.name) },
                        supportingContent = { Text(if (pc.enabled) pc.ip else "${pc.ip} · desactivado") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("IP del PC") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 4) pin = it.filter(Char::isDigit) },
                    label = { Text("PIN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { doConnect() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Puerto") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = ::doConnect,
                enabled = !connecting && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Conectando…")
                } else {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Conectar")
                }
            }
        }
    }
}
