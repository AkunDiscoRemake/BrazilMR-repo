package com.brazilmr.core.session

import com.brazilmr.core.gesture.GestureActions

enum class EnvironmentMode { MR, VR }
class XrSession {
    var mode = EnvironmentMode.MR; private set
    var uiVisible = true; private set
    var uiAnchorX = 0f; private set
    var uiAnchorY = 0f; private set
    var revision = 0L; private set
    fun setMode(mode: EnvironmentMode) { if (this.mode != mode) { this.mode = mode; revision++ } }
    fun toggleMode() = setMode(if (mode == EnvironmentMode.MR) EnvironmentMode.VR else EnvironmentMode.MR)
    fun setUiVisible(visible: Boolean) { if (uiVisible != visible) { uiVisible = visible; revision++ } }
    fun recenter(x: Float = 0.5f, y: Float = 0.5f) {
        require(x.isFinite() && y.isFinite())
        uiAnchorX = (x.coerceIn(0f, 1f) - 0.5f) * 0.6f
        uiAnchorY = (0.5f - y.coerceIn(0f, 1f)) * 0.4f
        revision++
    }
    fun apply(actions: GestureActions) {
        if (actions.toggleMode) toggleMode()
        if (actions.hideUi) setUiVisible(false)
        if (actions.showUi) { recenter(actions.handX, actions.handY); setUiVisible(true) }
    }
}
