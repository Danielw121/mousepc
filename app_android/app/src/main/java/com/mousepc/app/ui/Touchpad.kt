package com.mousepc.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mousepc.app.AppSettings
import com.mousepc.app.PcConnection
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Superficie tipo touchpad de portátil.
 *  - 1 dedo: mover               - toque: clic izquierdo
 *  - 2 dedos: scroll             - toque con 2 dedos: clic derecho
 *  - toque con 3 dedos: clic central
 *  - toque y luego toca-y-arrastra: arrastrar (mantiene el botón izquierdo)
 */
@Composable
fun Touchpad(conn: PcConnection, s: AppSettings, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val lastTapAt = remember { longArrayOf(0L) }

    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            dragging -> cs.primaryContainer
            touching -> cs.surfaceContainerHighest
            else -> cs.surfaceContainerHigh
        },
        label = "touchpad",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(bg, RoundedCornerShape(20.dp))
            .border(1.dp, if (dragging) cs.primary else cs.outlineVariant, RoundedCornerShape(20.dp))
            .pointerInput(conn) {
                val dens = density
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    val downAt = first.uptimeMillis
                    var upAt = downAt
                    val dragArmed = lastTapAt[0] != 0L && downAt - lastTapAt[0] < 300
                    var maxPointers = 1
                    var travel = 0f // en dp
                    var isDragging = false
                    var cancelled = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            maxPointers = max(maxPointers, pressed)
                            ev.changes.firstOrNull()?.let { upAt = it.uptimeMillis }
                            if (pressed == 0) break

                            val moving = ev.changes.filter { it.pressed && it.previousPressed }
                            if (moving.isEmpty()) continue
                            var sx = 0f
                            var sy = 0f
                            moving.forEach {
                                val d = it.position - it.previousPosition
                                sx += d.x; sy += d.y
                                it.consume()
                            }
                            // promedio entre dedos, convertido a dp para que no dependa de la pantalla
                            val dx = sx / moving.size / dens
                            val dy = sy / moving.size / dens
                            val dist = hypot(dx, dy)
                            travel += dist

                            if (pressed == 1 && maxPointers == 1) {
                                if (dragArmed && !isDragging && travel > 8f) {
                                    isDragging = true
                                    dragging = true
                                    conn.down("l")
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                // Aceleración: los movimientos rápidos recorren más distancia.
                                val accel = 1f + min(dist / 10f, 2.5f) * s.touchAccel
                                val f = 1.6f * s.touchSens * accel
                                conn.move(dx * f, dy * f)
                            } else if (pressed >= 2) {
                                val k = 4f * s.scrollSens
                                val sign = if (s.naturalScroll) 1f else -1f
                                conn.scroll(sign * dy * k, -sign * dx * k)
                            }
                        }
                    } catch (e: Exception) {
                        cancelled = true
                        throw e
                    } finally {
                        touching = false
                        if (isDragging) {
                            conn.up("l")
                            dragging = false
                            lastTapAt[0] = 0L
                        } else if (!cancelled && upAt - downAt < 220 && travel < 14f) {
                            when {
                                maxPointers >= 3 -> { conn.click("m"); lastTapAt[0] = 0L }
                                maxPointers == 2 -> { conn.click("r"); lastTapAt[0] = 0L }
                                else -> { conn.click("l"); lastTapAt[0] = upAt }
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } else {
                            lastTapAt[0] = 0L
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dragging) "Arrastrando…"
            else "Desliza para mover\nToque = clic · 2 dedos = clic derecho / scroll\nToca, vuelve a tocar y arrastra = arrastrar",
            textAlign = TextAlign.Center,
            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
