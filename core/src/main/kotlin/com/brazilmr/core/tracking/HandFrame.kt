package com.brazilmr.core.tracking

import kotlin.math.sqrt

enum class HandSide { LEFT, RIGHT }
enum class TrackingStatus { STOPPED, STARTING, RUNNING, NO_MODEL, NO_PERMISSION, UNAVAILABLE, ERROR }

/** Owned mutable storage. Never retain a provider's buffers; copy into your own frame. */
class HandData(val side: HandSide) {
    val landmarks = FloatArray(21 * 3)
    /** Metric world landmarks (metres), or aspect-corrected normalized landmarks as fallback. */
    val geometry = FloatArray(21 * 3)
    var present = false
    var confidence = 0f
    var normalX = 0f
    var normalY = 0f
    var normalZ = 0f
    fun x(index: Int) = landmarks[index * 3]
    fun y(index: Int) = landmarks[index * 3 + 1]
    fun z(index: Int) = landmarks[index * 3 + 2]
    fun copyFrom(other: HandData) {
        present = other.present
        confidence = other.confidence
        other.landmarks.copyInto(landmarks)
        other.geometry.copyInto(geometry)
        normalX = other.normalX; normalY = other.normalY; normalZ = other.normalZ
    }
    fun updateOrientation() {
        val ax = geometry[15] - geometry[0]; val ay = geometry[16] - geometry[1]; val az = geometry[17] - geometry[2]
        val bx = geometry[51] - geometry[0]; val by = geometry[52] - geometry[1]; val bz = geometry[53] - geometry[2]
        val nx = ay * bz - az * by; val ny = az * bx - ax * bz; val nz = ax * by - ay * bx
        val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f)
        val sign = if (side == HandSide.RIGHT) 1f else -1f
        normalX = sign * nx / length; normalY = sign * ny / length; normalZ = sign * nz / length
    }
}

class HandFrame {
    val left = HandData(HandSide.LEFT)
    val right = HandData(HandSide.RIGHT)
    var timestampNanos = 0L
    var inferenceMillis = 0f
    var sequence = 0L
    fun copyFrom(other: HandFrame) {
        left.copyFrom(other.left); right.copyFrom(other.right)
        timestampNanos = other.timestampNanos; inferenceMillis = other.inferenceMillis; sequence = other.sequence
    }
    fun clear() { left.present = false; right.present = false }
}

/** Small synchronized mailbox: no camera/ImageProxy/MediaPipe object crosses this boundary. */
class HandFrameMailbox {
    private val frame = HandFrame()
    @Synchronized fun publish(source: HandFrame) { frame.copyFrom(source) }
    @Synchronized fun readInto(destination: HandFrame) { destination.copyFrom(frame) }
    @Synchronized fun clear() { frame.clear(); frame.sequence++ }
}

interface HandTrackingManager : AutoCloseable {
    val status: TrackingStatus
    val detail: String
    fun start()
    fun stop()
    fun readInto(destination: HandFrame)
}
