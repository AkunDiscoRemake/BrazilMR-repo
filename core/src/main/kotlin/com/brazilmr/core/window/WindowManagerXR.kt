package com.brazilmr.core.window

enum class AppType { GAME, WINDOW }
enum class WindowContent { NOTES, CLOCK, DIAGNOSTICS, LUA, ANDROID, CAPTURE }

class XRWindow internal constructor(
    val id: Int, val appId: String, val owner: String, var title: String,
    val appType: AppType, val content: WindowContent,
    var x: Float, var y: Float, var width: Float, var height: Float,
) {
    var minimized = false; internal set
    var focused = false; internal set
    var text = ""
    var displayId = -1
    var status = ""
    fun contains(px: Float, py: Float) = !minimized && px >= x && px <= x + width && py >= y && py <= y + height
}

/** All mutations on the host/UI thread. Multi Window is the only execution model. */
class WindowManagerXR(private val maxWindows: Int = 12) {
    private val ordered = ArrayList<XRWindow>(maxWindows)
    val windows: List<XRWindow> get() = ordered
    var revision = 0L; private set
    private var nextId = 1
    fun open(appId: String, title: String, type: AppType = AppType.WINDOW, content: WindowContent = WindowContent.LUA, owner: String = appId): XRWindow {
        check(ordered.size < maxWindows) { "Limite de $maxWindows janelas atingido" }
        require(appId.isNotBlank() && title.length <= 120 && owner.isNotBlank())
        val index = ordered.size % 5
        val window = XRWindow(nextId++, appId, owner, title, type, content, 0.03f + index * 0.055f, 0.04f + index * 0.05f, 0.47f, 0.55f)
        ordered.add(window); focus(window.id)
        return window
    }
    fun get(id: Int) = ordered.firstOrNull { it.id == id }
    fun focus(id: Int): Boolean {
        val target = get(id) ?: return false
        for (window in ordered) window.focused = false
        target.focused = true; target.minimized = false
        ordered.remove(target); ordered.add(target); revision++
        return true
    }
    fun blur() { for (w in ordered) w.focused = false; revision++ }
    fun minimize(id: Int) {
        val target = get(id) ?: return
        val wasFocused = target.focused
        target.minimized = true; target.focused = false
        if (wasFocused) focusTopVisible()
        revision++
    }
    fun close(id: Int): XRWindow? {
        val target = get(id) ?: return null
        ordered.remove(target)
        if (target.focused) focusTopVisible()
        revision++
        return target
    }
    private fun focusTopVisible() { ordered.lastOrNull { !it.minimized }?.focused = true }
    fun move(id: Int, x: Float, y: Float) {
        require(x.isFinite() && y.isFinite())
        val target = get(id) ?: return
        target.x = x.coerceIn(0f, 1f - target.width)
        target.y = y.coerceIn(0f, 1f - target.height)
        revision++
    }
    fun resize(id: Int, width: Float, height: Float) {
        require(width.isFinite() && height.isFinite())
        val target = get(id) ?: return
        target.width = width.coerceIn(0.25f, 1f)
        target.height = height.coerceIn(0.25f, 1f)
        move(id, target.x, target.y)
    }
    fun tile() {
        val visible = ordered.count { !it.minimized }
        if (visible == 0) return
        val columns = if (visible == 1) 1 else if (visible <= 4) 2 else 3
        val rows = (visible + columns - 1) / columns
        var index = 0
        for (window in ordered) if (!window.minimized) {
            window.x = (index % columns) / columns.toFloat() + 0.008f
            window.y = (index / columns) / rows.toFloat() + 0.008f
            window.width = 1f / columns - 0.016f; window.height = 1f / rows - 0.016f
            index++
        }
        revision++
    }
    fun hitTest(x: Float, y: Float): XRWindow? {
        for (i in ordered.indices.reversed()) if (ordered[i].contains(x, y)) return ordered[i]
        return null
    }
    fun assertOwner(id: Int, owner: String) {
        check(get(id)?.owner == owner) { "Janela não pertence a este principal" }
    }
}
