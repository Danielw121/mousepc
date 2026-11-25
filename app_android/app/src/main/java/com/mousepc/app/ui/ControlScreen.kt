package com.mousepc.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ScreenRotationAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mousepc.app.AppSettings
import com.mousepc.app.ConnState
import com.mousepc.app.PcConnection

// Texto invisible que siempre queda en el campo: así detectamos "borrar" aunque parezca vacío.
private const val SENTINEL = "​​"
private val EMPTY_FIELD = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

private val SPECIAL_KEYS = listOf(
    "Esc" to "esc", "Tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right",
    "Supr" to "delete", "Inicio" to "home", "Fin" to "end", "Win" to "win",
    "Vol−" to "voldown", "Vol+" to "volup", "Mute" to "mute", "⏯" to "playpause",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(conn: PcConnection, s: AppSettings) {
    var keyboardOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(EMPTY_FIELD) }
    val focus = remember { FocusRequester() }

    // Mantiene la pantalla encendida mientras controlas el PC.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val (dotColor, status) = when (conn.state) {
        ConnState.CONNECTED -> Color(0xFF69F0AE) to conn.serverName
        ConnState.SERVER_OFF -> Color(0xFFFFAB40) to "Desactivado en el PC"
        else -> Color(0xFFFF5252) to "Reconectando…"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Circle, null, Modifier.size(12.dp), tint = dotColor)
                        Spacer(Modifier.width(8.dp))
                        Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        keyboardOpen = !keyboardOpen
                        field = EMPTY_FIELD
                    }) {
                        Icon(if (keyboardOpen) Icons.Default.KeyboardHide else Icons.Default.Keyboard, "Teclado")
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Ajustes") }
                    IconButton(onClick = conn::disconnect) { Icon(Icons.Default.LinkOff, "Desconectar") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = s.mode == "touch",
                    onClick = { s.mode = "touch" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Default.TouchApp, null) },
                ) { Text("Touchpad") }
                SegmentedButton(
                    selected = s.mode == "gyro",
                    onClick = { s.mode = "gyro" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Default.ScreenRotationAlt, null) },
                ) { Text("Giroscopio") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.mode == "gyro") GyroPad(conn, s) else Touchpad(conn, s)
            }

            Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("Izquierdo", "l", conn, Modifier.weight(3f))
                HoldButton("Medio", "m", conn, Modifier.weight(2f))
                HoldButton("Derecho", "r", conn, Modifier.weight(3f))
            }

            if (keyboardOpen) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPECIAL_KEYS.forEach { (label, key) ->
                        OutlinedButton(onClick = { conn.key(key) }) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { v ->
                        val t = v.text
                        if (t.length < SENTINEL.length || !t.startsWith(SENTINEL)) {
                            repeat(maxOf(1, SENTINEL.length - t.length)) { conn.key("backspace") }
                        } else {
                            val added = t.substring(SENTINEL.length)
                            if (added.isNotEmpty()) conn.text(added)
                        }
                        field = EMPTY_FIELD
                    },
                    placeholder = { Text("Escribe aquí → se escribe en el PC") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { conn.key("enter") }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                SettingSlider("Sensibilidad touchpad", s.touchSens, 0.3f..3f) { s.touchSens = it }
                SettingSlider("Aceleración touchpad", s.touchAccel, 0f..2f) { s.touchAccel = it }
                SettingSlider("Velocidad scroll", s.scrollSens, 0.3f..3f) { s.scrollSens = it }
                SettingSwitch("Scroll natural (como en el teléfono)", null, s.naturalScroll) { s.naturalScroll = it }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSlider("Sensibilidad giroscopio", s.gyroSens, 0.3f..3f) { s.gyroSens = it }
                SettingSwitch(
                    "Sostener vertical",
                    if (s.gyroUpright) "Pantalla hacia ti, apuntando con la parte trasera"
                    else "Plano, apuntando con la punta como un control remoto",
                    s.gyroUpright,
                ) { s.gyroUpright = it }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(4f))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(6f))
        Text("%.1fx".format(value), Modifier.width(44.dp))
    }
}

@Composable
private fun SettingSwitch(label: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Aprieta al tocar y suelta al levantar el dedo: sirve para clic normal y para arrastrar. */
@Composable
private fun HoldButton(label: String, button: String, conn: PcConnection, modifier: Modifier) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    DisposableEffect(Unit) { onDispose { if (pressed) conn.up(button) } }
    Box(
        modifier
            .fillMaxHeight()
            .background(if (pressed) cs.primary else cs.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    conn.down(button)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        conn.up(button)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) cs.onPrimary else cs.onSurface, fontWeight = FontWeight.SemiBold)
    }
}
