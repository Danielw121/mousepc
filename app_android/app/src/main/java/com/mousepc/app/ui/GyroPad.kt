package com.mousepc.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mousepc.app.MousePcApp
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mousepc.app.AppSettings
import com.mousepc.app.PcConnection
import kotlin.math.abs

/**
 * Convierte la velocidad de giro del teléfono (rad/s) en movimiento del puntero.
 * Usa la velocidad (no el ángulo), así no se desvía con el tiempo.
 */
private class GyroMouse(private val conn: PcConnection, private val s: AppSettings) : SensorEventListener {
    @Volatile var active = false
    @Volatile var movedWhileActive = false
    private var lastNs = 0L
    private var vx = 0f
    private var vy = 0f

    override fun onSensorChanged(e: SensorEvent) {
        val dt = if (lastNs == 0L) 0f else ((e.timestamp - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = e.timestamp
        if (!active) {
            vx = 0f; vy = 0f
            return
        }
        // Plano (como control remoto): girar = eje Z. Vertical (pantalla hacia ti): girar = eje Y.
        // Inclinar arriba/abajo es el eje X en los dos casos.
        var yaw = -(if (s.gyroUpright) e.values[1] else e.values[2])
        var pitch = -e.values[0]
        if (abs(yaw) < DEAD_ZONE) yaw = 0f
        if (abs(pitch) < DEAD_ZONE) pitch = 0f
        vx = vx * SMOOTHING + yaw * (1 - SMOOTHING)
        vy = vy * SMOOTHING + pitch * (1 - SMOOTHING)
        val k = PX_PER_RAD * s.gyroSens * dt
        val dx = vx * k
        val dy = vy * k
        if (abs(dx) + abs(dy) > 0.05f) {
            if (abs(dx) + abs(dy) > 0.6f) movedWhileActive = true
            conn.move(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEAD_ZONE = 0.035f   // rad/s: ignora el temblor de la mano
        const val SMOOTHING = 0.45f    // 0 = sin suavizado
        const val PX_PER_RAD = 1300f   // girar ~57° recorre ~1300 px
    }
}

@Composable
fun GyroPad(conn: PcConnection, s: AppSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val fb = (context.applicationContext as MousePcApp).feedback
    val gyro = remember { GyroMouse(conn, s) }
    var holding by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }

    DisposableEffect(sensor) {
        if (sensor != null) sensorManager.registerListener(gyro, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(gyro) }
    }
    gyro.active = holding || locked

    if (sensor == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Este teléfono no tiene giroscopio.")
        }
        return
    }

    val cs = MaterialTheme.colorScheme
    val active = holding || locked
    val bg by animateColorAsState(if (active) cs.primaryContainer else cs.surfaceContainerHigh, label = "gyro")

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(20.dp))
                .border(2.dp, if (active) cs.primary else cs.outlineVariant, RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        gyro.movedWhileActive = false
                        holding = true
                        gyro.active = true
                        fb.press(view)
                        val up = waitForUpOrCancellation()
                        holding = false
                        gyro.active = locked
                        // Toque corto sin mover = clic izquierdo
                        if (up != null && up.uptimeMillis - down.uptimeMillis < 220 && !gyro.movedWhileActive) {
                            conn.click("l")
                            fb.tap(view)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.ScreenRotation, null, Modifier.size(56.dp), tint = if (active) cs.primary else cs.onSurfaceVariant)
                Text(
                    if (active) "Moviendo…" else "Mantén apretado y gira el teléfono\nToque corto = clic",
                    textAlign = TextAlign.Center,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Siempre activo (sin mantener apretado)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = locked, onCheckedChange = { locked = it })
        }
    }
}
