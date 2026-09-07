package com.brazilmr.core

import com.brazilmr.core.filter.*
import com.brazilmr.core.tracking.*
import com.brazilmr.core.gesture.*
import com.brazilmr.core.input.*
import com.brazilmr.core.window.*
import com.brazilmr.core.permission.*
import com.brazilmr.core.plugin.*
import com.brazilmr.core.performance.*
import com.brazilmr.core.session.*
import com.brazilmr.core.spatial.*
import kotlin.math.*

private var passed = 0
private var failed = 0
private fun test(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS $name") }
    catch (t: Throwable) { failed++; System.err.println("FAIL $name: ${t.message}"); t.printStackTrace() }
}
private fun near(a: Float, b: Float, epsilon: Float = 0.0001f) { check(abs(a - b) <= epsilon) { "$a != $b" } }
private inline fun rejects(block: () -> Unit) { var threw = false; try { block() } catch (_: Exception) { threw = true }; check(threw) }

private fun hand(indexOpen: Boolean = true, thumbOpen: Boolean = true): HandData {
    val hand = HandData(HandSide.RIGHT); hand.present = true; hand.confidence = 0.95f
    fun p(i: Int, x: Float, y: Float, z: Float = 0f) { hand.geometry[i * 3] = x; hand.geometry[i * 3 + 1] = y; hand.geometry[i * 3 + 2] = z }
    p(0, 0f, 0f)
    p(1, -0.3f, 0.3f); p(2, -0.65f, 0.6f)
    if (thumbOpen) { p(3, -1f, 0.9f); p(4, -1.35f, 1.2f) }
    else { p(3, -0.45f, 0.95f); p(4, -0.15f, 0.75f, 0.3f) }
    for (finger in 0..3) {
        val mcp = 5 + finger * 4; val x = -0.3f + finger * 0.2f
        p(mcp, x, 1f); p(mcp + 1, x, 1.6f)
        if (finger == 0 && indexOpen) { p(mcp + 2, x, 2f); p(mcp + 3, x, 2.4f) }
        else { p(mcp + 2, x, 1.65f, 0.4f); p(mcp + 3, x, 0.95f, 0.5f) }
    }
    for (i in 0..20) { hand.landmarks[i * 3] = 0.5f + hand.geometry[i * 3] * 0.2f; hand.landmarks[i * 3 + 1] = 0.8f - hand.geometry[i * 3 + 1] * 0.2f }
    return hand
}
private fun features(open: Boolean = true, base: Boolean = true, present: Boolean = true) = HandFeatures().apply {
    tracked = present; indexExtended = open; indexCurled = !open; gunBase = base
}

