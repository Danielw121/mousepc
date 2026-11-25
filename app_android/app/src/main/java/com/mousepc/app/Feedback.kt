package com.mousepc.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Sonido de clic + vibración. Los sonidos se generan al vuelo (no hay archivos de audio en la app)
 * y se tocan con SoundPool, que tiene muy poca latencia.
 */
class Feedback(context: Context, private val s: AppSettings) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    // Clic seco (toque en el touchpad), "clac" grave al apretar y "tic" agudo al soltar.
    private val tapId = load(context, "tap", freq = 2400.0, durS = 0.035, decayS = 0.0050, noise = 0.35)
    private val pressId = load(context, "press", freq = 1500.0, durS = 0.040, decayS = 0.0060, noise = 0.30)
    private val releaseId = load(context, "release", freq = 3000.0, durS = 0.025, decayS = 0.0035, noise = 0.25)

    fun tap(view: View) {
        play(tapId, 1f)
        haptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun press(view: View) {
        play(pressId, 1f)
        haptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun release(view: View) {
        play(releaseId, 0.6f)
        haptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun longPress(view: View) {
        play(pressId, 1f)
        haptic(view, HapticFeedbackConstants.LONG_PRESS)
    }

    private fun play(id: Int, gain: Float) {
        if (!s.clickSound || id == 0) return
        val v = (s.soundVolume * gain).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    private fun haptic(view: View, type: Int) {
        if (s.haptics) view.performHapticFeedback(type)
    }

    private fun load(context: Context, name: String, freq: Double, durS: Double, decayS: Double, noise: Double): Int =
        try {
            val f = File(context.cacheDir, "snd_$name.wav")
            f.writeBytes(clickWav(freq, durS, decayS, noise))
            pool.load(f.path, 1)
        } catch (_: Exception) {
            0
        }

    /** Tono corto que se apaga rápido + un poco de ruido al inicio: suena como un clic mecánico. */
    private fun clickWav(freq: Double, durS: Double, decayS: Double, noise: Double): ByteArray {
        val rate = 44100
        val n = (rate * durS).toInt()
        val rnd = Random(7)
        val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) {
            val t = i / rate.toDouble()
            val attack = min(1.0, i / (rate * 0.0006))
            val tone = sin(2 * PI * freq * t) * exp(-t / decayS)
            val hiss = (rnd.nextDouble() * 2 - 1) * exp(-t / (decayS * 0.35))
            val v = (tone * (1 - noise) + hiss * noise) * attack * 0.85
            buf.putShort((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
        }
        return buf.array()
    }
}
