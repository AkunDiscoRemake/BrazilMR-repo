package com.brazilmr.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager

class ThermalMonitor(context: Context, private val onStatus: (Int) -> Unit) : AutoCloseable {
    private val power = context.getSystemService(PowerManager::class.java)
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    fun start() {
        if (Build.VERSION.SDK_INT >= 29 && listener == null) {
            val next = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
            listener = next; power.addThermalStatusListener(next); onStatus(power.currentThermalStatus)
        }
    }
    override fun close() {
        if (Build.VERSION.SDK_INT >= 29) listener?.let { power.removeThermalStatusListener(it) }
        listener = null
    }
}
