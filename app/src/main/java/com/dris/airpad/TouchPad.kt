package com.dris.airpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Big touchpad area.
 *  - 1 finger drag  : move cursor
 *  - 1 finger tap   : left click
 *  - 2 finger tap   : right click
 *  - 2 finger drag  : scroll
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchPad(ctx: Context, private val hid: WifiLink) : View(ctx) {

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travel = 0f
    private var maxPtr = 0
    private var scrollAcc = 0f

    private val speed = 2.0f

    init {
        background = GradientDrawable().apply {
            setColor(0xFF1E272E.toInt())
            cornerRadius = 24f
            setStroke(3, 0xFF546E7A.toInt())
        }
    }

    private fun cx(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getX(i); n++ }
        return if (n > 0) s / n else 0f
    }

    private fun cy(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getY(i); n++ }
        return if (n > 0) s / n else 0f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = cx(e); lastY = cy(e)
                downTime = e.eventTime
                travel = 0f; maxPtr = 1; scrollAcc = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPtr = maxOf(maxPtr, e.pointerCount)
                lastX = cx(e); lastY = cy(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = cx(e, e.actionIndex); lastY = cy(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = cx(e); val y = cy(e)
                val dx = x - lastX; val dy = y - lastY
                lastX = x; lastY = y
                travel += abs(dx) + abs(dy)
                if (e.pointerCount >= 2) {
                    scrollAcc += dy
                    val steps = (scrollAcc / 20f).toInt()
                    if (steps != 0) {
                        hid.wheel(-steps)
                        scrollAcc -= steps * 20f
                    }
                } else {
                    hid.move(dx * speed, dy * speed)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (travel < 15f && e.eventTime - downTime < 250) {
                    if (maxPtr >= 2) hid.click(2) else hid.click(1)
                }
            }
        }
        return true
    }
}
