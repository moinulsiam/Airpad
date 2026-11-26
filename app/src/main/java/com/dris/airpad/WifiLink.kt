package com.dris.airpad

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Sends mouse / keyboard commands to the AirPad Server on the PC over WiFi (UDP).
 * Packet format:  PIN|CMD|ARGS
 */
class WifiLink(private val ctx: Context, private val onState: (String, Boolean) -> Unit) {

    private val prefs = ctx.getSharedPreferences("airpad", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadExecutor()
    private var sock: DatagramSocket? = null
    private val port = 50505

    @Volatile var host: String = prefs.getString("host", "") ?: ""
    @Volatile var pin: String = prefs.getString("pin", "") ?: ""
    @Volatile private var lastOk = 0L
    @Volatile var onPending: (() -> Unit)? = null
    @Volatile private var badPin = false

    private var accX = 0f
    private var accY = 0f

    // ---------------------------------------------------------- lifecycle
    fun start() {
        exec.execute {
            try {
                bindWifi()
                val s = DatagramSocket()
                s.broadcast = true
                sock = s
                val t = Thread { rxLoop(s) }
                t.isDaemon = true
                t.start()
            } catch (e: Exception) {
                onState("Network error: " + e.message, false)
            }
        }
    }

    fun stop() {
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun bindWifi() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n)
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n)
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun rxLoop(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (!s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val msg = String(p.data, 0, p.length)
                when {
                    msg == "OK" || msg.startsWith("OK|") -> {
                        lastOk = System.currentTimeMillis()
                        badPin = false
                        if (msg.length > 3) {
                            val n = msg.substring(3).toIntOrNull() ?: 0
                            if (n > 0) onPending?.invoke()
                        }
                    }
                    msg == "BADPIN" -> badPin = true
                    msg.startsWith("AIRPAD!") -> {
                        val ip = p.address.hostAddress ?: ""
                        if (ip.isNotEmpty() && ip != host) {
                            host = ip
                            prefs.edit().putString("host", ip).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                if (s.isClosed) break
            }
        }
    }

    /** Called every ~2 seconds: keeps the connection alive / searches for the PC. */
    fun tick() {
        exec.execute {
            val s = sock ?: return@execute
            val connected = System.currentTimeMillis() - lastOk < 6000
            if (pin.isEmpty()) {
                onState("Tap 'PIN / IP' and enter the PIN shown on the PC", false)
            } else if (badPin) {
                onState("Wrong PIN", false)
            } else if (host.isNotEmpty() && connected) {
                onState("Connected: $host", true)
            } else if (host.isEmpty()) {
                onState("Searching for PC... (start AirPad Server)", false)
            } else {
                onState("Waiting for PC ($host)...", false)
            }
            if (host.isNotEmpty() && pin.isNotEmpty()) raw(s, host, "$pin|P|")
            if (!connected) discover(s)
        }
    }

    @Suppress("DEPRECATION")
    private fun discover(s: DatagramSocket) {
        val b = "AIRPAD?".toByteArray()
        val targets = ArrayList<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val d = wm.dhcpInfo
            if (d != null && d.ipAddress != 0 && d.netmask != 0) {
                val bc = (d.ipAddress and d.netmask) or d.netmask.inv()
                targets.add(
                    InetAddress.getByAddress(
                        byteArrayOf(bc.toByte(), (bc shr 8).toByte(), (bc shr 16).toByte(), (bc shr 24).toByte())
                    )
                )
            }
        } catch (_: Exception) {
        }
        for (t in targets) {
            try {
                s.send(DatagramPacket(b, b.size, t, port))
            } catch (_: Exception) {
            }
        }
    }

    private fun raw(s: DatagramSocket, h: String, msg: String) {
        try {
            val b = msg.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getByName(h), port))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------- settings
    fun setConfig(newPin: String, newHost: String) {
        pin = newPin.trim()
        host = newHost.trim()
        badPin = false
        lastOk = 0L
        prefs.edit().putString("pin", pin).putString("host", host).apply()
        tick()
    }

    fun findPc() {
        host = ""
        lastOk = 0L
        prefs.edit().remove("host").apply()
        tick()
    }

    // ---------------------------------------------------------- commands
    private fun cmd(c: String, a: String) {
        val h = host
        val p = pin
        if (h.isEmpty() || p.isEmpty()) return
        exec.execute {
            val s = sock
            if (s != null) raw(s, h, "$p|$c|$a")
        }
    }

    fun move(dx: Float, dy: Float) {
        accX += dx
        accY += dy
        val ix = accX.toInt().coerceIn(-127, 127)
        val iy = accY.toInt().coerceIn(-127, 127)
        accX -= ix
        accY -= iy
        if (ix != 0 || iy != 0) cmd("M", "$ix,$iy")
    }

    fun wheel(steps: Int) = cmd("W", steps.coerceIn(-50, 50).toString())

    /** mask: 1=left 2=right 4=middle */
    fun mouseButton(mask: Int, down: Boolean) = cmd("B", "$mask," + (if (down) 1 else 0))

    fun click(mask: Int) = cmd("C", mask.toString())

    /** Windows virtual-key code + modifiers (1=Ctrl 2=Shift 4=Alt 8=Win) */
    fun key(vk: Int, mods: Int) = cmd("K", "$vk,$mods")

    fun text(s: String) {
        for (part in s.chunked(150)) cmd("T", part)
    }
}
