package com.brazilmr.lua

import com.brazilmr.core.permission.*
import com.brazilmr.core.session.EnvironmentMode
import com.brazilmr.core.window.*
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*

/** Single worker thread per host controller. No Android, files, network, reflection, package or bytecode API. */
class LuaRuntime(
    val principal: Principal,
    private val primaryWindowId: Int,
    private val appType: AppType,
    private val permissions: PermissionManager,
    private val host: LuaHost,
) : AutoCloseable {
    private val globals = Globals()
    private val budget = ExecutionBudget()
    private val callbacks = LinkedHashMap<String, MutableList<LuaValue>>()
    private val api = LuaTable()
    private var subscriptions = 0
    @Volatile var running = false; private set
    var lastError: String? = null; private set

    init {
        globals.load(BaseLib())
        // LuaJ 3.0.1 libraries register themselves in package.loaded. This inert table is
        // discarded before any user code runs; PackageLib (and require/searchers) is never loaded.
        val registration = LuaTable(); registration.set("loaded", LuaTable()); globals.set("package", registration)
        globals.load(TableLib()); globals.load(StringLib()); globals.load(MathLib())
        globals.load(budget)
        LuaC.install(globals)
        globals.finder = ResourceFinder { null }
        globals.STDIN = java.io.ByteArrayInputStream(ByteArray(0))
        globals.STDOUT = java.io.PrintStream(object : java.io.OutputStream() { override fun write(value: Int) = Unit })
        globals.STDERR = globals.STDOUT
        for (name in listOf("dofile", "loadfile", "load", "loadstring", "require", "package", "io", "os", "luajava", "debug", "collectgarbage", "rawset", "getmetatable", "setmetatable", "coroutine")) globals.set(name, LuaValue.NIL)
        installSafeStrings()
        globals.set("print", function { args ->
            val text = (1..minOf(args.narg(), 8)).joinToString(" ") { args.arg(it).tojstring().take(256) }
            host.log(principal, text); LuaValue.NONE
        })
        installApi()
    }

    fun run(source: String): Boolean {
        if (running) return fail("Crie um runtime novo para reiniciar")
        if (source.toByteArray(Charsets.UTF_8).size > 65_536 || source.startsWith('\u001b')) return fail("Somente texto Lua, até 64 KiB")
        if (principal != Principal.script(principal.id.substringBefore('@'), source)) return fail("Código diferente da identidade autorizada")
        return guarded {
            // Explicit text mode. A script cannot install a binary chunk loader.
            val chunk = globals.load(java.io.StringReader(source), "@${principal.id.substringBefore('@')}")
            budget.begin(500); running = true; chunk.call()
        }
    }
    private fun emitValue(event: String, payload: LuaValue) {
        if (!running) return
        val listeners = callbacks[event] ?: return
        guarded {
            budget.begin()
            // Subscriptions added from inside callbacks take effect on the next event.
            val count = listeners.size
            for (i in 0 until count) listeners[i].call(payload)
        }
    }
    fun emit(event: String) = emitValue(event, LuaValue.NIL)
    fun emit(event: String, value: String) = emitValue(event, LuaValue.valueOf(value))
    fun emit(event: String, value: Double) = emitValue(event, LuaValue.valueOf(value))
    fun emit(event: String, value: Boolean) = emitValue(event, LuaValue.valueOf(value))
    fun emitPointer(x: Float, y: Float, action: String, source: String) {
        if (!permissions.has(principal, Capability.INPUT)) return
        val payload = LuaTable()
        payload.set("x", LuaValue.valueOf(x.toDouble())); payload.set("y", LuaValue.valueOf(y.toDouble()))
        payload.set("action", action); payload.set("source", source)
        emitValue("input.pointer", payload)
    }
    fun buttonClicked(id: Int) {
        if (!running) return
        host.sync { host.ui.owned(principal.id, id) }
        emit("ui.$id.click")
    }
    fun refreshCapabilities() {
        api.set("scenario", if (permissions.has(principal, Capability.SCENARIO)) readonly(scenarioApi()) else LuaValue.NIL)
    }
    fun cancel() { budget.cancelled = true }
    override fun close() {
        cancel(); running = false; callbacks.clear(); subscriptions = 0
        host.sync { host.ui.clear(principal.id); host.scenario.clear(principal.id); host.changed() }
        globals.STDOUT.close()
    }
    private fun guarded(action: () -> Unit): Boolean = try { action(); true }
        catch (e: ScriptBudgetExceeded) { fail(e.message ?: "Quota excedida") }
        catch (e: Exception) { fail(e.message ?: "Erro Lua") }
        catch (_: StackOverflowError) { fail("Limite de profundidade Lua") }
    private fun fail(message: String): Boolean {
        lastError = message.take(512); running = false; budget.cancelled = true
        host.log(principal, "ERRO · $lastError"); return false
    }
    private fun function(body: (Varargs) -> Varargs) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            budget.checkBudget()
            return try { host.sync { body(args) } }
            catch (error: Exception) { throw LuaError(error.message ?: "Operação recusada") }
        }
    }
    private fun bind(table: LuaTable, name: String, body: (Varargs) -> Varargs) { table.set(name, function(body)) }
    private fun require(capability: Capability) = permissions.require(principal, capability)
    private fun ownWindow(id: Int = primaryWindowId): XRWindow { host.windows.assertOwner(id, principal.id); return host.windows.get(id)!! }
    private fun changed() { host.ui.changed(); host.changed() }

    private fun installApi() {
        val app = LuaTable(); app.set("type", appType.name); app.set("id", principal.id.substringBefore('@')); app.set("windowId", primaryWindowId)
        api.set("app", readonly(app))
        api.set("ui", readonly(uiApi()))
        api.set("window", readonly(windowApi()))
        val hand = LuaTable()
        bind(hand, "get") { args ->
            require(Capability.HAND_TRACKING)
            val side = args.checkjstring(1); require(side == "left" || side == "right") { "Use left ou right" }
            val source = if (side == "left") host.hands.left else host.hands.right
            val snapshot = LuaTable(); snapshot.set("present", LuaValue.valueOf(source.present))
            if (source.present) {
                snapshot.set("confidence", LuaValue.valueOf(source.confidence.toDouble()))
                val landmarks = LuaTable()
                for (i in 0..20) {
                    val point = LuaTable(); point.set("x", LuaValue.valueOf(source.x(i).toDouble())); point.set("y", LuaValue.valueOf(source.y(i).toDouble())); point.set("z", LuaValue.valueOf(source.z(i).toDouble()))
                    landmarks.set(i + 1, point)
                }
                val normal = LuaTable(); normal.set("x", LuaValue.valueOf(source.normalX.toDouble())); normal.set("y", LuaValue.valueOf(source.normalY.toDouble())); normal.set("z", LuaValue.valueOf(source.normalZ.toDouble()))
                snapshot.set("landmarks", landmarks); snapshot.set("normal", normal)
            }
            snapshot
        }
        on(hand, "hand", Capability.HAND_TRACKING); api.set("hand", readonly(hand))
        val input = LuaTable(); on(input, "input", Capability.INPUT); api.set("input", readonly(input))
        for (mode in EnvironmentMode.entries) {
            val table = LuaTable()
            bind(table, "isActive") { LuaValue.valueOf(host.session.mode == mode) }
            bind(table, "enter") { require(Capability.UNSAFE_EXECUTION); host.session.setMode(mode); host.modeChanged(); changed(); LuaValue.TRUE }
            api.set(mode.name.lowercase(), readonly(table))
        }
        val game = LuaTable()
        bind(game, "isGame") { LuaValue.valueOf(appType == AppType.GAME) }
        bind(game, "hud") { args -> check(appType == AppType.GAME) { "API GAME em contexto WINDOW" }; createUi("panel", args.checktable(1)) }
        on(game, "game"); api.set("game", readonly(game))
        val system = LuaTable()
        system.set("version", "0.1.0")
        bind(system, "mode") { LuaValue.valueOf(host.session.mode.name) }
        bind(system, "time") { LuaValue.valueOf(host.nowMillis().toDouble()) }
        bind(system, "spatialStatus") { LuaValue.valueOf(host.spatialStatus()) }
        bind(system, "hasPermission") { args ->
            val name = args.checkjstring(1); LuaValue.valueOf(Capability.entries.firstOrNull { it.wireName == name }?.let { permissions.has(principal, it) } == true)
        }
        on(system, "system"); api.set("system", readonly(system))
        refreshCapabilities()
        globals.set("zxr", readonly(api))
    }
    private fun on(table: LuaTable, prefix: String, capability: Capability? = null) {
        bind(table, "on") { args ->
            if (capability != null) require(capability)
            val event = args.checkjstring(1)
            require(event.length <= 48 && event.matches(Regex("[a-zA-Z0-9_.-]+")))
            check(subscriptions < 64) { "Máximo de 64 subscriptions" }
            val callback = args.checkfunction(2)
            // Check a revocable capability even when the script retained this closure.
            val guarded = object : OneArgFunction() {
                override fun call(value: LuaValue): LuaValue {
                    if (capability != null && !permissions.has(principal, capability)) return LuaValue.NIL
                    return callback.call(value)
                }
            }
            callbacks.getOrPut("$prefix.$event") { ArrayList() }.add(guarded); subscriptions++
            LuaValue.TRUE
        }
    }
    private fun uiApi(): LuaTable {
        val table = LuaTable()
        bind(table, "create") { args -> createUi(args.checkjstring(1), args.checktable(2)) }
        for (kind in listOf("button", "text", "panel")) bind(table, kind) { args -> createUi(kind, args.checktable(1)) }
        bind(table, "set") { args -> val item = host.ui.owned(principal.id, args.checkint(1)); applyProperties(item, args.checktable(2)); changed(); LuaValue.TRUE }
        bind(table, "move") { args ->
            val item = host.ui.owned(principal.id, args.checkint(1)); item.x = unit(args, 2); item.y = unit(args, 3); changed(); LuaValue.TRUE
        }
        bind(table, "resize") { args ->
            val item = host.ui.owned(principal.id, args.checkint(1)); item.width = unit(args, 2).coerceAtLeast(.02f); item.height = unit(args, 3).coerceAtLeast(.02f); changed(); LuaValue.TRUE
        }
        bind(table, "remove") { args ->
            val id = args.checkint(1); host.ui.remove(principal.id, id)
            callbacks.remove("ui.$id.click")?.let { subscriptions -= it.size }; changed(); LuaValue.TRUE
        }
        bind(table, "on") { args ->
            val id = args.checkint(1); host.ui.owned(principal.id, id)
            require(args.checkjstring(2) == "click"); check(subscriptions < 64)
            callbacks.getOrPut("ui.$id.click") { ArrayList() }.add(args.checkfunction(3)); subscriptions++; LuaValue.TRUE
        }
        bind(table, "setGlobalVisible") { args -> require(Capability.UNSAFE_EXECUTION); host.session.setUiVisible(args.checkboolean(1)); changed(); LuaValue.TRUE }
        bind(table, "recenterGlobal") { args -> require(Capability.UNSAFE_EXECUTION); host.session.recenter(unit(args, 1), unit(args, 2)); changed(); LuaValue.TRUE }
        return table
    }
    private fun createUi(kind: String, properties: LuaTable): LuaValue {
        val windowId = properties.get("windowId").optint(primaryWindowId); ownWindow(windowId)
        val item = host.ui.create(principal.id, windowId, kind)
        try { applyProperties(item, properties) } catch (error: Exception) { host.ui.remove(principal.id, item.id); throw error }
        changed(); return LuaValue.valueOf(item.id)
    }
    private fun applyProperties(item: UiElement, props: LuaTable) {
        if (!props.get("text").isnil()) { val text = props.get("text").checkjstring(); require(text.length <= 2048); item.text = text }
        for (name in listOf("x", "y", "width", "height")) if (!props.get(name).isnil()) {
            val value = props.get(name).checkdouble().toFloat(); require(value in 0f..1f)
            when (name) { "x" -> item.x = value; "y" -> item.y = value; "width" -> item.width = value.coerceAtLeast(.02f); "height" -> item.height = value.coerceAtLeast(.02f) }
        }
        if (!props.get("visible").isnil()) item.visible = props.get("visible").checkboolean()
        if (!props.get("fontSize").isnil()) { val value = props.get("fontSize").checkdouble().toFloat(); require(value in 12f..64f); item.fontSize = value }
        if (!props.get("color").isnil()) item.color = color(props.get("color").checkjstring())
        if (!props.get("background").isnil()) item.background = color(props.get("background").checkjstring())
    }
    private fun windowApi(): LuaTable {
        val table = LuaTable(); table.set("id", primaryWindowId)
        bind(table, "create") { args ->
            val title = args.checkjstring(1); val type = AppType.valueOf(args.optjstring(2, appType.name))
            check(host.windows.windows.count { it.owner == principal.id } < 4) { "Máximo de 4 janelas por script" }
            val window = host.windows.open(principal.id, title, type, WindowContent.LUA, principal.id); changed(); LuaValue.valueOf(window.id)
        }
        bind(table, "get") { args ->
            val window = ownWindow(args.optint(1, primaryWindowId)); val out = LuaTable()
            out.set("id", window.id); out.set("title", window.title); out.set("type", window.appType.name)
            out.set("x", LuaValue.valueOf(window.x.toDouble())); out.set("y", LuaValue.valueOf(window.y.toDouble()))
            out.set("width", LuaValue.valueOf(window.width.toDouble())); out.set("height", LuaValue.valueOf(window.height.toDouble()))
            out.set("focused", LuaValue.valueOf(window.focused)); out.set("minimized", LuaValue.valueOf(window.minimized)); out
        }
        bind(table, "setTitle") { args ->
            val title = args.checkjstring(1); require(title.length in 1..120)
            ownWindow(args.optint(2, primaryWindowId)).title = title; changed(); LuaValue.TRUE
        }
        bind(table, "move") { args -> val window = ownWindow(args.optint(3, primaryWindowId)); host.windows.move(window.id, unit(args, 1), unit(args, 2)); changed(); LuaValue.TRUE }
        bind(table, "resize") { args -> val window = ownWindow(args.optint(3, primaryWindowId)); host.windows.resize(window.id, unit(args, 1), unit(args, 2)); changed(); LuaValue.TRUE }
        for (operation in listOf("focus", "minimize", "close")) bind(table, operation) { args ->
            val id = ownWindow(args.optint(1, primaryWindowId)).id
            when (operation) { "focus" -> host.windows.focus(id); "minimize" -> host.windows.minimize(id); "close" -> { host.windows.close(id); host.windowClosed(id) } }
            changed(); LuaValue.TRUE
        }
        on(table, "window"); return table
    }
    private fun scenarioApi(): LuaTable {
        val table = LuaTable()
        bind(table, "spawn") { args ->
            require(Capability.SCENARIO); check(appType == AppType.GAME) { "scenario requer GAME" }
            val p = args.checktable(1)
            val id = host.scenario.spawn(principal.id, p.get("x").optdouble(0.0).toFloat(), p.get("y").optdouble(0.0).toFloat(), p.get("z").optdouble(-2.0).toFloat(), p.get("size").optdouble(.1).toFloat(), color(p.get("color").optjstring("#A77BFF")))
            LuaValue.valueOf(id)
        }
        bind(table, "move") { args -> require(Capability.SCENARIO); host.scenario.move(principal.id, args.checkint(1), finite(args, 2), finite(args, 3), finite(args, 4)); LuaValue.TRUE }
        bind(table, "remove") { args -> require(Capability.SCENARIO); host.scenario.remove(principal.id, args.checkint(1)); LuaValue.TRUE }
        on(table, "scenario", Capability.SCENARIO); return table
    }
    private fun unit(args: Varargs, index: Int): Float = finite(args, index).also { require(it in 0f..1f) }
    private fun finite(args: Varargs, index: Int): Float = args.checkdouble(index).toFloat().also { require(it.isFinite()) }
    private fun color(hex: String): Int {
        require(hex.matches(Regex("#[0-9a-fA-F]{6}"))) { "Cor deve ser #RRGGBB" }
        return 0xff000000.toInt() or hex.substring(1).toInt(16)
    }
    private fun readonly(source: LuaTable): LuaTable {
        val proxy = LuaTable(); val meta = LuaTable(); meta.set("__index", source)
        meta.set("__newindex", object : ThreeArgFunction() { override fun call(a: LuaValue, b: LuaValue, c: LuaValue): LuaValue = throw LuaError("API somente leitura") })
        meta.set("__metatable", LuaValue.FALSE); proxy.setmetatable(meta); return proxy
    }
    private fun installSafeStrings() {
        val strings = globals.get("string").checktable()
        for (name in listOf("dump", "gsub", "gmatch", "match", "format")) strings.set(name, LuaValue.NIL)
        strings.set("rep", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.checkjstring(1); val count = args.checkint(2); val separator = args.optjstring(3, "")
                if (count !in 0..16_384 || (text.length.toLong() + separator.length) * count > 16_384) throw ScriptBudgetExceeded("string.rep excedeu 16 KiB")
                return LuaValue.valueOf(if (count == 0) "" else List(count) { text }.joinToString(separator))
            }
        })
        // Literal search only: pattern engines can spend unbounded time inside a single opcode.
        strings.set("find", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.checkjstring(1); val needle = args.checkjstring(2)
                val offset = args.optint(3, 1).coerceAtLeast(1) - 1; val index = text.indexOf(needle, offset)
                return if (index < 0) LuaValue.NIL else LuaValue.varargsOf(LuaValue.valueOf(index + 1), LuaValue.valueOf(index + needle.length))
            }
        })
        val concat = globals.get("table").get("concat")
        globals.get("table").set("concat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val table = args.checktable(1); val start = args.optint(3, 1); val end = args.optint(4, table.length())
                if (end.toLong() - start > 4096) throw ScriptBudgetExceeded("table.concat excedeu a quota")
                var size = 0L; val separator = args.optjstring(2, "").length
                for (i in start..end) { size += table.get(i).tojstring().length + separator; if (size > 16_384) throw ScriptBudgetExceeded("table.concat excedeu 16 KiB") }
                return concat.invoke(args)
            }
        })
        // LuaJ uses a process-global string metatable. Its methods are host-free, bounded and immutable to scripts.
        synchronized(LuaString::class.java) {
            val methods = LuaTable()
            for (key in strings.keys()) methods.rawset(key, strings.rawget(key))
            val meta = LuaTable(); meta.set("__index", readonly(methods)); meta.set("__metatable", LuaValue.FALSE)
            LuaString.s_metatable = meta
        }
    }
}
