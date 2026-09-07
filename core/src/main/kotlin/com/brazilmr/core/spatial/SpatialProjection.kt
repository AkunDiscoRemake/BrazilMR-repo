package com.brazilmr.core.spatial

import kotlin.math.*

class Quaternion(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f) {
    fun set(x: Float, y: Float, z: Float, w: Float) {
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (!norm.isFinite() || norm < 1e-6f) { this.x = 0f; this.y = 0f; this.z = 0f; this.w = 1f; return }
        this.x = x / norm; this.y = y / norm; this.z = z / norm; this.w = w / norm
    }
    fun copyFrom(other: Quaternion) = set(other.x, other.y, other.z, other.w)
    fun rotate(vx: Float, vy: Float, vz: Float, out: FloatArray, inverse: Boolean = false) {
        val sign = if (inverse) -1f else 1f
        val qx = x * sign; val qy = y * sign; val qz = z * sign
        val tx = 2f * (qy * vz - qz * vy); val ty = 2f * (qz * vx - qx * vz); val tz = 2f * (qx * vy - qy * vx)
        out[0] = vx + w * tx + qy * tz - qz * ty
        out[1] = vy + w * ty + qz * tx - qx * tz
        out[2] = vz + w * tz + qx * ty - qy * tx
    }
}

/** Same projection is used by OpenGL and hit testing. One instance per thread. */
class SpatialProjection {
    var spatial = false
    var sbs = false
    var eyeAspect = 1f
    var ipdMetres = 0.063f
    var fovDegrees = 75f
    var planeWidth = 1.95f
    var planeHeight = planeWidth / (16f / 9f)
    var distance = 1.7f
    var centerX = 0f; var centerY = 0f
    var positionX = 0f; var positionY = 0f; var positionZ = 0f
    val orientation = Quaternion()
    private val tmp = FloatArray(3)
    private val origin = FloatArray(3)
    fun copyFrom(other: SpatialProjection) {
        spatial = other.spatial; sbs = other.sbs; eyeAspect = other.eyeAspect
        ipdMetres = other.ipdMetres; fovDegrees = other.fovDegrees
        planeWidth = other.planeWidth; planeHeight = other.planeHeight; distance = other.distance
        centerX = other.centerX; centerY = other.centerY
        positionX = other.positionX; positionY = other.positionY; positionZ = other.positionZ
        orientation.copyFrom(other.orientation)
    }
    fun project(u: Float, v: Float, eye: Int, clip: FloatArray) {
        if (!spatial) {
            val scale = planeWidth / 1.95f
            clip[0] = (u * 2 - 1) * scale + centerX; clip[1] = (1 - v * 2) * scale + centerY
            clip[2] = 0f; clip[3] = 1f; return
        }
        projectWorld((u - 0.5f) * planeWidth + centerX, (0.5f - v) * planeHeight + centerY, -distance, eye, clip)
    }
    fun projectWorld(x: Float, y: Float, z: Float, eye: Int, clip: FloatArray) {
        val eyeX = if (sbs) (if (eye == 0) -0.5f else 0.5f) * ipdMetres else 0f
        orientation.rotate(eyeX, 0f, 0f, origin)
        orientation.rotate(x - origin[0] - positionX, y - origin[1] - positionY, z - origin[2] - positionZ, tmp, true)
        val tanHalf = tan(fovDegrees * PI.toFloat() / 360f)
        clip[0] = tmp[0] / (eyeAspect * tanHalf)
        clip[1] = tmp[1] / tanHalf
        clip[2] = -1.0010005f * tmp[2] - 0.10005003f // near .05, far 100
        clip[3] = -tmp[2]
    }
    /** x/y are normalized within ONE eye; caller splits touch coordinates for SBS. */
    fun rayToUi(x: Float, y: Float, eye: Int, out: FloatArray): Boolean {
        out[0] = Float.NaN; out[1] = Float.NaN
        if (!x.isFinite() || !y.isFinite()) return false
        if (!spatial) {
            val scale = planeWidth / 1.95f
            out[0] = ((x * 2 - 1 - centerX) / scale + 1) * .5f
            out[1] = (1 - (1 - y * 2 - centerY) / scale) * .5f
            return out[0] in 0f..1f && out[1] in 0f..1f
        }
        val tanHalf = tan(fovDegrees * PI.toFloat() / 360f)
        orientation.rotate((2 * x - 1) * eyeAspect * tanHalf, (1 - 2 * y) * tanHalf, -1f, tmp)
        val eyeX = if (sbs) (if (eye == 0) -0.5f else 0.5f) * ipdMetres else 0f
        orientation.rotate(eyeX, 0f, 0f, origin)
        origin[0] += positionX; origin[1] += positionY; origin[2] += positionZ
        if (abs(tmp[2]) < 1e-6f) return false
        val t = (-distance - origin[2]) / tmp[2]
        if (t <= 0f) return false
        out[0] = (origin[0] + tmp[0] * t - centerX) / planeWidth + 0.5f
        out[1] = 0.5f - (origin[1] + tmp[1] * t - centerY) / planeHeight
        return out[0] in 0f..1f && out[1] in 0f..1f
    }
}

/** Camera preview uses center-crop. Landmarks must undergo the identical crop/mirroring. */
object CameraCoordinates {
    fun map(x: Float, y: Float, sourceAspect: Float, destinationAspect: Float, mirror: Boolean, out: FloatArray) {
        val mx = if (mirror) 1f - x else x
        if (sourceAspect > destinationAspect) {
            out[0] = (mx - 0.5f) * sourceAspect / destinationAspect + 0.5f; out[1] = y
        } else {
            out[0] = mx; out[1] = (y - 0.5f) * destinationAspect / sourceAspect + 0.5f
        }
    }
}
