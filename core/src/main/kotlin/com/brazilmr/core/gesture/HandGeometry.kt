package com.brazilmr.core.gesture

import com.brazilmr.core.tracking.HandData
import kotlin.math.sqrt

class HandFeatures {
    var tracked = false
    var indexExtended = false
    var indexCurled = false
    var gunBase = false
    var fist = false
    var pinchRatio = Float.POSITIVE_INFINITY
}

/** Joint angles and ratios in 3D, invariant under translation, scale and rotation. */
object HandGeometry {
    fun describe(hand: HandData, out: HandFeatures) {
        out.tracked = hand.present
        if (!hand.present) {
            out.indexExtended = false; out.indexCurled = false; out.gunBase = false
            out.fist = false; out.pinchRatio = Float.POSITIVE_INFINITY
            return
        }
        val p = hand.geometry
        val palm = distance(p, 5, 17).coerceAtLeast(1e-6f)
        out.indexExtended = straight(p, 5, 6, 7, 8)
        out.indexCurled = curled(p, 5, 6, 8)
        val otherCurled = curled(p, 9, 10, 12) && curled(p, 13, 14, 16) && curled(p, 17, 18, 20)
        val thumbExtended = cosine(p, 2, 3, 4) < -0.50f && distance(p, 4, 5) / palm > 0.65f
        out.gunBase = thumbExtended && otherCurled
        out.fist = !thumbExtended && out.indexCurled && otherCurled
        out.pinchRatio = distance(p, 4, 8) / palm
    }
    private fun straight(p: FloatArray, mcp: Int, pip: Int, dip: Int, tip: Int) =
        cosine(p, mcp, pip, dip) < -0.80f && cosine(p, pip, dip, tip) < -0.75f &&
            distance(p, tip, 0) > distance(p, pip, 0) * 1.08f
    private fun curled(p: FloatArray, mcp: Int, pip: Int, tip: Int) =
        cosine(p, mcp, pip, tip) > -0.45f && distance(p, tip, 0) < distance(p, pip, 0) * 1.15f
    private fun cosine(p: FloatArray, a: Int, b: Int, c: Int): Float {
        val ax = p[a * 3] - p[b * 3]; val ay = p[a * 3 + 1] - p[b * 3 + 1]; val az = p[a * 3 + 2] - p[b * 3 + 2]
        val bx = p[c * 3] - p[b * 3]; val by = p[c * 3 + 1] - p[b * 3 + 1]; val bz = p[c * 3 + 2] - p[b * 3 + 2]
        val denom = sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz))
        return if (denom < 1e-10f) 1f else (ax * bx + ay * by + az * bz) / denom
    }
    fun distance(p: FloatArray, a: Int, b: Int): Float {
        val x = p[a * 3] - p[b * 3]; val y = p[a * 3 + 1] - p[b * 3 + 1]; val z = p[a * 3 + 2] - p[b * 3 + 2]
        return sqrt(x * x + y * y + z * z)
    }
}
