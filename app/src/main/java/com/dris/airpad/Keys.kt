package com.dris.airpad

/** Char -> (HID usage id, needs shift) */
object Keys {
    val map = HashMap<Char, Pair<Int, Boolean>>()

    init {
        for (c in 'a'..'z') map[c] = Pair(0x04 + (c - 'a'), false)
        for (c in 'A'..'Z') map[c] = Pair(0x04 + (c - 'A'), true)
        for (c in '1'..'9') map[c] = Pair(0x1E + (c - '1'), false)
        map['0'] = Pair(0x27, false)

        val shiftedDigits = "!@#\$%^&*()"
        for (i in shiftedDigits.indices) map[shiftedDigits[i]] = Pair(0x1E + i, true)

        val plain = "\n\t -=[]\\;'`,./"
        val plainUsage = intArrayOf(0x28, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        for (i in plain.indices) map[plain[i]] = Pair(plainUsage[i], false)

        val shifted = "_+{}|:\"~<>?"
        val shiftedUsage = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        for (i in shifted.indices) map[shifted[i]] = Pair(shiftedUsage[i], true)
    }
}
