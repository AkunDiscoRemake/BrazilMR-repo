package com.brazilmr.scripting

import android.os.Handler
import android.os.Looper
import com.brazilmr.core.permission.Principal
import com.brazilmr.core.window.AppType
import com.brazilmr.core.window.WindowContent
import com.brazilmr.lua.LuaHost
import com.brazilmr.lua.LuaRuntime
import com.brazilmr.platform.PlatformState
import com.brazilmr.platform.ScriptApp
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded, serial worker. UI/host mutations are marshalled to main; user Lua never runs on main. */
class AndroidLuaController(
    private val state: PlatformState,
    private val onModeChanged: () -> Unit,
    private val onWindowClosed: (Int) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private class Slot(val app: ScriptApp, val windowId: Int) {
        @Volatile var runtime: LuaRuntime? = null
        @Volatile var cancelled = false
        val tickPending = AtomicBoolean(false)
        val movePending = AtomicBoolean(false)
        var focused = false
        var minimized = false
    }
    private val slots = ConcurrentHashMap<Int, Slot>()
    private val worker = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(32), { job -> Thread(job,"BrazilMR-Lua") }, ThreadPoolExecutor.AbortPolicy())
    @Volatile private var closed = false
    private val host = object : LuaHost(state.windows, state.elements, state.session, state.scenario, state.hands) {
        override fun <T> sync(action: () -> T): T {
            check(!closed) { "Host encerrado" }
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            val task = FutureTask(action)
            main.post(task)
            try { return task.get(200,TimeUnit.MILLISECONDS) }
            catch (error: Exception) { task.cancel(false); throw IllegalStateException("Host indisponível ou ocupado",error) }
        }
        override fun log(principal: Principal,message: String) { main.post { if (!closed) state.log("${principal.id.substringBefore('@')} › $message") } }
        override fun changed() { state.dirty = true }
        override fun windowClosed(id: Int) { super.windowClosed(id); onWindowClosed(id) }
        override fun modeChanged() { onModeChanged() }
        override fun spatialStatus() = state.spatialStatus
    }
    fun run(app: ScriptApp) {
        val existing = slots.values.firstOrNull { it.app.principal == app.principal && !it.cancelled }
        if (existing != null) {
            if (existing.runtime?.running != false) { state.windows.focus(existing.windowId); state.dirty = true; return }
            state.windows.close(existing.windowId); closeWindow(existing.windowId)
        }
        check(slots.size < 3) { "Máximo de três runtimes Lua simultâneos" }
        state.permissions.register(app.principal,app.requested)
        val window = state.windows.open(app.id,app.title,app.type,WindowContent.LUA,app.principal.id)
        window.status = "Inicializando runtime sandboxed..."
        val slot = Slot(app,window.id); slots[window.id] = slot; state.dirty = true
        submit {
            if (slot.cancelled) return@submit
            try {
                val runtime = LuaRuntime(app.principal,window.id,app.type,state.permissions,host)
                slot.runtime = runtime
                if (slot.cancelled) { runtime.close(); return@submit }
                val success = runtime.run(app.source)
                main.post { state.windows.get(window.id)?.status = if (success) "Lua ativo" else "Parado · ${runtime.lastError}"; state.dirty = true }
            } catch (error: Exception) { main.post { state.log("Falha no runtime: ${error.message}"); state.windows.get(window.id)?.status = "Falha ao iniciar Lua" } }
        }
    }
    fun click(elementId: Int) {
        val element = state.elements.elements.firstOrNull { it.id == elementId } ?: return
        val slot = slots.values.firstOrNull { it.app.principal.id == element.owner } ?: return
        submit { if (!slot.cancelled) slot.runtime?.buttonClicked(elementId) }
    }
    fun input(windowId: Int) {
        val owner = state.windows.get(windowId)?.owner ?: return
        val slot = slots.values.firstOrNull { it.app.principal.id == owner } ?: return
        submit { if (!slot.cancelled) slot.runtime?.emit("input.click") }
    }
    fun pointer(windowId: Int, x: Float, y: Float, action: String, source: String) {
        val owner = state.windows.get(windowId)?.owner ?: return
        val slot = slots.values.firstOrNull { it.app.principal.id == owner } ?: return
        val move = action == "move"
        if (move && !slot.movePending.compareAndSet(false,true)) return
        val accepted = submit {
            try { if (!slot.cancelled) slot.runtime?.emitPointer(x,y,action,source) }
            finally { if (move) slot.movePending.set(false) }
        }
        if (!accepted) {
            if (move) slot.movePending.set(false)
            else { slot.runtime?.cancel(); state.log("Input Lua saturado; runtime interrompido para não perder release/cancel.") }
        }
    }
    fun windowStatesChanged() {
        for (slot in slots.values) {
            val window = state.windows.get(slot.windowId) ?: continue
            val focus = state.windows.windows.any { it.owner == slot.app.principal.id && it.focused }
            if (focus != slot.focused) { slot.focused=focus; submit { slot.runtime?.emit("window.focus",focus) } }
            if (window.minimized != slot.minimized) {
                slot.minimized=window.minimized; val minimized=slot.minimized
                submit { slot.runtime?.emit("window.minimized",minimized) }
            }
        }
    }
    fun environmentChanged(status: String) {
        for (slot in slots.values) submit { slot.runtime?.emit("scenario.tracking",status); slot.runtime?.emit("system.spatial",status) }
    }
    fun modeChanged(mode: String) { for (slot in slots.values) submit { if (!slot.cancelled) slot.runtime?.emit("system.mode",mode) } }
    fun permissionsChanged(principal: Principal) {
        for (slot in slots.values) if (slot.app.principal == principal) submit { slot.runtime?.refreshCapabilities(); slot.runtime?.emit("system.permissions") }
    }
    /** Caller throttles to 10 Hz; minimized windows and a hidden UI do not receive simulation ticks. */
    fun tick(seconds: Double) {
        if (!state.session.uiVisible) return
        for (slot in slots.values) {
            val window = state.windows.get(slot.windowId) ?: continue
            if (window.minimized || slot.cancelled || !slot.tickPending.compareAndSet(false,true)) continue
            if (!submit {
                try {
                    if (!slot.cancelled) {
                        slot.runtime?.emit("hand.update")
                        if (slot.app.type == AppType.GAME) slot.runtime?.emit("game.update",seconds)
                    }
                } finally { slot.tickPending.set(false) }
            }) slot.tickPending.set(false)
        }
    }
    fun closeWindow(id: Int) {
        val slot = slots.remove(id) ?: return
        slot.cancelled = true; slot.runtime?.cancel()
        // Tear down all child windows belonging to this runtime, without starting another execution system.
        val children = state.windows.windows.filter { it.owner == slot.app.principal.id }.map { it.id }
        for (child in children) { state.windows.close(child); state.elements.removeWindow(child) }
        state.elements.clear(slot.app.principal.id); state.scenario.clear(slot.app.principal.id)
        submit { slot.runtime?.close() }
    }
    fun stopAll() { for (id in slots.keys.toList()) { state.windows.close(id); closeWindow(id) }; state.dirty = true }
    private fun submit(action: () -> Unit): Boolean {
        if (closed) return false
        return try {
            worker.execute {
                try { action() }
                catch (error: Exception) { main.post { if(!closed) state.log("Evento Lua descartado: ${error.message}") } }
            }; true
        } catch (_: RejectedExecutionException) { false } // backpressure, not an unbounded callback queue
    }
    override fun close() {
        stopAll(); closed = true
        for (slot in slots.values) slot.runtime?.cancel()
        slots.clear(); worker.shutdownNow()
    }
}
