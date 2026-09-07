package com.brazilmr.ui

import com.brazilmr.core.performance.XrSettings
import com.brazilmr.core.permission.Capability
import com.brazilmr.core.window.WindowContent
import com.brazilmr.platform.LauncherApp
import com.brazilmr.platform.ScriptApp

interface UiActions {
    fun openPhoneTools(page: com.brazilmr.platform.Page) = Unit
    fun requestCamera()
    fun updateSettings(settings: XrSettings)
    fun toggleMode()
    fun recenter()
    fun hideUi()
    fun openBuiltin(content: WindowContent)
    fun launchOutside(app: LauncherApp) = Unit
    fun openAndroid(app: LauncherApp)
    fun runScript(app: ScriptApp)
    fun stopScripts()
    fun editScript()
    fun selectScript(index: Int)
    fun showDocumentation()
    fun changeCapability(app: ScriptApp, capability: Capability)
    fun requestAccessibility()
    fun requestCapture()
    fun closeWindow(id: Int)
    fun minimizeWindow(id: Int)
    fun windowResized(id: Int)
    fun editNotes(id: Int)
    fun clickLuaElement(id: Int)
    fun windowPointer(id: Int, x: Float, y: Float, action: String, source: String)
    fun externalGesture(id: Int, x0: Float, y0: Float, x1: Float, y1: Float, duration: Long)
}
