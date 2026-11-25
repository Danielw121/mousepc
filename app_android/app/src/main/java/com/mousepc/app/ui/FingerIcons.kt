package com.mousepc.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iconos que pueden seguir al dedo en el touchpad. */
enum class FingerIcon(val id: String, val label: String, val animated: Boolean = false) {
    CIRCLE("circle", "Círculo"),
    PAW("paw", "Patita de gato"),
    ATOM("atom", "Átomo", animated = true),
    POINTER("pointer", "Puntero grande"),
    STAR("star", "Estrella", animated = true),
    NONE("none", "Ninguno");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: CIRCLE
    }
}

/** Ángulo (0..360) que gira sin parar, solo para los iconos animados. */
@Composable
fun rememberIconPhase(icon: FingerIcon): State<Float> {
    if (!icon.animated) return remember { mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = "icon")
    return t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
}

/** Dibuja [icon] centrado en [c]; [r] es el "radio" del icono en píxeles. */
fun DrawScope.drawFingerIcon(icon: FingerIcon, c: Offset, r: Float, phase: Float, accent: Color) {
    when (icon) {
        FingerIcon.CIRCLE -> {
            drawCircle(accent.copy(alpha = 0.18f), r * 1.15f, c)
            drawCircle(accent, r * 0.85f, c, style = Stroke(r * 0.12f))
            drawCircle(accent, r * 0.28f, c)
        }
        FingerIcon.PAW -> drawPaw(c, r)
        FingerIcon.ATOM -> drawAtom(c, r, phase)
        FingerIcon.POINTER -> drawPointer(c, r)
        FingerIcon.STAR -> drawStar(c, r, phase)
        FingerIcon.NONE -> Unit
    }
}

private val PawPink = Color(0xFFFFB3CF)
private val PawDark = Color(0xFF4A1F31)

private fun DrawScope.drawPaw(c: Offset, r: Float) {
    // Almohadilla principal: tres círculos que juntos forman la forma de "corazón invertido".
    val pad = listOf(
        Offset(0f, 0.30f) to 0.42f,
        Offset(-0.30f, 0.50f) to 0.32f,
        Offset(0.30f, 0.50f) to 0.32f,
    )
    // Deditos: (posición, ángulo)
    val toes = listOf(
        Offset(-0.72f, -0.18f) to -28f,
        Offset(-0.27f, -0.58f) to -8f,
        Offset(0.27f, -0.58f) to 8f,
        Offset(0.72f, -0.18f) to 28f,
    )
    for ((color, grow) in listOf(PawDark to 0.08f, PawPink to 0f)) {
        pad.forEach { (o, rad) -> drawCircle(color, r * (rad + grow), c + o * r) }
        toes.forEach { (o, deg) ->
            val center = c + o * r
            rotate(deg, center) {
                val w = r * (0.40f + grow * 2)
                val h = r * (0.52f + grow * 2)
                drawOval(color, Offset(center.x - w / 2, center.y - h / 2), Size(w, h))
            }
        }
    }
}

private fun DrawScope.drawAtom(c: Offset, r: Float, phase: Float) {
    val orbit = Color(0xFF7FE3FF)
    drawCircle(orbit.copy(alpha = 0.12f), r * 1.15f, c)
    val ry = r * 0.36f
    for (k in 0 until 3) {
        val ang = k * 60f + phase / 6f
        rotate(ang, c) {
            drawOval(orbit, Offset(c.x - r, c.y - ry), Size(r * 2, ry * 2), style = Stroke(r * 0.07f))
        }
        // electrón que recorre su órbita
        val th = (phase * (1 + k * 0.35f) + k * 120f) * PI.toFloat() / 180f
        val ex = r * cos(th)
        val ey = ry * sin(th)
        val a = ang * PI.toFloat() / 180f
        val p = Offset(c.x + ex * cos(a) - ey * sin(a), c.y + ex * sin(a) + ey * cos(a))
        drawCircle(Color.White, r * 0.11f, p)
    }
    drawCircle(Color(0xFFFF6B9A), r * 0.22f, c)
    drawCircle(Color.White.copy(alpha = 0.6f), r * 0.08f, c + Offset(-r * 0.06f, -r * 0.06f))
}

private fun DrawScope.drawPointer(c: Offset, r: Float) {
    // Flecha clásica de Windows; la punta queda justo donde está el dedo.
    val pts = listOf(0f to 0f, 0f to 17f, 4f to 13f, 7f to 20f, 10f to 19f, 7f to 12f, 12f to 12f)
    val k = r * 1.7f / 20f
    val path = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * k, y * k) else lineTo(x * k, y * k) }
        close()
    }
    translate(c.x + k * 1.2f, c.y + k * 1.4f) { drawPath(path, Color.Black.copy(alpha = 0.35f)) }
    translate(c.x, c.y) {
        drawPath(path, Color.White)
        drawPath(path, Color.Black, style = Stroke(k * 1.1f, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawStar(c: Offset, r: Float, phase: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = (-90f + i * 36f + phase / 4f) * PI.toFloat() / 180f
        val p = Offset(c.x + rad * cos(a), c.y + rad * sin(a))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawCircle(Color(0xFFFFD54F).copy(alpha = 0.15f), r * 1.2f, c)
    drawPath(path, Color(0xFFFFD54F))
    drawPath(path, Color(0xFF8D6E00), style = Stroke(r * 0.08f, join = StrokeJoin.Round))
}

/** Tarjeta para elegir el icono en los ajustes. */
@Composable
fun FingerIconOption(icon: FingerIcon, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val phase = rememberIconPhase(icon)
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(if (selected) cs.primaryContainer else cs.surfaceContainerHighest, RoundedCornerShape(16.dp))
                .border(2.dp, if (selected) cs.primary else Color.Transparent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon == FingerIcon.NONE) {
                Text("—", color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
            } else {
                Canvas(Modifier.size(64.dp)) {
                    val r = 17.dp.toPx()
                    // la flecha se dibuja desde la punta: la corremos para que quede centrada
                    val c = if (icon == FingerIcon.POINTER) center - Offset(r * 0.5f, r * 0.85f) else center
                    drawFingerIcon(icon, c, r, phase.value, cs.primary)
                }
            }
        }
        Text(
            icon.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            maxLines = 2,
        )
    }
}
