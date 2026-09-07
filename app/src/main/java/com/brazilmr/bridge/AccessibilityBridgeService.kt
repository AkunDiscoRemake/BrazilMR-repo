package com.brazilmr.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** Session-local allowlist populated only by explicit app launches from the XR launcher. */
object AccessibilitySession {
    @Volatile var userConsented = false
    private val displays = HashMap<Int, String>()
    @Synchronized fun authorize(display: Int, app: String) { displays[display] = app }
    @Synchronized fun permits(display: Int, app: String) = userConsented && displays[display] == app
    @Synchronized fun remove(display: Int) { displays.remove(display) }
    @Synchronized fun clear() { displays.clear(); userConsented = false }
}

class AccessibilityBridgeService : AccessibilityService() {
    companion object { @Volatile var connected: AccessibilityBridgeService? = null; private set }
    private var busy = false
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // no event content collection or telemetry
    override fun onInterrupt() { busy = false }
    override fun onDestroy() { connected = null; AccessibilitySession.clear(); super.onDestroy() }

    /** Returns a denial reason, or null if Android accepted this user-generated click/drag. */
    fun gesture(app: String, displayId: Int, x0: Float, y0: Float, x1: Float, y1: Float, duration: Long): String? {
        if (!AccessibilitySession.permits(displayId, app)) return "Controle não autorizado nesta sessão."
        if (busy) return "Aguarde o gesto anterior terminar."
        if (Build.VERSION.SDK_INT < 30 && displayId != Display.DEFAULT_DISPLAY) return "Input em displays secundários requer Android 11+."
        if (listOf(x0, y0, x1, y1).any { !it.isFinite() || it !in 0f..1f }) return "Ponteiro fora do conteúdo."
        val candidates: List<AccessibilityWindowInfo> = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays.get(displayId) ?: emptyList() else windows
        var bounds: Rect? = null
        for (window in candidates) {
            if (!window.isActive && !window.isFocused) continue
            val root = window.root ?: continue
            val sameApp = root.packageName?.toString() == app
            @Suppress("DEPRECATION") root.recycle()
            if (sameApp) { bounds = Rect().also { window.getBoundsInScreen(it) }; break }
        }
        val target = bounds ?: return "O app autorizado não está focado no display de destino. Nenhum input foi injetado."
        if (target.isEmpty) return "Conteúdo do app indisponível."
        val path = Path().apply {
            moveTo(target.left + x0 * (target.width()-1), target.top + y0 * (target.height()-1))
            if (x0 != x1 || y0 != y1) lineTo(target.left + x1 * (target.width()-1), target.top + y1 * (target.height()-1))
        }
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1, 1500)))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        busy = true
        val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { busy = false }
            override fun onCancelled(gestureDescription: GestureDescription?) { busy = false }
        }, null)
        if (!accepted) { busy = false; return "O Android recusou a interação."
        }
        return null
    }
}
