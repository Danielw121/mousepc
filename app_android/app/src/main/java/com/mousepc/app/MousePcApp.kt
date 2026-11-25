package com.mousepc.app

import android.app.Application
import android.os.Build

/** Guarda la conexión y los ajustes durante toda la vida de la app. */
class MousePcApp : Application() {
    val settings by lazy { AppSettings(this) }
    val connection by lazy { PcConnection(Build.MODEL ?: "Android") }
    val feedback by lazy { Feedback(this, settings) }
}
