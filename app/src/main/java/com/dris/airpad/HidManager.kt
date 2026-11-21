package com.dris.airpad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Makes the phone behave as a Bluetooth HID keyboard + mouse (Android 9+).
 */
@SuppressLint("MissingPermission")
class HidManager(private val ctx: Context, private val onState: (String, Boolean) -> Unit) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("airpad", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var proxyRequested = false
    @Volatile var registered = false
    @Volatile var connected = false

    private var buttons = 0
    private var accX = 0f
    private var accY = 0f

    companion object {
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // ---- Keyboard (report id 1) ----
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // ---- Mouse (report id 2) ----
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02,
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0
        ).map { it.toByte() }.toByteArray()
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidManager.registered = registered
            if (registered) {
                onState("Ready - connecting to PC...", false)
                autoConnect()
            } else {
                onState("Bluetooth HID not available", false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    connected = true
                    prefs.edit().putString("last", device.address).apply()
                    onState("Connected: " + (device.name ?: device.address), true)
                }
                BluetoothProfile.STATE_CONNECTING -> onState("Connecting...", false)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    onState("Disconnected - waiting for PC", false)
                }
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "AirPad", "Phone keyboard and mouse", "AirPad",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
            )
            h.registerApp(sdp, null, null, exec, callback)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
            connected = false
            proxyRequested = false
        }
    }

    /** Called every few seconds from the UI: starts HID and keeps retrying the auto-connect. */
    fun tick() {
        val a = adapter ?: run { onState("Bluetooth not supported", false); return }
        if (!a.isEnabled) {
            connected = false
            onState("Turn Bluetooth ON", false)
            return
        }
        if (hid == null) {
            if (!proxyRequested) {
                proxyRequested = true
                a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
            }
            return
        }
        if (registered && !connected) autoConnect()
    }

    fun autoConnect() {
        val h = hid ?: return
        val a = adapter ?: return
        if (!registered || connected) return
        val bonded = a.bondedDevices ?: return
        val last = prefs.getString("last", null)
        val target = bonded.firstOrNull { it.address == last }
            ?: bonded.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
        if (target != null) h.connect(target) else onState("Pair with the PC first (see Pair button)", false)
    }

    fun bondedList(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connectTo(d: BluetoothDevice) {
        prefs.edit().putString("last", d.address).apply()
        hid?.connect(d)
    }

    fun stop() {
        try {
            hid?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------ keyboard
    private fun keyReport(mod: Int, usage: Int) {
        val d = host ?: return
        if (!connected) return
        hid?.sendReport(d, 1, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
    }

    /** press + release one key (usage id) with modifier bitmask (1=Ctrl 2=Shift 4=Alt 8=Win). */
    fun key(mod: Int, usage: Int) {
        keyReport(mod, usage)
        keyReport(0, 0)
    }

    // ------------------------------------------------ mouse
    private fun mouse(dx: Int, dy: Int, wheel: Int) {
        val d = host ?: return
        if (!connected) return
        hid?.sendReport(d, 2, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    }

    fun move(dx: Float, dy: Float) {
        accX += dx
        accY += dy
        val ix = accX.toInt().coerceIn(-127, 127)
        val iy = accY.toInt().coerceIn(-127, 127)
        accX -= ix
        accY -= iy
        if (ix != 0 || iy != 0) mouse(ix, iy, 0)
    }

    fun wheel(steps: Int) = mouse(0, 0, steps.coerceIn(-127, 127))

    /** mask: 1=left 2=right 4=middle */
    fun mouseButton(mask: Int, down: Boolean) {
        buttons = if (down) buttons or mask else buttons and mask.inv()
        mouse(0, 0, 0)
    }

    fun click(mask: Int) {
        mouseButton(mask, true)
        mouseButton(mask, false)
    }
}
