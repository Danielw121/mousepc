package com.mousepc.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, SERVER_OFF, BAD_PIN, BLOCKED, NO_ANSWER, LOST }

data class FoundPc(val ip: String, val name: String, val enabled: Boolean)

/**
 * Cliente UDP del servidor MousePC. Todo el tráfico de red va en hilos propios;
 * el estado que ve la UI se cambia siempre en el hilo principal.
 */
class PcConnection(private val deviceName: String) {
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var socket: DatagramSocket? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var port = 5005
    @Volatile private var pin = ""
    @Volatile private var canSend = false // espejo rápido de "state == CONNECTED"
    @Volatile private var lastAnswer = 0L

    private var helloTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null

    var state by mutableStateOf(ConnState.DISCONNECTED)
        private set
    var serverName by mutableStateOf("")
        private set
    var discovering by mutableStateOf(false)
        private set
    val found = mutableStateListOf<FoundPc>()

    /** Aviso del PC (p. ej. ventana de administrador que Windows no deja controlar). */
    var warning by mutableStateOf<String?>(null)
        private set
    private val clearWarning = Runnable { warning = null }

    // ---------------------------------------------------------------- red
    @Synchronized
    private fun sock(): DatagramSocket {
        socket?.let { return it }
        val s = DatagramSocket().apply { broadcast = true }
        socket = s
        thread(isDaemon = true, name = "mousepc-rx") { receiveLoop(s) }
        return s
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: IOException) {
                if (s.isClosed) break else continue
            }
            val json = try {
                JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
            } catch (e: JSONException) {
                continue
            }
            val from = p.address
            main.post { onMessage(json, from) }
        }
    }

    private fun sendTo(msg: JSONObject, addr: InetAddress, port: Int) {
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        sender.execute {
            try {
                sock().send(DatagramPacket(bytes, bytes.size, addr, port))
            } catch (_: IOException) { /* sin red: se ignora */ }
        }
    }

    private fun send(msg: JSONObject) {
        host?.let { sendTo(msg, it, port) }
    }

    private fun msg(type: String) = JSONObject().put("t", type)

    // ---------------------------------------------------------------- búsqueda
    fun discover(port: Int) {
        if (discovering) return
        found.clear()
        discovering = true
        scheduler.execute {
            val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    nif.interfaceAddresses.forEach { ia ->
                        if (ia.address is Inet4Address) ia.broadcast?.let(targets::add)
                    }
                }
            } catch (_: Exception) {
            }
            repeat(3) {
                targets.forEach { sendTo(msg("disc"), it, port) }
                Thread.sleep(400)
            }
            Thread.sleep(500)
            main.post { discovering = false }
        }
    }

    // ---------------------------------------------------------------- conexión
    fun connect(hostText: String, port: Int, pin: String) {
        stopTimers()
        changeState(ConnState.CONNECTING)
        this.port = port
        this.pin = pin
        scheduler.execute {
            val addr = try {
                InetAddress.getByName(hostText.trim())
            } catch (e: Exception) {
                null
            }
            main.post {
                if (state != ConnState.CONNECTING) return@post
                if (addr == null) {
                    changeState(ConnState.NO_ANSWER)
                    return@post
                }
                host = addr
                var tries = 0
                helloTask = scheduler.scheduleAtFixedRate({
                    main.post {
                        if (state != ConnState.CONNECTING) {
                            helloTask?.cancel(false)
                        } else if (++tries > 5) {
                            helloTask?.cancel(false)
                            changeState(ConnState.NO_ANSWER)
                        } else {
                            sendHello()
                        }
                    }
                }, 0, 700, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun sendHello() = send(msg("hello").put("pin", pin).put("name", deviceName).put("v", 1))

    private fun startPing() {
        if (pingTask != null) return
        lastAnswer = SystemClock.elapsedRealtime()
        pingTask = scheduler.scheduleAtFixedRate({
            send(msg("ping"))
            main.post {
                val silent = SystemClock.elapsedRealtime() - lastAnswer > 6000
                when {
                    state == ConnState.LOST -> sendHello() // se recupera solo cuando vuelve la red
                    silent && (state == ConnState.CONNECTED || state == ConnState.SERVER_OFF) ->
                        changeState(ConnState.LOST)
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun disconnect() {
        if (host != null) send(msg("bye"))
        stopTimers()
        host = null
        changeState(ConnState.DISCONNECTED)
    }

    private fun stopTimers() {
        helloTask?.cancel(false)
        pingTask?.cancel(false)
        helloTask = null
        pingTask = null
    }

    private fun changeState(s: ConnState) {
        state = s
        canSend = s == ConnState.CONNECTED
    }

    private fun onMessage(m: JSONObject, from: InetAddress) {
        val t = m.optString("t")
        if (t == "here") {
            val ip = from.hostAddress ?: return
            found.removeAll { it.ip == ip }
            found.add(FoundPc(ip, m.optString("name", ip), m.optBoolean("enabled", true)))
            return
        }
        if (host == null || from != host) return
        lastAnswer = SystemClock.elapsedRealtime()
        when (t) {
            "ok" -> {
                serverName = m.optString("name")
                changeState(ConnState.CONNECTED)
                startPing()
            }
            "off" -> {
                serverName = m.optString("name")
                changeState(ConnState.SERVER_OFF)
                startPing()
            }
            "pong" -> if (state == ConnState.CONNECTED || state == ConnState.SERVER_OFF || state == ConnState.LOST) {
                changeState(if (m.optBoolean("enabled", true)) ConnState.CONNECTED else ConnState.SERVER_OFF)
            }
            "auth" -> sendHello() // el PC nos olvidó (reinicio, PIN cambiado…)
            "badpin" -> {
                stopTimers(); changeState(ConnState.BAD_PIN)
            }
            "blocked" -> {
                stopTimers(); changeState(ConnState.BLOCKED)
            }
            "warn" -> if (m.optString("k") == "admin") {
                val app = m.optString("app").ifBlank { "Esa ventana" }
                warning = "$app es de administrador y Windows no deja controlarla. " +
                    "En el PC: clic derecho en el icono de MousePC → Ejecutar como administrador."
                main.removeCallbacks(clearWarning)
                main.postDelayed(clearWarning, 8000)
            }
        }
    }

    // ---------------------------------------------------------------- comandos
    fun move(dx: Float, dy: Float) {
        if (!canSend || (dx == 0f && dy == 0f)) return
        send(msg("m").put("dx", r(dx)).put("dy", r(dy)))
    }

    fun click(b: String) {
        if (canSend) send(msg("c").put("b", b))
    }

    fun down(b: String) {
        if (canSend) send(msg("d").put("b", b))
    }

    /** Soltar se manda siempre, para no dejar un botón pegado en el PC. */
    fun up(b: String) = send(msg("u").put("b", b))

    fun scroll(v: Float, h: Float) {
        if (!canSend || (v == 0f && h == 0f)) return
        send(msg("s").put("v", r(v)).put("h", r(h)))
    }

    fun text(s: String) {
        if (canSend) send(msg("txt").put("s", s))
    }

    fun key(k: String) {
        if (canSend) send(msg("key").put("k", k))
    }

    private fun r(v: Float): Double = (v * 100).roundToInt() / 100.0
}
