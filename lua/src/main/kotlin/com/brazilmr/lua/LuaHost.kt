package com.brazilmr.lua

import com.brazilmr.core.permission.Principal
import com.brazilmr.core.session.XrSession
import com.brazilmr.core.spatial.Scenario
import com.brazilmr.core.tracking.HandFrame
import com.brazilmr.core.window.WindowManagerXR

class UiElement(
    val id: Int, val owner: String, val windowId: Int, val kind: String,
    var text: String = "", var x: Float = 0.05f, var y: Float = 0.08f,
    var width: Float = 0.85f, var height: Float = 0.18f,
    var color: Int = 0xffeeeaf6.toInt(), var background: Int = 0xff6f40cf.toInt(),
    var fontSize: Float = 22f, var visible: Boolean = true,
)

class UiElementStore {
    private val items = LinkedHashMap<Int, UiElement>()
    val elements: Collection<UiElement> get() = items.values
    private var nextId = 1
    var revision = 0L; private set
    fun create(owner: String, windowId: Int, kind: String): UiElement {
        require(kind in setOf("button", "text", "panel"))
        check(items.values.count { it.owner == owner } < 128 && items.size < 1024) { "Limite de elementos UI" }
        val element = UiElement(nextId++, owner, windowId, kind)
        items[element.id] = element; revision++
        return element
    }
    fun owned(owner: String, id: Int): UiElement = items[id]?.takeIf { it.owner == owner } ?: error("Elemento não pertence ao app")
    fun changed() { revision++ }
    fun remove(owner: String, id: Int) { owned(owner, id); items.remove(id); revision++ }
    fun removeWindow(windowId: Int) { items.values.removeAll { it.windowId == windowId }; revision++ }
    fun clear(owner: String) { items.values.removeAll { it.owner == owner }; revision++ }
}

/** Nothing here is exposed as Java userdata. sync marshals operations to the host's owner thread. */
abstract class LuaHost(
    val windows: WindowManagerXR,
    val ui: UiElementStore,
    val session: XrSession,
    val scenario: Scenario,
    val hands: HandFrame,
) {
    abstract fun <T> sync(action: () -> T): T
    abstract fun log(principal: Principal, message: String)
    abstract fun changed()
    open fun windowClosed(id: Int) { ui.removeWindow(id) }
    open fun modeChanged() {}
    open fun nowMillis(): Long = System.nanoTime() / 1_000_000
    open fun spatialStatus(): String = "3DoF"
}
