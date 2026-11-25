package com.mousepc.app.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ScreenRotationAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.mousepc.app.Feedback
import com.mousepc.app.MousePcApp
import com.mousepc.app.PcConnection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Texto invisible que siempre queda en el campo: así detectamos "borrar" aunque parezca vacío.
private const val SENTINEL = "​​"
private val EMPTY_FIELD = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

private val SPECIAL_KEYS = listOf(
    "Esc" to "esc", "Tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right",
    "Supr" to "delete", "Inicio" to "home", "Fin" to "end", "Win" to "win", "⏯" to "playpause",
)

private val Accent2 = Color(0xFFB69CFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(conn: PcConnection, s: AppSettings) {
    var keyboardOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(EMPTY_FIELD) }
    val focus = remember { FocusRequester() }
    val fb = (LocalContext.current.applicationContext as MousePcApp).feedback

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
                    IconButton(onClick = { s.showVolume = !s.showVolume }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volumen", tint = if (s.showVolume) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
            // Aviso del PC: aparece unos segundos y se va solo.
            var lastWarning by remember { mutableStateOf("") }
            conn.warning?.let { lastWarning = it }
            AnimatedVisibility(
                visible = conn.warning != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF4A3A10))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFFC94D))
                    Spacer(Modifier.width(10.dp))
                    Text(lastWarning, color = Color(0xFFFFE7B0), style = MaterialTheme.typography.bodySmall)
                }
            }

            ModeToggle(s.mode, fb, view) { s.mode = it }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.mode == "gyro") GyroPad(conn, s) else Touchpad(conn, s)
            }

            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("Izquierdo", "l", conn, fb, Modifier.weight(3f))
                HoldButton("Medio", "m", conn, fb, Modifier.weight(2f))
                HoldButton("Derecho", "r", conn, fb, Modifier.weight(3f))
            }

            AnimatedVisibility(
                visible = s.showVolume,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                VolumeBar(conn, fb)
            }

            if (keyboardOpen) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPECIAL_KEYS.forEach { (label, key) ->
                        OutlinedButton(onClick = { conn.key(key); fb.tap(view) }) { Text(label) }
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
            SettingsContent(s)
        }
    }
}

// ------------------------------------------------------------------ ajustes

@Composable
private fun SettingsContent(s: AppSettings) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        Section("1. Touchpad")
        SettingSlider("Sensibilidad", s.touchSens, 0.3f..3f) { s.touchSens = it }
        SettingSlider("Aceleración", s.touchAccel, 0f..2f) { s.touchAccel = it }
        SettingSlider("Velocidad del scroll", s.scrollSens, 0.3f..3f) { s.scrollSens = it }
        SettingSwitch("Scroll natural", "Como en el teléfono: el contenido sigue al dedo", s.naturalScroll) { s.naturalScroll = it }

        Section("2. Icono del dedo")
        Text(
            "Aparece donde tocas el touchpad",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FingerIcon.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { icon ->
                        FingerIconOption(icon, s.fingerIcon == icon.id, Modifier.weight(1f)) { s.fingerIcon = icon.id }
                    }
                }
            }
        }

        Section("3. Giroscopio")
        SettingSlider("Sensibilidad", s.gyroSens, 0.3f..3f) { s.gyroSens = it }
        SettingSwitch(
            "Sostener vertical",
            if (s.gyroUpright) "Pantalla hacia ti, apuntando con la parte trasera"
            else "Plano, apuntando con la punta como un control remoto",
            s.gyroUpright,
        ) { s.gyroUpright = it }

        Section("4. Sonido y vibración")
        SettingSwitch("Sonido al hacer clic", null, s.clickSound) { s.clickSound = it }
        if (s.clickSound) {
            SettingSlider("Volumen del sonido", s.soundVolume, 0.05f..1f, format = { "${(it * 100).toInt()}%" }) { s.soundVolume = it }
        }
        SettingSwitch("Vibración", null, s.haptics) { s.haptics = it }

        Section("5. Volumen del PC")
        SettingSwitch("Mostrar botones de volumen", "Barra debajo de los botones del mouse", s.showVolume) { s.showVolume = it }
        SettingSwitch(
            "Usar los botones de volumen del teléfono",
            "Mientras estás conectado, suben y bajan el volumen del PC",
            s.hwVolumeKeys,
        ) { s.hwVolumeKeys = it }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { "%.1fx".format(it) },
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(4f))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(6f))
        Text(format(value), Modifier.padding(start = 8.dp).width(44.dp))
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

