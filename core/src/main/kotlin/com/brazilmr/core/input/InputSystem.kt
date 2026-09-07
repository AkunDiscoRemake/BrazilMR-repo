package com.brazilmr.core.input

import com.brazilmr.core.gesture.HandFeatures
import com.brazilmr.core.tracking.HandData

enum class PointerAction { MOVE, DOWN, UP, CANCEL }
enum class InputSource { TOUCH, HAND, ACCESSIBILITY, GAZE }
enum class CursorState { NORMAL, HOVER, PRESSED, DISABLED }

/** Event instance is borrowed until the callback returns. Coordinates are screen-normalized. */
class PointerEvent {
    var action = PointerAction.MOVE
    var source = InputSource.HAND
    var x = 0f; var y = 0f
    var timeMillis = 0L
}
fun interface InputSink { fun onPointer(event: PointerEvent) }

class InputSystem(private val sink: InputSink) {
    private val event = PointerEvent()
    private var pinched = false
    private var releaseRequired = false
    private var candidateSince = Long.MIN_VALUE
    private var releaseSince = Long.MIN_VALUE
    private var lastRelease = Long.MIN_VALUE
    private var touchActive = false
    private var touchUntil = Long.MIN_VALUE
    var cursorState = CursorState.DISABLED; private set
    var cursorX = 0.5f; private set
    var cursorY = 0.5f; private set
    var hovered = false

    fun touch(action: PointerAction, x: Float, y: Float, time: Long, source: InputSource = InputSource.TOUCH) {
        if (!x.isFinite() || !y.isFinite()) return
        if (action == PointerAction.DOWN) { cancelHand(time); touchActive = true }
        if (action == PointerAction.UP || action == PointerAction.CANCEL) touchActive = false
        touchUntil = time + 750
        emit(action, source, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), time)
    }
    fun hand(hand: HandData, features: HandFeatures, suppress: Boolean, now: Long) {
        if (!hand.present || !features.tracked || suppress || touchActive || now < touchUntil) {
            cancelHand(now)
            cursorState = if (hand.present && !touchActive && now >= touchUntil) CursorState.NORMAL else CursorState.DISABLED
            if (hand.present) { cursorX = hand.x(8).coerceIn(0f, 1f); cursorY = hand.y(8).coerceIn(0f, 1f) }
            return
        }
        cursorX = hand.x(8).coerceIn(0f, 1f); cursorY = hand.y(8).coerceIn(0f, 1f)
        emit(PointerAction.MOVE, InputSource.HAND, cursorX, cursorY, now)
        if (releaseRequired) {
            if (features.pinchRatio > 0.43f) releaseRequired = false
            else { cursorState = if (hovered) CursorState.HOVER else CursorState.NORMAL; return }
        }
        if (!pinched) {
            releaseSince = Long.MIN_VALUE
            if (features.pinchRatio < 0.28f && (lastRelease == Long.MIN_VALUE || now - lastRelease >= 250)) {
                if (candidateSince == Long.MIN_VALUE) candidateSince = now
                if (now - candidateSince >= 65) {
                    pinched = true; candidateSince = Long.MIN_VALUE
                    emit(PointerAction.DOWN, InputSource.HAND, cursorX, cursorY, now)
                }
            } else candidateSince = Long.MIN_VALUE
        } else {
            if (features.pinchRatio > 0.43f) {
                if (releaseSince == Long.MIN_VALUE) releaseSince = now
                if (now - releaseSince >= 55) {
                    pinched = false; lastRelease = now; releaseSince = Long.MIN_VALUE
                    emit(PointerAction.UP, InputSource.HAND, cursorX, cursorY, now)
                }
            } else releaseSince = Long.MIN_VALUE
        }
        cursorState = if (pinched) CursorState.PRESSED else if (hovered) CursorState.HOVER else CursorState.NORMAL
    }
    fun touchOwns(now: Long) = touchActive || now < touchUntil
    fun gaze(action: PointerAction,now: Long,x: Float=.5f,y: Float=.5f) {
        if(touchOwns(now)) return
        cursorX=x;cursorY=y
        emit(action,InputSource.GAZE,x,y,now)
        cursorState=if(action==PointerAction.DOWN) CursorState.PRESSED else if(hovered) CursorState.HOVER else CursorState.NORMAL
    }
    fun cancelHand(time: Long) {
        if (pinched || candidateSince != Long.MIN_VALUE) releaseRequired = true
        if (pinched) { emit(PointerAction.CANCEL, InputSource.HAND, cursorX, cursorY, time); lastRelease = time }
        pinched = false; candidateSince = Long.MIN_VALUE; releaseSince = Long.MIN_VALUE
        cursorState = CursorState.DISABLED
    }
    fun reset(time: Long) {
        cancelHand(time)
        if (touchActive) emit(PointerAction.CANCEL, InputSource.TOUCH, cursorX, cursorY, time)
        touchActive = false; touchUntil = Long.MIN_VALUE
    }
    private fun emit(action: PointerAction, source: InputSource, x: Float, y: Float, time: Long) {
        event.action = action; event.source = source; event.x = x; event.y = y; event.timeMillis = time
        sink.onPointer(event)
    }
}
