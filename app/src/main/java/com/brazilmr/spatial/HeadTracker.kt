package com.brazilmr.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.brazilmr.core.spatial.Quaternion
import com.brazilmr.core.spatial.SpatialProjection
import kotlin.math.*

/** Sensor-only 3DoF fallback; no claim of computational 6DoF without a spatial provider. */
class HeadTracker(context: Context) : SensorEventListener, AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available get() = sensor != null
    @Volatile var displayRotation = Surface.ROTATION_90
    private val rotation = FloatArray(9)
    private val aligned = FloatArray(9)
    private val current = Quaternion()
    private val origin = Quaternion()
    private val relative = Quaternion()
    private var centered = false
    fun start() { sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    @Synchronized fun recenter() {
        val yaw=atan2(2*(current.x*current.z+current.w*current.y),1-2*(current.x*current.x+current.y*current.y))
        origin.set(0f,sin(yaw/2),0f,cos(yaw/2));centered=true;updateRelative()
    }
    @Synchronized fun readInto(projection: SpatialProjection) { projection.orientation.copyFrom(relative) }
    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val x: Int; val y: Int
        when (displayRotation) {
            Surface.ROTATION_90 -> { x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180 -> { x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270 -> { x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X }
            else -> { x = SensorManager.AXIS_X; y = SensorManager.AXIS_Y }
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, aligned)
        val m = aligned
        val qw = sqrt((1f + m[0] + m[4] + m[8]).coerceAtLeast(0f)) / 2f
        val qx = Math.copySign(sqrt((1f + m[0] - m[4] - m[8]).coerceAtLeast(0f)) / 2f, m[7] - m[5])
        val qy = Math.copySign(sqrt((1f - m[0] + m[4] - m[8]).coerceAtLeast(0f)) / 2f, m[2] - m[6])
        val qz = Math.copySign(sqrt((1f - m[0] - m[4] + m[8]).coerceAtLeast(0f)) / 2f, m[3] - m[1])
        // Android world is Z-up; renderer/ARCore world is Y-up. Keep gravity, recenter only heading.
        val c=.70710678f
        current.set(c*qx-c*qw,c*qy+c*qz,c*qz-c*qy,c*qw+c*qx)
        if(!centered)recenter() else updateRelative()
    }
    private fun updateRelative() {
        relative.set(
            origin.w * current.x - origin.x * current.w - origin.y * current.z + origin.z * current.y,
            origin.w * current.y + origin.x * current.z - origin.y * current.w - origin.z * current.x,
            origin.w * current.z - origin.x * current.y + origin.y * current.x - origin.z * current.w,
            origin.w * current.w + origin.x * current.x + origin.y * current.y + origin.z * current.z,
        )
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() { manager.unregisterListener(this) }
}