// ------------------------------------------------------------------ controles

/** Selector Touchpad / Giroscopio con una "píldora" que se desliza. */
@Composable
private fun ModeToggle(mode: String, fb: Feedback, view: View, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (mode == "gyro") half else 0.dp, spring(dampingRatio = 0.75f, stiffness = 500f), label = "pill")
        Box(
            Modifier
                .offset(x = x)
                .width(half)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(cs.primary, Accent2))),
        )
        Row(Modifier.fillMaxSize()) {
            listOf(
                Triple("touch", Icons.Default.TouchApp, "Touchpad"),
                Triple("gyro", Icons.Default.ScreenRotationAlt, "Giroscopio"),
            ).forEach { (id, icon, label) ->
                val selected = mode == id
                val color by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, label = "modeColor")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!selected) {
                                onChange(id)
                                fb.tap(view)
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = color, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Aprieta al tocar y suelta al levantar el dedo: sirve para clic normal y para arrastrar. */
@Composable
private fun HoldButton(label: String, button: String, conn: PcConnection, fb: Feedback, modifier: Modifier) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "btnScale")
    val content by animateColorAsState(if (pressed) cs.onPrimary else cs.onSurface, label = "btnContent")
    DisposableEffect(Unit) { onDispose { if (pressed) conn.up(button) } }
    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                if (pressed) Brush.verticalGradient(listOf(cs.primary, Accent2))
                else Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHigh)),
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f))), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    conn.down(button)
                    fb.press(view)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        conn.up(button)
                        fb.release(view)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MouseGlyph(button, content)
            Text(label, color = content, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Mouse pequeño con la parte del botón resaltada. */
@Composable
private fun MouseGlyph(button: String, color: Color) {
    Canvas(Modifier.size(width = 16.dp, height = 22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        val body = RoundRect(0f, 0f, w, h, CornerRadius(w / 2, w / 2))
        val split = h * 0.45f
        clipPath(Path().apply { addRoundRect(body) }) {
            when (button) {
                "l" -> drawRect(color, Offset.Zero, Size(w / 2, split))
                "r" -> drawRect(color, Offset(w / 2, 0f), Size(w / 2, split))
            }
        }
        drawPath(Path().apply { addRoundRect(body) }, color, style = Stroke(stroke))
        drawLine(color, Offset(0f, split), Offset(w, split), stroke)
        if (button == "m") {
            drawRoundRect(color, Offset(w / 2 - stroke * 1.2f, h * 0.12f), Size(stroke * 2.4f, h * 0.24f), CornerRadius(stroke, stroke))
        } else {
            drawLine(color, Offset(w / 2, 0f), Offset(w / 2, split), stroke)
        }
    }
}

/** Barra para controlar el volumen del PC. Mantener apretado repite. */
@Composable
private fun VolumeBar(conn: PcConnection, fb: Feedback) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundActionButton(Icons.AutoMirrored.Filled.VolumeOff, "Silenciar", fb, repeat = false) { conn.key("mute") }
        Spacer(Modifier.width(6.dp))
        RoundActionButton(Icons.AutoMirrored.Filled.VolumeDown, "Bajar volumen", fb) { conn.key("voldown") }
        Text(
            "Volumen del PC",
            Modifier.weight(1f),
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        RoundActionButton(Icons.AutoMirrored.Filled.VolumeUp, "Subir volumen", fb, primary = true) { conn.key("volup") }
    }
}

@Composable
private fun RoundActionButton(
    icon: ImageVector,
    description: String,
    fb: Feedback,
    repeat: Boolean = true,
    primary: Boolean = false,
    action: () -> Unit,
) {
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val currentAction by rememberUpdatedState(action)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "roundScale")
    val bg = when {
        pressed -> Brush.verticalGradient(listOf(cs.primary, Accent2))
        primary -> Brush.verticalGradient(listOf(cs.primaryContainer, cs.primaryContainer))
        else -> Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHighest))
    }
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .pointerInput(repeat) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        currentAction()
                        fb.press(view)
                        val job = if (repeat) {
                            launch {
                                delay(400)
                                while (isActive) {
                                    currentAction()
                                    delay(110)
                                }
                            }
                        } else null
                        waitForUpOrCancellation()
                        job?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (pressed) cs.onPrimary else if (primary) cs.onPrimaryContainer else cs.onSurface)
    }
}