fun main() {
    test("One Euro: initialization, constant, duplicate and out-of-order timestamps") {
        val f = OneEuroFilter(); near(f.filter(0.4f, 1_000_000_000), 0.4f)
        near(f.filter(0.4f, 1_016_000_000), 0.4f)
        near(f.filter(1f, 1_016_000_000), 0.4f); near(f.filter(1f, 1_000_000_000), 0.4f)
        near(f.filter(Float.NaN, 1_032_000_000), 0.4f)
    }
    test("One Euro: jitter reduction and fast motion responsiveness") {
        val smooth = OneEuroFilter(OneEuroConfig(1.7f, 0f, 1f))
        val responsive = OneEuroFilter(OneEuroConfig(1.7f, 0.6f, 1f))
        var rawEnergy = 0f; var filteredEnergy = 0f
        for (i in 0..300) {
            val input = 0.5f + sin(i * 2.2f) * 0.03f
            val result = smooth.filter(input, i * 16_666_667L)
            if (i > 20) { rawEnergy += (input - .5f).pow(2); filteredEnergy += (result - .5f).pow(2) }
        }
        check(filteredEnergy < rawEnergy * .15f)
        smooth.reset(); responsive.reset(); smooth.filter(0f, 0); responsive.filter(0f, 0)
        var a = 0f; var b = 0f
        for (i in 1..5) { a = smooth.filter(1f, i * 16_666_667L); b = responsive.filter(1f, i * 16_666_667L) }
        check(b > a && b > .85f)
    }
    test("One Euro: invalid settings and reacquisition") {
        rejects { OneEuroConfig(0f) }; rejects { OneEuroConfig(beta = -1f) }; rejects { OneEuroConfig(Float.NaN) }
        val f = OneEuroFilter(); f.filter(0f, 0); near(f.filter(1f, 900_000_000), 1f)
        f.reset(); near(f.filter(.2f, 910_000_000), .2f)
    }
    test("Pipeline: handedness, filter reset, missing and malformed hands") {
        val pipeline = HandTrackingPipeline(); val raw = HandFrame()
        raw.right.copyFrom(hand()); raw.timestampNanos = 1_000_000_000
        check(pipeline.process(raw).right.present)
        raw.timestampNanos += 1_000_000_000; raw.right.landmarks[24] = .9f
        near(pipeline.process(raw).right.x(8), .9f)
        pipeline.rightEnabled = false; check(!pipeline.process(raw).right.present)
        pipeline.rightEnabled = true; raw.right.landmarks[5] = Float.NaN
        check(!pipeline.process(raw).right.present)
    }
    test("Mailbox: copies buffers rather than sharing mutable landmarks") {
        val mailbox = HandFrameMailbox(); val raw = HandFrame(); val read = HandFrame()
        raw.right.copyFrom(hand()); mailbox.publish(raw); raw.right.landmarks[0] = 9f
        mailbox.readInto(read); check(read.right.x(0) != 9f)
        mailbox.clear(); mailbox.readInto(read); check(!read.right.present)
    }
    test("Geometry: gun, bent trigger and fist classified in 3D") {
        val f = HandFeatures(); HandGeometry.describe(hand(), f)
        check(f.gunBase && f.indexExtended && !f.fist && !f.indexCurled)
        HandGeometry.describe(hand(false), f); check(f.gunBase && f.indexCurled && !f.indexExtended)
        HandGeometry.describe(hand(false, false), f); check(f.fist && !f.gunBase)
    }
    test("Geometry: invariant under full 3D rotation, translation, scale") {
        val f = HandFeatures(); val transformed = FloatArray(3)
        for (step in -12..12) for (open in listOf(true, false)) {
            val hand = hand(open); val q = Quaternion(); val angle = step * .25f
            q.set(sin(angle) * .3f, sin(angle) * .8f, sin(angle) * .52f, cos(angle))
            for (i in 0..20) {
                q.rotate(hand.geometry[i * 3], hand.geometry[i * 3 + 1], hand.geometry[i * 3 + 2], transformed)
                for (axis in 0..2) hand.geometry[i * 3 + axis] = transformed[axis] * 3f + axis * 4f
            }
            HandGeometry.describe(hand, f); check(f.gunBase && f.indexExtended == open && f.indexCurled != open)
        }
    }
    test("Gun: must arm before triggering; cooldown and one shot per pull") {
        val detector = GunGestureDetector()
        for (t in 0L..300L step 50) check(!detector.update(features(false), t))
        detector.update(features(), 400); detector.update(features(), 550)
        check(detector.state == GunState.ARMED)
        check(!detector.update(features(false), 600)); check(detector.update(features(false), 680))
        for (t in 700L..3000L step 100) check(!detector.update(features(false), t))
        detector.update(features(), 3100); detector.update(features(), 3250)
        detector.update(features(false), 3300); check(detector.update(features(false), 3380))
    }
    test("Gun: hand loss disarms, jitter and duplicate timestamps do not fire") {
        val d = GunGestureDetector(); d.update(features(), 0); d.update(features(), 150)
        d.update(features(present = false), 160); check(d.state == GunState.IDLE)
        check(!d.update(features(false), 300)); check(!d.update(features(false), 300))
        d.update(features(), 400); d.update(features(base = false), 450); check(d.state == GunState.IDLE)
    }
    test("Fist: five seconds, tolerance pauses, one shot until release") {
        val d = FistHoldDetector(); var fired = 0
        for (t in 0L..5200L step 100) if (d.update(true, t != 2000L, t)) fired++
        check(fired == 1)
        for (t in 5300L..7000L step 100) check(!d.update(true, true, t))
        d.update(true, false, 7500); near(d.progress, 0f)
    }
    test("Fist: missing hand never completes; long gap resets") {
        val d = FistHoldDetector()
        for (t in 0L..4900L step 100) check(!d.update(true, true, t))
        check(!d.update(false, true, 5000)); check(!d.update(false, true, 5100))
        check(!d.update(true, true, 6000)); near(d.progress, 0f)
    }
    test("Session: MR/VR toggle, left hide and repositioned recovery") {
        val s = XrSession(); val a = GestureActions(); a.toggleMode = true; s.apply(a); check(s.mode == EnvironmentMode.VR)
        s.apply(a); check(s.mode == EnvironmentMode.MR)
        a.clear(); a.hideUi = true; s.apply(a); check(!s.uiVisible)
        a.clear(); a.showUi = true; a.handX = .1f; s.apply(a)
        check(s.uiVisible && s.uiAnchorX < 0)
    }
    test("Input: pinch down/up, debounce, cursor and cancellation") {
        val events = ArrayList<PointerAction>()
        val input = InputSystem { events.add(it.action) }; val h = hand(); val f = features(base = false).apply { pinchRatio = .2f }
        input.hand(h, f, false, 0); input.hand(h, f, false, 70)
        check(events.count { it == PointerAction.DOWN } == 1 && input.cursorState == CursorState.PRESSED)
        f.pinchRatio = .5f; input.hand(h, f, false, 100); input.hand(h, f, false, 160)
        check(events.count { it == PointerAction.UP } == 1)
        f.pinchRatio = .2f; input.hand(h, f, false, 200); input.hand(h, f, false, 280)
        check(events.count { it == PointerAction.DOWN } == 1)
        input.hand(h, f, false, 420); input.hand(h, f, false, 500)
        h.present = false; input.hand(h, f, false, 520)
        check(events.last() == PointerAction.CANCEL && input.cursorState == CursorState.DISABLED)
    }
    test("Input: touch ownership, gun suppresses accidental click") {
        var downs = 0; val input = InputSystem { if (it.action == PointerAction.DOWN) downs++ }
        val h = hand(); val f = features().apply { pinchRatio = .1f }
        input.touch(PointerAction.DOWN, .5f, .5f, 0)
        input.hand(h, f, false, 1000); input.hand(h, f, false, 1100); check(downs == 1)
        input.touch(PointerAction.UP, .5f, .5f, 1200)
        input.hand(h, f, false, 1400); input.hand(h, f, false, 1500); check(downs == 1)
        input.hand(h, f, true, 2000); input.hand(h, f, true, 2100); check(downs == 1)
    }
    test("WindowManager: GAME and WINDOW share lifecycle and ownership") {
        val m = WindowManagerXR(); val a = m.open("a", "A"); val b = m.open("b", "B", AppType.GAME)
        check(!a.focused && b.focused && m.windows.size == 2)
        m.focus(a.id); check(m.windows.last() === a)
        m.minimize(a.id); check(a.minimized && b.focused)
        m.focus(a.id); check(!a.minimized)
        rejects { m.assertOwner(a.id, "b") }; m.assertOwner(a.id, "a")
        m.close(a.id); check(b.focused && m.windows.size == 1)
        m.blur(); check(!b.focused)
    }
    test("WindowManager: bounds, resize, hit testing, tiling and quota") {
        val m = WindowManagerXR(4); val a = m.open("a", "A")
        m.move(a.id, -1f, 2f); near(a.x, 0f); near(a.y + a.height, 1f)
        m.resize(a.id, 10f, .01f); near(a.width, 1f); near(a.height, .25f)
        rejects { m.resize(a.id, Float.NaN, 1f) }
        repeat(3) { m.open("app$it", "Window") }; rejects { m.open("overflow", "No") }
        m.tile(); for (w in m.windows) { check(w.x >= 0 && w.x + w.width <= 1f); check(m.hitTest(w.x + .1f, w.y + .1f) === w) }
    }
    test("Permissions: default deny, declaration is not consent, live revocation") {
        val p = PermissionManager(); val a = Principal("a"); val b = Principal("b")
        p.register(a, setOf(Capability.UNSAFE_EXECUTION, Capability.SCENARIO)); p.register(b, setOf(Capability.SCENARIO))
        check(!p.has(a, Capability.SCENARIO)); rejects { p.require(a, Capability.SCENARIO) }
        p.decideFromUser(a, Capability.SCENARIO, true); p.require(a, Capability.SCENARIO)
        check(!p.has(b, Capability.SCENARIO) && !p.has(a, Capability.UNSAFE_EXECUTION))
        rejects { p.decideFromUser(b, Capability.UNSAFE_EXECUTION, true) }
        p.revokeAll(a); rejects { p.require(a, Capability.SCENARIO) }
    }
    test("Permissions: source changes invalidate script identity") {
        check(Principal.script("app", "a") != Principal.script("app", "b"))
        check(Principal.script("app", "a") == Principal.script("app", "a"))
        rejects { Principal.script("../../escape", "code") }
    }
    test("Plugin: manifest permission, start, revoke, stop; no DEX loader") {
        val p = PermissionManager(); val manager = PluginSystem(p); var active = false
        val principal = manager.register(object : XrPlugin {
            override val manifest = PluginManifest("test", "1", PluginKind.FILTER, setOf(Capability.HAND_TRACKING))
            override fun start(context: PluginContext) { context.require(Capability.HAND_TRACKING); active = true }
            override fun stop() { active = false }
        })
        rejects { manager.enable("test") }; check(!active)
        p.decideFromUser(principal, Capability.HAND_TRACKING, true); manager.enable("test"); check(active)
        p.revokeAll(principal); manager.enforceRevocations(); check(!active); manager.close()
    }
    test("Performance: absent-hand backoff, thermal limits, validated settings") {
        val p = PerformanceController(); val s = XrSettings()
        check(p.update(s, 10f, 0, true, 100).trackingFps == 24)
        check(p.update(s, 10f, 0, false, 2100).trackingFps == 5)
        val hot = p.update(s, 30f, 3, true, 2200); check(hot.renderFps == 30 && hot.renderScale <= .65f)
        check(p.update(s, 30f, 4, true, 2300).pauseTracking)
        rejects { XrSettings(ipdMm = 90f) }; rejects { XrSettings(renderScale = Float.NaN) }
    }
    test("SBS: geometric IPD disparity and inverse hit testing for both eyes") {
        val p = SpatialProjection().apply { spatial = true; sbs = true; eyeAspect = 1.1f; centerX = .1f; positionX = .03f }
        p.orientation.set(.025f, .08f, .02f, .99f)
        val clip = FloatArray(4); val uv = FloatArray(2); val left = FloatArray(4); val right = FloatArray(4)
        for (eye in 0..1) for (x in listOf(.2f, .5f, .8f)) for (y in listOf(.2f, .5f, .8f)) {
            p.project(x, y, eye, clip)
            check(p.rayToUi((clip[0] / clip[3] + 1) * .5f, (1 - clip[1] / clip[3]) * .5f, eye, uv))
            near(uv[0], x); near(uv[1], y)
        }
        p.project(.5f, .5f, 0, left); p.project(.5f, .5f, 1, right)
        check(left[0] / left[3] > right[0] / right[3])
        p.spatial = false; p.centerX = 0f; check(p.rayToUi(.2f, .8f, 0, uv)); near(uv[0], .2f)
    }
    test("Camera: crop and front-camera mirror use the same coordinates") {
        val uv = FloatArray(2)
        CameraCoordinates.map(.25f, .5f, 4f / 3f, 16f / 9f, true, uv); near(uv[0], .75f); near(uv[1], .5f)
        CameraCoordinates.map(.5f, .2f, 16f / 9f, 4f / 3f, false, uv); near(uv[0], .5f); near(uv[1], .2f)
    }
    test("Scenario: ownership, position validation and quotas") {
        val s = Scenario(); val id = s.spawn("a", 0f, 0f, -2f, .1f, 0xff9900ff.toInt())
        rejects { s.move("b", id, 1f, 0f, -2f) }; s.move("a", id, .2f, .1f, -1f)
        rejects { s.spawn("a", Float.NaN, 0f, -2f, .1f, 0) }
        repeat(31) { s.spawn("a", 0f, 0f, -2f, .1f, 0) }; rejects { s.spawn("a", 0f, 0f, -2f, .1f, 0) }
        s.clear("a"); check(s.objects.isEmpty())
    }
    test("Cadence: 24 FPS from 30 FPS camera, no quantization down to 15") {
        val cadence = CadenceLimiter(); var count = 0
        for (frame in 0 until 300) if (cadence.acquire(frame * 1_000_000_000L / 30, 24)) count++
        check(count in 239..241) { "Accepted $count instead of ~240 frames" }
    }
    test("Cadence: faster reacquisition, nonmonotonic timestamps, long stalls") {
        val cadence = CadenceLimiter(); check(cadence.acquire(0,5))
        check(!cadence.acquire(0,5)); check(!cadence.acquire(-1,5))
        check(cadence.acquire(50_000_000,24))
        check(cadence.acquire(10_000_000_000,24)); check(!cadence.acquire(10_000_000_001,24))
        cadence.reset(); check(cadence.acquire(0,24))
    }
    test("One Euro defaults: responsive to normalized hand motion, not beta tuned for pixel units") {
        val f=OneEuroFilter();var filtered=0f;var raw=0f
        for(i in 0..24) { raw=.1f+i/24f*.5f;filtered=f.filter(raw,i*1_000_000_000L/24) }
        check(raw-filtered < .025f) { "Excessive normalized lag: ${raw-filtered}" }
    }
    test("Projection: behind-camera/invalid ray clears previous output") {
        val p=SpatialProjection().apply { spatial=true }; val uv=floatArrayOf(.2f,.3f)
        p.orientation.set(0f,1f,0f,0f)
        check(!p.rayToUi(.5f,.5f,0,uv));check(uv[0].isNaN())
        check(!p.rayToUi(Float.NaN,0f,0,uv))
    }
    println("\nBrazil MR Core: $passed passed, $failed failed")
    check(failed == 0) { "$failed core tests failed" }
}
