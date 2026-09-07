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
import com.brazilmr.core.spatial.*
import kotlin.math.*

/** Accessible input into independent 3D surfaces, including the final per-eye lens transformation. */
class SpatialInputView(context: Context,private val scene: SpatialUiScene,private val read: (PanelSnapshot)->Unit,private val pointer: (PointerAction,Float,Float,Long)->Unit): View(context) {
    private val snapshot=PanelSnapshot();private val uv=FloatArray(2);private val world=FloatArray(3);private val clip=FloatArray(4)
    private var active=-1
    private val helper=object:ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float,y: Float): Int {
            read(snapshot);val p=snapshot.projection;val eye=if(p.sbs && x>=width/2f)1 else 0
            var u=x/width.coerceAtLeast(1);var v=y/height.coerceAtLeast(1)
            if(p.sbs) { u=u*2-eye;if(!LensMapping.outputToView(u,v,eye,p.lensShift,p.lensVertical,p.lensDistortion,uv))return INVALID_ID;u=uv[0];v=uv[1] }
            return scene.cast(snapshot,u,v,eye)?.takeIf { it.kind!=HitKind.BLOCK }?.id ?: INVALID_ID
        }
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            read(snapshot)
            for(t in scene.targets) if(t.kind==HitKind.BUTTON && bounds(t)!=null)ids.add(t.id)
        }
        override fun onPopulateNodeForVirtualView(id: Int,node: AccessibilityNodeInfoCompat) {
            val t=scene.target(id);node.contentDescription=t?.label ?: "Indisponível";node.className="android.widget.Button"
            node.setBoundsInParent(t?.let { bounds(it) } ?: Rect(0,0,1,1))
            node.isEnabled=t!=null;node.isFocusable=true;node.isClickable=true;node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        override fun onPerformActionForVirtualView(id: Int,action: Int,args: Bundle?): Boolean {
            if(action!=AccessibilityNodeInfoCompat.ACTION_CLICK)return false
            val result=scene.activate(id);if(result)sendEventForVirtualView(id,AccessibilityEvent.TYPE_VIEW_CLICKED);return result
        }
    }
    init { isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;ViewCompat.setAccessibilityDelegate(this,helper);scene.onTargetsChanged={helper.invalidateRoot()};contentDescription="Brazil MR · espaço 3D" }
    private fun bounds(target: SpatialTarget): Rect? {
        val i=snapshot.indexOf(target.panelId);if(i<0)return null
        val pixels=scene.pixels(target.panelId) ?: return null;val p=snapshot.projection
        var l=Float.POSITIVE_INFINITY;var r=Float.NEGATIVE_INFINITY;var top=l;var bottom=r
        for(c in 0..3) {
            val u=(if(c%2==0)target.rect.left else target.rect.right)/pixels.width
            val v=(if(c<2)target.rect.top else target.rect.bottom)/pixels.height
            snapshot.poses[i].world(u,v,world);p.projectWorld(world[0],world[1],world[2],0,clip)
            if(clip[3]<=.05f)return null
            var x=(clip[0]/clip[3]+1)*.5f;var y=(1-clip[1]/clip[3])*.5f
            if(p.sbs) { LensMapping.viewToOutput(x,y,0,p.lensShift,p.lensVertical,p.lensDistortion,uv);x=uv[0];y=uv[1] }
            x*=if(p.sbs)width/2f else width.toFloat();y*=height
            l=min(l,x);r=max(r,x);top=min(top,y);bottom=max(bottom,y)
        }
        val result=Rect(l.toInt(),top.toInt(),r.toInt().coerceAtLeast(l.toInt()+1),bottom.toInt().coerceAtLeast(top.toInt()+1))
        if(!result.intersect(0,0,if(p.sbs)width/2 else width,height))return null
        return result
    }
    override fun dispatchHoverEvent(event: MotionEvent)=helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    @SuppressLint("ClickableViewAccessibility") override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{active=event.getPointerId(0);send(PointerAction.DOWN,event,0)}
            MotionEvent.ACTION_MOVE->{val i=event.findPointerIndex(active);if(i>=0)send(PointerAction.MOVE,event,i)}
            MotionEvent.ACTION_UP->{val i=event.findPointerIndex(active);if(i>=0)send(PointerAction.UP,event,i);active=-1;performClick()}
            MotionEvent.ACTION_CANCEL->{send(PointerAction.CANCEL,event,0);active=-1}
            MotionEvent.ACTION_POINTER_UP->if(event.getPointerId(event.actionIndex)==active){send(PointerAction.CANCEL,event,event.actionIndex);active=-1}
        }
        return true
    }
    private fun send(action: PointerAction,e: MotionEvent,i: Int)=pointer(action,e.getX(i)/width.coerceAtLeast(1),e.getY(i)/height.coerceAtLeast(1),e.eventTime)
    override fun performClick(): Boolean { super.performClick();return true }
}
