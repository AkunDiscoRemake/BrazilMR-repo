package com.brazilmr.lua

import com.brazilmr.core.permission.*
import com.brazilmr.core.session.*
import com.brazilmr.core.spatial.Scenario
import com.brazilmr.core.tracking.HandFrame
import com.brazilmr.core.window.*

private class TestHost : LuaHost(WindowManagerXR(), UiElementStore(), XrSession(), Scenario(), HandFrame()) {
    val logs = ArrayList<String>()
    override fun <T> sync(action: () -> T) = action()
    override fun log(principal: Principal, message: String) { logs.add(message) }
    override fun changed() = Unit
}
private class Harness(val source: String, type: AppType = AppType.WINDOW, grants: Set<Capability> = emptySet(), name: String = "test") {
    val host = TestHost()
    val permissions = PermissionManager()
    val principal = Principal.script(name, source)
    val window = host.windows.open(name, "Lua Test", type, WindowContent.LUA, principal.id)
    val runtime: LuaRuntime
    init {
        permissions.register(principal, Capability.entries.toSet())
        for (capability in grants) permissions.decideFromUser(principal, capability, true)
        runtime = LuaRuntime(principal, window.id, type, permissions, host)
    }
    fun run(): Boolean = runtime.run(source)
    fun success() { check(run()) { runtime.lastError ?: "Lua failed" } }
}
private var passed = 0
private var failed = 0
private fun test(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS $name") }
    catch (e: Throwable) { failed++; System.err.println("FAIL $name: ${e.message}"); e.printStackTrace() }
}
fun main() {
    test("Lua: sandbox excludes Java, files, network, bytecode and debug") {
        Harness("""
            assert(io == nil and os == nil and luajava == nil and package == nil)
            assert(require == nil and dofile == nil and loadfile == nil and load == nil)
            assert(debug == nil and collectgarbage == nil and coroutine == nil)
            assert(getmetatable == nil and setmetatable == nil and rawset == nil)
            assert(string.dump == nil and string.gsub == nil)
            print('sandbox ok')
        """.trimIndent()).success()
    }
    test("Lua: all namespaces, WINDOW context and immutable app identity") {
        Harness("""
            assert(zxr.ui and zxr.window and zxr.hand and zxr.input and zxr.vr and zxr.mr and zxr.game and zxr.system)
            assert(zxr.scenario == nil and zxr.app.type == 'WINDOW')
            local ok = pcall(function() zxr.app.type = 'GAME' end)
            assert(not ok and zxr.app.type == 'WINDOW')
            assert(zxr.mr.isActive() and not zxr.vr.isActive())
        """.trimIndent()).success()
    }
    test("Lua: create, update, move, resize, click and remove owned UI") {
        val h = Harness("""
            local label = zxr.ui.text {text='Pronto', x=.1, y=.1}
            local button = zxr.ui.button {text='Selecionar', x=.1, y=.4}
            zxr.ui.move(button, .2, .3)
            zxr.ui.resize(button, .4, .2)
            zxr.ui.on(button, 'click', function()
                zxr.ui.set(label, {text='Clicado', color='#A77BFF'})
                zxr.ui.remove(button)
            end)
        """.trimIndent())
        h.success(); val button = h.host.ui.elements.first { it.kind == "button" }
        check(button.x == .2f && button.width == .4f)
        h.runtime.buttonClicked(button.id)
        check(h.host.ui.elements.size == 1 && h.host.ui.elements.first().text == "Clicado")
    }
    test("Lua: owned window API and GAME context use the same manager") {
        val h = Harness("""
            assert(zxr.app.type == 'GAME' and zxr.game.isGame())
            zxr.window.setTitle('Orbit')
            zxr.window.move(.2, .1)
            zxr.window.resize(.6, .5)
            local child = zxr.window.create('Painel', 'WINDOW')
            zxr.ui.text {windowId=child, text='Multi Window'}
            zxr.window.minimize(child)
            assert(zxr.window.get(child).minimized)
            zxr.window.focus(zxr.window.id)
            zxr.game.hud {text='HUD'}
        """.trimIndent(), AppType.GAME)
        h.success(); check(h.host.windows.windows.size == 2 && h.window.title == "Orbit")
    }
    test("Lua: foreign windows and foreign elements denied, even with unsafe_execution") {
        val code = """
            local a = pcall(function() zxr.window.setTitle('hijack', 2) end)
            local b = pcall(function() zxr.ui.set(1, {text='hijack'}) end)
            local c = pcall(function() zxr.ui.text {windowId=2, text='hijack'} end)
            assert(not a and not b and not c)
        """.trimIndent()
        val h = Harness(code, grants = setOf(Capability.UNSAFE_EXECUTION))
        h.host.windows.open("other", "Private", owner = "other")
        h.host.ui.create("other", 2, "text").text = "private"
        h.success(); check(h.host.ui.elements.first().text == "private")
    }
    test("Lua: unsafe_execution is explicit and does not expose Android/JVM privileges") {
        val code = "zxr.ui.setGlobalVisible(false); zxr.vr.enter(); assert(os == nil and luajava == nil)"
        val denied = Harness(code); check(!denied.run()); check(denied.host.session.uiVisible)
        val allowed = Harness(code, grants = setOf(Capability.UNSAFE_EXECUTION))
        allowed.success(); check(!allowed.host.session.uiVisible && allowed.host.session.mode == EnvironmentMode.VR)
    }
    test("Lua: scenario is hidden unless granted and requires GAME") {
        Harness("assert(zxr.scenario == nil)").success()
        val code = "zxr.scenario.spawn {x=0, y=.2, z=-2, color='#9955FF'}"
        check(!Harness(code, grants = setOf(Capability.SCENARIO)).run())
        val game = Harness(code, AppType.GAME, setOf(Capability.SCENARIO)); game.success()
        check(game.host.scenario.objects.size == 1)
    }
    test("Lua: revocation rechecks retained scenario closures") {
        val h = Harness("""
            local spawn = zxr.scenario.spawn
            zxr.system.on('probe', function()
                local ok = pcall(function() spawn {z=-2} end)
                assert(not ok)
            end)
        """.trimIndent(), AppType.GAME, setOf(Capability.SCENARIO))
        h.success(); h.permissions.revokeAll(h.principal); h.runtime.refreshCapabilities()
        h.runtime.emit("system.probe"); check(h.runtime.running && h.host.scenario.objects.isEmpty())
    }
    test("Lua: live unsafe revocation and no grant transfer across source changes") {
        val h = Harness("""
            local hide = zxr.ui.setGlobalVisible
            zxr.system.on('probe', function() hide(false) end)
        """.trimIndent(), grants = setOf(Capability.UNSAFE_EXECUTION))
        h.success(); h.permissions.revokeAll(h.principal); h.runtime.emit("system.probe")
        check(!h.runtime.running && h.host.session.uiVisible)
        val wrong = Harness("print('original')", grants = setOf(Capability.UNSAFE_EXECUTION))
        check(!wrong.runtime.run("zxr.ui.setGlobalVisible(false)")); check(wrong.host.session.uiVisible)
    }
    test("Lua: hand permission, normalized snapshot and 1-based landmarks") {
        val code = "local h = zxr.hand.get('right'); assert(h.present and #h.landmarks == 21); h.landmarks[9].x=99"
        check(!Harness(code).run())
        val h = Harness(code, grants = setOf(Capability.HAND_TRACKING)); h.host.hands.right.present = true
        h.host.hands.right.landmarks[24] = .42f; h.success()
        check(h.host.hands.right.x(8) == .42f)
    }
    test("Lua: input subscriptions are permission-gated and revocable") {
        val code = "zxr.input.on('click', function() print('click') end)"
        check(!Harness(code).run())
        val h = Harness(code, grants = setOf(Capability.INPUT)); h.success(); h.runtime.emit("input.click")
        check(h.host.logs.count { it == "click" } == 1)
        h.permissions.revokeAll(h.principal); h.runtime.emit("input.click")
        check(h.host.logs.count { it == "click" } == 1)
    }
    test("Lua: infinite loop bounded and quota cannot be swallowed with pcall") {
        for (code in listOf("while true do end", "while true do pcall(function() while true do end end) end")) {
            val start = System.nanoTime(); val h = Harness(code); check(!h.run())
            check((System.nanoTime() - start) / 1_000_000 < 3000)
            check(h.runtime.lastError!!.contains("Limite"))
        }
    }
    test("Lua: string/concat explosion and recursive calls are bounded") {
        for (code in listOf("local a=string.rep('a', 999999999)", "local a='hello'; for i=1,32 do a=a..a end", "local function f() return 1+f() end; f()")) {
            val h = Harness(code); check(!h.run()) { code }
        }
    }
    test("Lua: widget, window, text and invalid-number quotas") {
        for (code in listOf("for i=1,129 do zxr.ui.button {text='x'} end", "for i=1,4 do zxr.window.create('w') end", "zxr.ui.text {text=string.rep('a',2049)}", "zxr.ui.text {x=0/0}", "zxr.window.move(math.huge,0)")) {
            check(!Harness(code).run()) { code }
        }
    }
    test("Lua: syntax errors, binary chunks and oversized source fail safely") {
        for (code in listOf("local =", "\u001bLua", "--" + "a".repeat(65536))) check(!Harness(code).run())
    }
    test("Lua: string metatable is not a cross-script mutable API backdoor") {
        val a = Harness("zxr.system.on('check', function() assert(('ab'):rep(2) == 'abab') end)"); a.success()
        val b = Harness("string.rep=function() return 'poisoned' end", name = "other"); b.success()
        a.runtime.emit("system.check"); check(a.runtime.running)
    }
    test("Lua: cancellation stops callbacks and releases owned UI and scenario objects") {
        val h = Harness("zxr.ui.text {text='Hello'}; zxr.scenario.spawn {z=-2}", AppType.GAME, setOf(Capability.SCENARIO))
        h.success(); h.runtime.close()
        check(!h.runtime.running && h.host.ui.elements.isEmpty() && h.host.scenario.objects.isEmpty())
    }
    println("\nBrazil MR Lua: $passed passed, $failed failed")
    check(failed == 0) { "$failed Lua tests failed" }
}
