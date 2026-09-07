package com.brazilmr.render

import android.graphics.Bitmap

/** Two slots with explicit ownership. GL never reads pixels while Canvas is writing them. */
class UiTextureExchange(val width: Int = 1600, val height: Int = 900) {
    val bitmaps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val states = IntArray(2)
    private var latest = -1
    @Synchronized fun beginWrite(): Int {
        for (i in states.indices) if (states[i] == FREE) { states[i] = WRITING; return i }
        return -1
    }
    @Synchronized fun publish(index: Int) {
        check(states[index] == WRITING)
        if (latest >= 0 && states[latest] == READY) states[latest] = FREE
        states[index] = READY; latest = index
    }
    @Synchronized fun cancelWrite(index: Int) { states[index] = FREE }
    @Synchronized fun beginRead(): Int {
        val index = latest
        if (index < 0 || states[index] != READY) return -1
        states[index] = READING; latest = -1; return index
    }
    @Synchronized fun endRead(index: Int) { check(states[index] == READING); states[index] = FREE }
    private companion object { const val FREE = 0; const val WRITING = 1; const val READY = 2; const val READING = 3 }
}
