package com.brazilmr.render

import com.brazilmr.core.spatial.SpatialProjection

class RenderFrame {
    val projection = SpatialProjection()
    var vr = false
    var camera = false
    var mirror = false
    var opacity = .96f
    var scale = .85f
    var maxWidth = 1920
    var displayRotation = 1
    var cursorVisible = false
    var cursorX = .5f; var cursorY = .5f
    var cursorState = 0
    var externalCount = 0
    val externalIds = IntArray(4)
    val externalRects = FloatArray(16)
    var objectCount = 0
    val objects = FloatArray(64 * 4)
    val objectColors = IntArray(64)
    fun copyFrom(other: RenderFrame) {
        projection.copyFrom(other.projection); vr = other.vr; camera = other.camera; mirror = other.mirror
        opacity = other.opacity; scale = other.scale; maxWidth = other.maxWidth; displayRotation = other.displayRotation
        cursorVisible = other.cursorVisible; cursorX = other.cursorX; cursorY = other.cursorY; cursorState = other.cursorState
        externalCount = other.externalCount; other.externalIds.copyInto(externalIds); other.externalRects.copyInto(externalRects)
        objectCount = other.objectCount; other.objects.copyInto(objects); other.objectColors.copyInto(objectColors)
    }
}
