package com.dris.airpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val NORMAL = 0xFF37474F.toInt()
    private val ACTIVE = 0xFF1565C0.toInt()

    private lateinit var hid: HidManager
    private lateinit var ir: IrRemote
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private lateinit var tabPad: View
    private lateinit var tabKeys: View
    private lateinit var tabTv: View
    private lateinit var edit: EditText

    private var modMask = 0
    private val modBtns = HashMap<Int, Button>()
    private var prev = ""
    private var suppress = false
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            if (started) hid.tick()
            ui.postDelayed(this, 3000)
        }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hid = HidManager(this) { text, ok ->
            ui.post {
                status.text = text
                status.background = round(if (ok) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt())
            }
        }
        ir = IrRemote(this)
        buildUi()
        if (hasPerms()) startHid() else requestPermissions(perms(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasPerms()) startHid() else status.text = "Bluetooth permission needed"
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        hid.stop()
        super.onDestroy()
    }

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf<String>()

    private fun hasPerms(): Boolean =
        perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startHid() {
        started = true
        hid.tick()
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 2000)
    }

    // ------------------------------------------------------------ helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun btn(label: String, act: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = round(NORMAL)
        setPadding(0, 0, 0, 0)
        setOnClickListener { act() }
    }

    private fun rowOf(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) {
            addView(x, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun spacer(): View = View(this)

    // ------------------------------------------------------------ UI
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        status = TextView(this).apply {
            text = "Starting..."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = round(0xFFB71C1C.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(rowOf(
            btn("Pair (make visible)") {
                startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                )
            },
            btn("Select PC") { choosePc() }
        ))
        root.addView(rowOf(
            btn("Touchpad") { show(0) },
            btn("Keyboard") { show(1) },
            btn("TV Remote") { show(2) }
        ))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        tabPad = buildPad()
        tabKeys = buildKeys()
        tabTv = buildTv()
        content.addView(tabPad, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(tabKeys, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(tabTv, FrameLayout.LayoutParams(MATCH, MATCH))
        show(0)
        setContentView(root)
    }

    private fun show(i: Int) {
        tabPad.visibility = if (i == 0) View.VISIBLE else View.GONE
        tabKeys.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabTv.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i != 1) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(content.windowToken, 0)
        }
    }

    private fun choosePc() {
        val list = hid.bondedList()
        if (list.isEmpty()) {
            status.text = "No paired device. Pair from the PC first."
            return
        }
        val names = list.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose PC")
            .setItems(names) { _, i -> hid.connectTo(list[i]) }
            .show()
    }

    // ------------------------------------------------------------ touchpad tab
    private fun buildPad(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TouchPad(this, hid), LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(Triple("Left", 1, 2f), Triple("Middle", 4, 1f), Triple("Right", 2, 2f))
        for ((label, mask, w) in defs) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = round(NORMAL)
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            hid.mouseButton(mask, true)
                            v.alpha = 0.6f
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            hid.mouseButton(mask, false)
                            v.alpha = 1f
                        }
                    }
                    true
                }
            }
            r.addView(b, LinearLayout.LayoutParams(0, dp(84), w).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        col.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return col
    }

    // ------------------------------------------------------------ keyboard tab
    private fun clearMods() {
        modMask = 0
        for (b in modBtns.values) b.background = round(NORMAL)
    }

    private fun press(usage: Int, extra: Int = 0) {
        hid.key(modMask or extra, usage)
        clearMods()
    }

    private fun typeChar(c: Char) {
        val e = Keys.map[c] ?: return
        hid.key(modMask or (if (e.second) 2 else 0), e.first)
        clearMods()
    }

    private fun k(label: String, usage: Int, mod: Int = 0): Button = btn(label) { press(usage, mod) }

    private fun modBtn(label: String, bit: Int): Button {
        val b = btn(label) {}
        b.setOnClickListener {
            modMask = modMask xor bit
            b.background = round(if ((modMask and bit) != 0) ACTIVE else NORMAL)
        }
        modBtns[bit] = b
        return b
    }

    private fun buildKeys(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        edit = EditText(this).apply {
            hint = "Tap here and type (English)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF90A4AE.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_NONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppress) return
                    val now = s?.toString() ?: ""
                    var p = 0
                    val m = minOf(prev.length, now.length)
                    while (p < m && prev[p] == now[p]) p++
                    repeat(prev.length - p) { press(0x2A) }
                    for (i in p until now.length) typeChar(now[i])
                    prev = now
                    if (now.length > 60) {
                        suppress = true
                        setText("")
                        prev = ""
                        suppress = false
                    }
                }
            })
            setOnEditorActionListener { _, _, _ -> press(0x28); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_DEL && text.isEmpty()) {
                        press(0x2A); true
                    } else if (code == KeyEvent.KEYCODE_ENTER) {
                        press(0x28); true
                    } else false
                } else false
            }
        }
        col.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })

        col.addView(rowOf(k("Esc", 0x29), k("Tab", 0x2B), k("Enter", 0x28), k("Bksp", 0x2A)))
        col.addView(rowOf(modBtn("Ctrl", 1), modBtn("Alt", 4), modBtn("Shift", 2), modBtn("Win", 8)))
        col.addView(rowOf(k("Home", 0x4A), k("End", 0x4D), k("PgUp", 0x4B), k("PgDn", 0x4E)))
        col.addView(rowOf(k("←", 0x50), k("↑", 0x52), k("↓", 0x51), k("→", 0x4F)))
        col.addView(rowOf(k("Del", 0x4C), k("Space", 0x2C), btn("Start") { hid.key(8, 0) }, k("Alt+Tab", 0x2B, 4)))
        col.addView(rowOf(k("Copy", 0x06, 1), k("Paste", 0x19, 1), k("Cut", 0x1B, 1), k("All", 0x04, 1)))
        col.addView(rowOf(k("Undo", 0x1D, 1), k("Save", 0x16, 1), k("F5", 0x3E), k("Alt+F4", 0x3D, 4)))

        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }

    // ------------------------------------------------------------ TV remote tab (LG, IR)
    private fun t(label: String, cmd: Int): Button = btn(label) { ir.send(cmd) }

    private fun buildTv(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (!ir.available) {
            col.addView(TextView(this).apply {
                text = "No IR blaster found on this phone."
                setTextColor(0xFFFFAB00.toInt())
                setPadding(dp(6), dp(6), dp(6), dp(6))
            })
        }
        col.addView(rowOf(t("Power", 0x08), t("Input", 0x0B), t("Mute", 0x09)))
        col.addView(rowOf(t("Vol +", 0x02), t("Vol −", 0x03), t("CH +", 0x00), t("CH −", 0x01)))
        col.addView(rowOf(spacer(), t("▲", 0x40), spacer()))
        col.addView(rowOf(t("◀", 0x07), t("OK", 0x44), t("▶", 0x06)))
        col.addView(rowOf(spacer(), t("▼", 0x41), spacer()))
        col.addView(rowOf(t("Back", 0x28), t("Home", 0x7C), t("Menu", 0x43)))
        col.addView(rowOf(t("1", 0x11), t("2", 0x12), t("3", 0x13)))
        col.addView(rowOf(t("4", 0x14), t("5", 0x15), t("6", 0x16)))
        col.addView(rowOf(t("7", 0x17), t("8", 0x18), t("9", 0x19)))
        col.addView(rowOf(spacer(), t("0", 0x10), spacer()))

        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }
}
