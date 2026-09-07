package com.brazilmr.core.spatial

import kotlin.math.*

/** A real, independently positioned surface, in metres. +Z normal faces the viewer at the origin. */
class PanelPose {
    var x=0f; var y=0f; var z=-1.7f
    var width=.86f; var height=.645f
    var yaw=0f
    fun set(x: Float,y: Float,z: Float,width: Float,height: Float,yaw: Float=0f): PanelPose {
        require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite())
        require(width in .02f..4f && height in .01f..4f)
        this.x=x;this.y=y;this.z=z;this.width=width;this.height=height;this.yaw=yaw
        return this
    }
    fun copyFrom(p: PanelPose) = set(p.x,p.y,p.z,p.width,p.height,p.yaw)
    fun world(u: Float,v: Float,out: FloatArray,normalOffset: Float=0f) {
        val a=yaw*PI.toFloat()/180;val c=cos(a);val s=sin(a);val lx=(u-.5f)*width
        out[0]=x+c*lx+s*normalOffset;out[1]=y+(.5f-v)*height;out[2]=z-s*lx+c*normalOffset
    }
    /** out = u,v,distance; coordinates are also returned outside the bounds for captured drags. */
    fun intersect(ray: SpatialRay,out: FloatArray,bounded: Boolean=true): Boolean {
        out[0]=Float.NaN;out[1]=Float.NaN;out[2]=Float.POSITIVE_INFINITY
        val a=yaw*PI.toFloat()/180;val c=cos(a);val s=sin(a)
        val denominator=s*ray.dx+c*ray.dz
        if(denominator>=-1e-5f) return false // no back-face clicks
        val t=(s*(x-ray.ox)+c*(z-ray.oz))/denominator
        if(t<=.05f || !t.isFinite()) return false
        val hx=ray.ox+ray.dx*t-x;val hy=ray.oy+ray.dy*t-y;val hz=ray.oz+ray.dz*t-z
        out[0]=(c*hx-s*hz)/width+.5f;out[1]=.5f-hy/height;out[2]=t
        return !bounded || (out[0] in 0f..1f && out[1] in 0f..1f)
    }
}
class SpatialRay {
    var ox=0f;var oy=0f;var oz=0f
    var dx=0f;var dy=0f;var dz=-1f
    fun point(distance: Float,out: FloatArray) { out[0]=ox+dx*distance;out[1]=oy+dy*distance;out[2]=oz+dz*distance }
}

/** Snapshot shared by projection, ray casting and accessibility. No live window collections on GL. */
class PanelSnapshot {
    val projection=SpatialProjection()
    var count=0
    val ids=IntArray(16)
    val poses=Array(16) { PanelPose() }
    fun copyFrom(other: PanelSnapshot) {
        projection.copyFrom(other.projection);count=other.count
        for(i in 0 until count) { ids[i]=other.ids[i];poses[i].copyFrom(other.poses[i]) }
    }
    fun indexOf(id: Int): Int { for(i in 0 until count) if(ids[i]==id) return i;return -1 }
    fun hit(ray: SpatialRay,out: FloatArray): Int {
        var nearest=Float.POSITIVE_INFINITY;var selected=-1;var u=0f;var v=0f
        for(i in 0 until count) if(poses[i].intersect(ray,out) && out[2]<nearest) { nearest=out[2];selected=i;u=out[0];v=out[1] }
        out[0]=u;out[1]=v;out[2]=nearest
        return selected
    }
}

object HeadsetLayout {
    const val DOCK=-1
    const val MENU=-2
    const val STATUS=-3
    fun dock(p: PanelPose) = p.set(0f,-.48f,-1.65f,1.20f,.15f)
    fun menu(p: PanelPose) = p.set(0f,.075f,-1.65f,.82f,.82f)
    fun clock(p: PanelPose) = p.set(.70f,.52f,-1.95f,.48f,.225f,-19f)
    fun window(p: PanelPose,index: Int) {
        val column=index%3
        val x=when(column) {0 -> -.70f;1 -> .70f;else -> 0f}
        val z=if(column==2) -2.15f else -1.85f
        p.set(x,if(index<3) .02f else -.2f,z,.86f,.645f,atan2(-x,-z)*180f/PI.toFloat())
    }
}
