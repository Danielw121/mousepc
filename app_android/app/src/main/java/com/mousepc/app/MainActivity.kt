package com.mousepc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.mousepc.app.ui.ConnectScreen
import com.mousepc.app.ui.ControlScreen

class MainActivity : ComponentActivity() {
    private val app get() = application as MousePcApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4FF),
                    onPrimary = Color(0xFF00296B),
                    primaryContainer = Color(0xFF1F4A9E),
                    onPrimaryContainer = Color(0xFFDAE2FF),
                    background = Color(0xFF121318),
                    surface = Color(0xFF121318),
                ),
            ) {
                val conn = app.connection
                val inControl = conn.state == ConnState.CONNECTED ||
                    conn.state == ConnState.SERVER_OFF ||
                    conn.state == ConnState.LOST
                if (inControl) ControlScreen(conn, app.settings) else ConnectScreen(conn, app.settings)
            }
        }
    }

    override fun onDestroy() {
        // Al cerrar la app (no al girar la pantalla) avisa al PC.
        if (isFinishing) app.connection.disconnect()
        super.onDestroy()
    }
}
