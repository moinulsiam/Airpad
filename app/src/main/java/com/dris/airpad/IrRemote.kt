package com.dris.airpad

import android.content.Context
import android.hardware.ConsumerIrManager

/** LG TV remote using the phone's IR blaster (NEC protocol, address 0x04). */
class IrRemote(ctx: Context) {
    private val mgr = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean get() = mgr?.hasIrEmitter() == true

    fun send(cmd: Int) {
        val m = mgr ?: return
        if (!m.hasIrEmitter()) return
        m.transmit(38000, pattern(0x04, cmd))
    }

    private fun pattern(addr: Int, cmd: Int): IntArray {
        val l = ArrayList<Int>()
        l.add(9000)
        l.add(4500)
        val bytes = intArrayOf(addr, addr.inv() and 0xFF, cmd, cmd.inv() and 0xFF)
        for (b in bytes) {
            for (i in 0..7) {
                l.add(560)
                l.add(if (((b shr i) and 1) == 1) 1690 else 560)
            }
        }
        l.add(560)
        return l.toIntArray()
    }
}
