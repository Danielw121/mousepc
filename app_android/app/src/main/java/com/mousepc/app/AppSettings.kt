package com.mousepc.app

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Preferencias guardadas en el teléfono. Cada cambio se guarda solo y refresca la UI. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mousepc", Context.MODE_PRIVATE)

    var touchSens by floatPref("touchSens", 1f)       // multiplicador del touchpad
    var touchAccel by floatPref("touchAccel", 1f)     // cuánto acelera al mover rápido
    var scrollSens by floatPref("scrollSens", 1f)
    var naturalScroll by boolPref("naturalScroll", true) // como en el teléfono
    var gyroSens by floatPref("gyroSens", 1f)
    var gyroUpright by boolPref("gyroUpright", false)    // false = plano, como control remoto
    var mode by stringPref("mode", "touch")              // "touch" | "gyro"

    var lastHost by stringPref("lastHost", "")
    var port by intPref("port", 5005)
    var pin by stringPref("pin", "")

    private fun floatPref(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        val state = mutableFloatStateOf(prefs.getFloat(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.floatValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            state.floatValue = value
            prefs.edit().putFloat(key, value).apply()
        }
    }

    private fun intPref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        val state = mutableIntStateOf(prefs.getInt(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.intValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            state.intValue = value
            prefs.edit().putInt(key, value).apply()
        }
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        val state = mutableStateOf(prefs.getBoolean(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun stringPref(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        val state = mutableStateOf(prefs.getString(key, def) ?: def)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            prefs.edit().putString(key, value).apply()
        }
    }
}
