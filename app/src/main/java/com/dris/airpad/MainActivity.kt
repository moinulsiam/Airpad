package com.dris.airpad

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

@SuppressLint("ClickableViewAccessibility")
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val NORMAL = 0xFF37474F.toInt()
    private val ACTIVE = 0xFF1565C0.toInt()

    private lateinit var link: WifiLink
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

    private val ticker = object : Runnable {
        override fun run() {
            link.tick()
            ui.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = WifiLink(this) { text, ok ->
            ui.post {
                status.text = text
                status.background = round(if (ok) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt())
            }
        }
        ir = IrRemote(this)
        buildUi()
        link.start()
        ui.postDelayed(ticker, 800)
        if (link.pin.isEmpty()) ui.post { settings() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        link.stop()
        super.onDestroy()
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
            btn("PIN / IP") { settings() },
            btn("Find PC") { link.findPc() }
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

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val pinE = EditText(this).apply {
            hint = "PIN (shown on the PC window)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(link.pin)
        }
        val ipE = EditText(this).apply {
            hint = "PC IP (leave empty = auto find)"
            setText(link.host)
        }
        box.addView(pinE)
        box.addView(ipE)
        AlertDialog.Builder(this)
            .setTitle("PC connection")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                link.setConfig(pinE.text.toString(), ipE.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ touchpad tab
    private fun buildPad(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TouchPad(this, link), LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
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
                            link.mouseButton(mask, true)
                            v.alpha = 0.6f
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            link.mouseButton(mask, false)
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

    /** vk = Windows virtual-key code */
    private fun press(vk: Int, extra: Int = 0) {
        link.key(vk, modMask or extra)
        clearMods()
    }

    private fun sendTyped(s: String) {
        val sb = StringBuilder()
        for (c in s) {
            if (modMask != 0 && c.code < 128 && c.isLetterOrDigit()) {
                if (sb.isNotEmpty()) {
                    link.text(sb.toString())
                    sb.setLength(0)
                }
                link.key(c.uppercaseChar().code, modMask)
                clearMods()
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) link.text(sb.toString())
    }

    private fun k(label: String, vk: Int, mod: Int = 0): Button = btn(label) { press(vk, mod) }

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
            hint = "Tap here and type"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF90A4AE.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
                    repeat(prev.length - p) { press(0x08) }
                    sendTyped(now.substring(p))
                    prev = now
                    if (now.length > 60) {
                        suppress = true
                        setText("")
                        prev = ""
                        suppress = false
                    }
                }
            })
            setOnEditorActionListener { _, _, _ -> press(0x0D); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_DEL && text.isEmpty()) {
                        press(0x08); true
                    } else if (code == KeyEvent.KEYCODE_ENTER) {
                        press(0x0D); true
                    } else false
                } else false
            }
        }
        col.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })

        col.addView(rowOf(k("Esc", 0x1B), k("Tab", 0x09), k("Enter", 0x0D), k("Bksp", 0x08)))
        col.addView(rowOf(modBtn("Ctrl", 1), modBtn("Alt", 4), modBtn("Shift", 2), modBtn("Win", 8)))
        col.addView(rowOf(k("Home", 0x24), k("End", 0x23), k("PgUp", 0x21), k("PgDn", 0x22)))
        col.addView(rowOf(k("←", 0x25), k("↑", 0x26), k("↓", 0x28), k("→", 0x27)))
        col.addView(rowOf(k("Del", 0x2E), k("Space", 0x20), btn("Start") { link.key(0x5B, 0) }, k("Alt+Tab", 0x09, 4)))
        col.addView(rowOf(k("Copy", 0x43, 1), k("Paste", 0x56, 1), k("Cut", 0x58, 1), k("All", 0x41, 1)))
        col.addView(rowOf(k("Undo", 0x5A, 1), k("Save", 0x53, 1), k("F5", 0x74), k("Alt+F4", 0x73, 4)))
        col.addView(rowOf(k("Vol −", 0xAE), k("Vol +", 0xAF), k("Mute", 0xAD), k("Play", 0xB3)))

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
