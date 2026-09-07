package com.brazilmr.core.spatial

/** Same inexpensive radial mapping is used by the final eye shader and touch input. k=0 is uncalibrated. */
object LensMapping {
    fun outputToView(u: Float,v: Float,eye: Int,shift: Float,vertical: Float,k: Float,out: FloatArray): Boolean {
        val cx=.5f+if(eye==0) shift else -shift;val cy=.5f+vertical
        val x=(u-cx)*2;val y=(v-cy)*2;val f=1+k*(x*x+y*y)
        out[0]=.5f+x*f*.5f;out[1]=.5f+y*f*.5f
        return out[0] in 0f..1f && out[1] in 0f..1f
    }
    fun viewToOutput(u: Float,v: Float,eye: Int,shift: Float,vertical: Float,k: Float,out: FloatArray) {
        val x=(u-.5f)*2;val y=(v-.5f)*2;val r=kotlin.math.sqrt(x*x+y*y)
        var radius=r
        repeat(7) { radius-=(radius*(1+k*radius*radius)-r)/(1+3*k*radius*radius) }
        val factor=if(r<1e-6f) 1f else radius/r
        out[0]=.5f+(if(eye==0) shift else -shift)+x*factor*.5f
        out[1]=.5f+vertical+y*factor*.5f
    }
}
