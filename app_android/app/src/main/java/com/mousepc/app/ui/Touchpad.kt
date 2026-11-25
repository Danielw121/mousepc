package com.mousepc.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mousepc.app.AppSettings
import com.mousepc.app.MousePcApp
import com.mousepc.app.PcConnection
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Gestos del touchpad, en el orden en que se muestran. */
private val GESTURES = listOf(
    "1 dedo, deslizar" to "Mover el puntero",
    "Tocar" to "Clic izquierdo",
    "Tocar con 2 dedos" to "Clic derecho",
    "Tocar con 3 dedos" to "Clic central",
    "2 dedos, deslizar" to "Scroll",
    "Doble toque y deslizar" to "Arrastrar",
)

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
    val fb = (LocalContext.current.applicationContext as MousePcApp).feedback
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var fingers by remember { mutableStateOf(emptyList<Offset>()) }
    val lastTapAt = remember { longArrayOf(0L) }

    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(
        when {
            dragging -> cs.primary
            touching -> cs.primary.copy(alpha = 0.5f)
            else -> cs.outlineVariant
        },
        label = "touchpadBorder",
    )
    val shape = RoundedCornerShape(24.dp)

    val icon = FingerIcon.of(s.fingerIcon)
    val phase = rememberIconPhase(icon)
    val iconAlpha by animateFloatAsState(if (touching) 1f else 0f, tween(if (touching) 120 else 280), label = "iconAlpha")
    val iconScale by animateFloatAsState(if (touching) 1f else 0.6f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "iconScale")

    Box(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerLow)))
            .border(1.dp, border, shape)
            .pointerInput(conn) {
                val dens = density
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    fingers = listOf(first.position)
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
                            val down = ev.changes.filter { it.pressed }
                            val pressed = down.size
                            maxPointers = max(maxPointers, pressed)
                            ev.changes.firstOrNull()?.let { upAt = it.uptimeMillis }
                            if (pressed == 0) break
                            fingers = down.map { it.position }

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
                                    fb.longPress(view)
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
                            fb.release(view)
                            dragging = false
                            lastTapAt[0] = 0L
                        } else if (!cancelled && upAt - downAt < 220 && travel < 14f) {
                            when {
                                maxPointers >= 3 -> { conn.click("m"); lastTapAt[0] = 0L }
                                maxPointers == 2 -> { conn.click("r"); lastTapAt[0] = 0L }
                                else -> { conn.click("l"); lastTapAt[0] = upAt }
                            }
                            fb.tap(view)
                        } else {
                            lastTapAt[0] = 0L
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Guía de gestos: aparece con el touchpad libre y se esconde al tocarlo.
        AnimatedVisibility(
            visible = !touching,
            enter = fadeIn(tween(450, delayMillis = 150)) + scaleIn(tween(450, delayMillis = 150), initialScale = 0.94f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 1.04f),
        ) {
            GestureGuide()
        }

        // Aviso de arrastre
        AnimatedVisibility(
            visible = dragging,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Text(
                "Arrastrando…",
                Modifier.background(cs.primary, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
                color = cs.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Icono bajo cada dedo
        if (icon != FingerIcon.NONE && (touching || iconAlpha > 0.01f)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = iconAlpha },
            ) {
                val r = 30.dp.toPx() * iconScale
                fingers.forEach { drawFingerIcon(icon, it, r, phase.value, cs.primary) }
            }
        }
    }
}

@Composable
private fun GestureGuide() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GESTURES.forEachIndexed { i, (gesture, action) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).background(cs.primary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = cs.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    gesture,
                    Modifier.padding(start = 10.dp).width(150.dp),
                    color = cs.onSurface.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(action, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
