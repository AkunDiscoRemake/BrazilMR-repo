package com.brazilmr.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.brazilmr.core.input.PointerAction
import com.brazilmr.core.spatial.SpatialProjection
import kotlin.math.*

/** Transparent native touch + accessibility layer. Visuals are drawn once by XrUiScene into the XR texture. */
class WorkspaceInputView(
    context: Context,
    private val scene: XrUiScene,
    private val readProjection: (SpatialProjection) -> Unit,
    private val screenPointer: (PointerAction, Float, Float, Long) -> Unit,
) : View(context) {
    private var touchId = -1
    private val projection = SpatialProjection()
    private val coordinate = FloatArray(2)
    private val clip = FloatArray(4)
    private val helper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (!map(x,y)) return INVALID_ID
            return scene.hitTest(coordinate[0]*XrUiScene.WIDTH,coordinate[1]*XrUiScene.HEIGHT)?.takeIf { it.kind != HitKind.BLOCK }?.id ?: INVALID_ID
        }
        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (target in scene.visibleTargets) if (target.kind != HitKind.BLOCK) virtualViewIds.add(target.id)
        }
        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val target = scene.target(virtualViewId)
            node.className = "android.widget.Button"
            node.contentDescription = target?.label ?: "Elemento indisponível"
            node.isEnabled = target != null
            node.isFocusable = true
            if (target != null) {
                node.setBoundsInParent(screenBounds(target))
                if (target.kind == HitKind.BUTTON || target.kind == HitKind.MOVE) { node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK); node.isClickable = true }
            } else node.setBoundsInParent(Rect(0,0,1,1))
        }
        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val handled = scene.activate(virtualViewId)
            if (handled) sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return handled
        }
    }
    init {
        isFocusable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, helper)
        scene.onTargetsChanged = { helper.invalidateRoot() }
        contentDescription = "Brazil MR workspace"
    }
    private fun map(x: Float, y: Float): Boolean {
        readProjection(projection)
        val normalizedX = x/width.coerceAtLeast(1); val normalizedY = y/height.coerceAtLeast(1)
        val eye = if (projection.sbs && normalizedX >= .5f) 1 else 0
        val eyeX = if (projection.sbs) normalizedX*2-eye else normalizedX
        return projection.rayToUi(eyeX, normalizedY, eye, coordinate)
    }
    private fun screenBounds(target: UiTarget): Rect {
        readProjection(projection)
        val eyeWidth = if (projection.sbs) width/2f else width.toFloat()
        var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (i in 0..3) {
            projection.project((if(i%2 == 0) target.rect.left else target.rect.right)/XrUiScene.WIDTH, (if(i<2) target.rect.top else target.rect.bottom)/XrUiScene.HEIGHT, 0, clip)
            if (clip[3] <= 0f) return Rect(0,0,1,1)
            val x = (clip[0]/clip[3]+1)*.5f*eyeWidth; val y = (1-clip[1]/clip[3])*.5f*height
            left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)
        }
        return Rect(left.toInt(),top.toInt(),max(left+1,right).toInt(),max(top+1,bottom).toInt())
    }
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchId = event.getPointerId(0); parent?.requestDisallowInterceptTouchEvent(true); send(PointerAction.DOWN,event,0) }
            MotionEvent.ACTION_MOVE -> { val index = event.findPointerIndex(touchId); if(index>=0) send(PointerAction.MOVE,event,index) }
            MotionEvent.ACTION_UP -> { val index = event.findPointerIndex(touchId); if(index>=0) send(PointerAction.UP,event,index); touchId=-1; performClick() }
            MotionEvent.ACTION_CANCEL -> { send(PointerAction.CANCEL,event,0); touchId=-1 }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == touchId) { send(PointerAction.CANCEL,event,event.actionIndex);touchId=-1 }
        }
        return true
    }
    private fun send(action: PointerAction,event: MotionEvent,index: Int) { screenPointer(action,event.getX(index)/width.coerceAtLeast(1),event.getY(index)/height.coerceAtLeast(1),event.eventTime) }
    override fun performClick(): Boolean { super.performClick(); return true }
}
