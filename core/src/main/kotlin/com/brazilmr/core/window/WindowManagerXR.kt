package com.brazilmr.core.window

import com.brazilmr.core.spatial.PanelPose
import com.brazilmr.core.spatial.HeadsetLayout
import kotlin.math.*

enum class AppType { GAME, WINDOW }
enum class WindowContent { NOTES, CLOCK, DIAGNOSTICS, LUA, ANDROID, CAPTURE }

class XRWindow internal constructor(
    val id: Int, val appId: String, val owner: String, var title: String,
    val appType: AppType, val content: WindowContent,
    var x: Float, var y: Float, var width: Float, var height: Float,
) {
    val pose=PanelPose()
    var minimized = false; internal set
    var focused = false; internal set
    var text = ""
    var hasSurfaceFrame=false
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
        if(content==WindowContent.CLOCK)HeadsetLayout.clock(window.pose) else HeadsetLayout.window(window.pose,ordered.count { it.content!=WindowContent.CLOCK })
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
        target.pose.x=(target.x+target.width*.5f-.5f)*3f;target.pose.y=(.5f-target.y-target.height*.5f)*1.8f
        target.pose.yaw=atan2(-target.pose.x,-target.pose.z)*180f/PI.toFloat()
        revision++
    }
    fun resize(id: Int, width: Float, height: Float) {
        require(width.isFinite() && height.isFinite())
        val target = get(id) ?: return
        val factor=min(width.coerceIn(.25f,1f)/target.width,height.coerceIn(.25f,1f)/target.height)
        scaleSpatial(id,factor)
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
    fun place(id: Int,x: Float,y: Float,z: Float,yaw: Float?=null) {
        require(x.isFinite() && y.isFinite() && z.isFinite() && (yaw==null || yaw.isFinite()))
        val p=get(id)?.pose ?: return
        p.x=x.coerceIn(-4f,4f);p.y=y.coerceIn(-2.5f,2.5f);p.z=z.coerceIn(-4f,-.8f)
        p.yaw=(yaw ?: atan2(-p.x,-p.z)*180f/PI.toFloat()).coerceIn(-70f,70f);revision++
    }
    fun scaleSpatial(id: Int,scale: Float) {
        require(scale.isFinite())
        val p=get(id)?.pose ?: return
        val factor=scale.coerceIn(.12f/p.width,minOf(1.6f/p.width,1.2f/p.height))
        p.width*=factor;p.height*=factor;revision++
    }
    fun distance(id: Int,distance: Float) {
        require(distance.isFinite())
        val p=get(id)?.pose ?: return
        val radius=sqrt(p.x*p.x+p.z*p.z);val factor=distance.coerceIn(1f,3.5f)/radius.coerceAtLeast(.1f)
        place(id,p.x*factor,p.y*factor,p.z*factor,p.yaw)
    }
    fun arrangeSpatial() {
        var i=0
        for(w in ordered) if(!w.minimized) { if(w.content==WindowContent.CLOCK) HeadsetLayout.clock(w.pose) else HeadsetLayout.window(w.pose,i++) }
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
