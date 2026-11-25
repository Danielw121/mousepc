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
import kotlin.math.sign
import kotlin.math.sqrt

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
    // Dirección "arriba" del mundo vista desde el teléfono (vector unitario, filtrado).
    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var hasGravity = false

    /** Gravedad (TYPE_GRAVITY) o acelerómetro filtrado si no hay sensor de gravedad. */
    fun onGravity(x: Float, y: Float, z: Float, filtered: Boolean) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1f) return
        val a = if (filtered || !hasGravity) 1f else 0.1f
        gx += (x / n - gx) * a
        gy += (y / n - gy) * a
        gz += (z / n - gz) * a
        val m = sqrt(gx * gx + gy * gy + gz * gz)
        gx /= m; gy /= m; gz /= m
        hasGravity = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> return onGravity(e.values[0], e.values[1], e.values[2], true)
            Sensor.TYPE_ACCELEROMETER -> return onGravity(e.values[0], e.values[1], e.values[2], false)
        }
        val dt = if (lastNs == 0L) 0f else ((e.timestamp - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = e.timestamp
        if (!active) {
            vx = 0f; vy = 0f
            return
        }
        val wx = e.values[0]
        val wy = e.values[1]
        val wz = e.values[2]
        // Izquierda/derecha = girar alrededor del eje vertical del mundo (la gravedad),
        // así da igual si el teléfono está plano, inclinado o de pie.
        var yaw = -(wx * gx + wy * gy + wz * gz)
        // Arriba/abajo = girar alrededor del eje horizontal "derecha" del teléfono
        // (su eje X sin la parte vertical). Si el teléfono está de lado, se usa su eje Y.
        var rx = 1f - gx * gx
        var ry = -gx * gy
        var rz = -gx * gz
        var rn = sqrt(rx * rx + ry * ry + rz * rz)
        if (rn < 0.3f) {
            rx = -gy * gx; ry = 1f - gy * gy; rz = -gy * gz
            rn = sqrt(rx * rx + ry * ry + rz * rz)
        }
        var pitch = -(wx * rx + wy * ry + wz * rz) / rn
        if (s.gyroInvertY) pitch = -pitch
        yaw = softDeadZone(yaw)
        pitch = softDeadZone(pitch)
        vx = vx * SMOOTHING + yaw * (1 - SMOOTHING)
        vy = vy * SMOOTHING + pitch * (1 - SMOOTHING)
        // Aceleración suave: giros lentos = precisión, giros rápidos = cruzar la pantalla.
        val speed = sqrt(vx * vx + vy * vy).coerceAtMost(2.5f)
        val k = PX_PER_RAD * s.gyroSens * dt * (0.6f + 0.8f * speed)
        val dx = vx * k
        val dy = vy * k
        if (abs(dx) + abs(dy) > 0.05f) {
            if (abs(dx) + abs(dy) > 0.6f) movedWhileActive = true
            conn.move(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Quita el temblor sin dar un salto al pasar el umbral. */
    private fun softDeadZone(v: Float): Float {
        val a = abs(v) - DEAD_ZONE
        return if (a <= 0f) 0f else a * sign(v)
    }

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
        if (sensor != null) {
            sensorManager.registerListener(gyro, sensor, SensorManager.SENSOR_DELAY_GAME)
            val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (grav != null) sensorManager.registerListener(gyro, grav, SensorManager.SENSOR_DELAY_GAME)
        }
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
