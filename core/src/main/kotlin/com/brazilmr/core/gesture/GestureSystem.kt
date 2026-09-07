package com.brazilmr.core.gesture

import com.brazilmr.core.tracking.HandFrame

enum class GunState { IDLE, PRIMING, ARMED, TRIGGERING, COOLDOWN }

class GunGestureDetector(
    private val armMillis: Long = 140,
    private val triggerMillis: Long = 70,
    private val cooldownMillis: Long = 1000,
) {
    var state = GunState.IDLE; private set
    private var stateSince = 0L
    private var lastGoodBase = 0L
    private var cooldownUntil = 0L
    private var lastTime = Long.MIN_VALUE
    private var released = false
    fun reset() { state = GunState.IDLE; stateSince = 0; lastGoodBase = 0; released = false }
    fun update(f: HandFeatures, timeMillis: Long): Boolean {
        if (timeMillis <= lastTime) return false
        lastTime = timeMillis
        if (state == GunState.COOLDOWN) {
            // A sustained bent index cannot auto-rearm when the timer expires.
            if (!f.tracked || !f.gunBase || !f.indexCurled) released = true
            if (timeMillis >= cooldownUntil && released) reset()
            else return false
        }
        if (!f.tracked) { reset(); return false }
        if (f.gunBase) lastGoodBase = timeMillis
        when (state) {
            GunState.IDLE -> if (f.gunBase && f.indexExtended) transition(GunState.PRIMING, timeMillis)
            GunState.PRIMING -> when {
                !f.gunBase || !f.indexExtended -> reset()
                timeMillis - stateSince >= armMillis -> transition(GunState.ARMED, timeMillis)
            }
            GunState.ARMED -> when {
                timeMillis - lastGoodBase > 220 -> reset()
                f.indexCurled -> transition(GunState.TRIGGERING, timeMillis)
            }
            GunState.TRIGGERING -> when {
                timeMillis - lastGoodBase > 220 -> reset()
                !f.indexCurled -> transition(GunState.ARMED, timeMillis)
                f.indexCurled && timeMillis - stateSince >= triggerMillis -> {
                    cooldownUntil = timeMillis + cooldownMillis
                    released = false
                    transition(GunState.COOLDOWN, timeMillis)
                    return true
                }
            }
            GunState.COOLDOWN -> Unit
        }
        return false
    }
    private fun transition(next: GunState, now: Long) { state = next; stateSince = now }
}

/** Brief errors pause (not advance) the hold. A lost hand can never finish a hold. */
class FistHoldDetector(private val holdMillis: Long = 5000, private val toleranceMillis: Long = 300) {
    var progress = 0f; private set
    private var accumulated = 0L
    private var lastTime = Long.MIN_VALUE
    private var lastGood = Long.MIN_VALUE
    private var previousGood = false
    private var latched = false
    fun update(tracked: Boolean, closed: Boolean, now: Long): Boolean {
        if (now <= lastTime) return false
        val good = tracked && closed
        val delta = if (lastTime == Long.MIN_VALUE) 0 else now - lastTime
        if (lastGood != Long.MIN_VALUE && now - lastGood > toleranceMillis) {
            accumulated = 0; latched = false; previousGood = false
        }
        if (good) {
            if (previousGood && delta <= toleranceMillis) accumulated += delta
            lastGood = now
        }
        previousGood = good
        lastTime = now
        progress = (accumulated.toFloat() / holdMillis).coerceIn(0f, 1f)
        if (good && !latched && accumulated >= holdMillis) { latched = true; return true }
        return false
    }
    fun reset() {
        accumulated = 0; lastTime = Long.MIN_VALUE; lastGood = Long.MIN_VALUE
        previousGood = false; latched = false; progress = 0f
    }
}

class GestureActions {
    var toggleMode = false
    var hideUi = false
    var showUi = false
    var handX = 0.5f
    var handY = 0.5f
    fun clear() { toggleMode = false; hideUi = false; showUi = false }
}

class GestureSystem {
    val rightGun = GunGestureDetector()
    val leftGun = GunGestureDetector()
    val leftFist = FistHoldDetector()
    val rightFeatures = HandFeatures()
    val leftFeatures = HandFeatures()
    val actions = GestureActions()
    fun update(frame: HandFrame, nowMillis: Long): GestureActions {
        HandGeometry.describe(frame.right, rightFeatures)
        HandGeometry.describe(frame.left, leftFeatures)
        actions.clear()
        actions.toggleMode = rightGun.update(rightFeatures, nowMillis)
        actions.hideUi = leftGun.update(leftFeatures, nowMillis)
        actions.showUi = leftFist.update(leftFeatures.tracked, leftFeatures.fist, nowMillis)
        if (actions.showUi) { actions.handX = frame.left.x(0); actions.handY = frame.left.y(0) }
        return actions
    }
    val suppressPinch get() = rightGun.state != GunState.IDLE
    fun reset() { rightGun.reset(); leftGun.reset(); leftFist.reset(); actions.clear() }
}
