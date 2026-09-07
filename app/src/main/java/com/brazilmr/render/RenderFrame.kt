package com.brazilmr.render

import com.brazilmr.core.spatial.SpatialProjection
import com.brazilmr.core.spatial.PanelSnapshot

class RenderFrame {
    val projection = SpatialProjection()
    var immersive=true
    val panels=PanelSnapshot()
    val panelPixels=arrayOfNulls<UiTextureExchange>(16)
    val panelVideo=IntArray(16) { -1 }
    var cursorWorldX=0f;var cursorWorldY=0f;var cursorWorldZ=-2f
    var dwellProgress=0f
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
        immersive=other.immersive;panels.copyFrom(other.panels)
        other.panelPixels.copyInto(panelPixels);other.panelVideo.copyInto(panelVideo)
        cursorWorldX=other.cursorWorldX;cursorWorldY=other.cursorWorldY;cursorWorldZ=other.cursorWorldZ;dwellProgress=other.dwellProgress
        projection.copyFrom(other.projection); vr = other.vr; camera = other.camera; mirror = other.mirror
        opacity = other.opacity; scale = other.scale; maxWidth = other.maxWidth; displayRotation = other.displayRotation
        cursorVisible = other.cursorVisible; cursorX = other.cursorX; cursorY = other.cursorY; cursorState = other.cursorState
        externalCount = other.externalCount; other.externalIds.copyInto(externalIds); other.externalRects.copyInto(externalRects)
        objectCount = other.objectCount; other.objects.copyInto(objects); other.objectColors.copyInto(objectColors)
    }
}
