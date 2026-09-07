package com.brazilmr.core.performance

import com.brazilmr.core.filter.OneEuroConfig

enum class PerformanceMode { ECONOMY, BALANCED, PERFORMANCE }
data class XrSettings(
    val trackingEnabled: Boolean = true,
    val trackingFps: Int = 24,
    val trackingWidth: Int = 640,
    val rightHand: Boolean = true,
    val leftHand: Boolean = true,
    val swapHands: Boolean = false,
    val filter: OneEuroConfig = OneEuroConfig(),
    val sbs: Boolean = false,
    val ipdMm: Float = 63f,
    val fovDegrees: Float = 75f,
    val renderScale: Float = 0.85f,
    val renderWidth: Int = 1920,
    val targetFps: Int = 60,
    val frontCamera: Boolean = false,
    val spatialTracking: Boolean = false,
    val passthrough: Boolean = true,
    val uiScale: Float = 1f,
    val uiDistance: Float = 1.7f,
    val uiOpacity: Float = 0.96f,
    val uiOffsetX: Float = 0f,
    val uiOffsetY: Float = 0f,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val dynamicResolution: Boolean = true,
) {
    init {
        require(trackingFps in 5..60 && trackingWidth in setOf(320, 640, 960))
        require(ipdMm in 50f..78f && fovDegrees in 45f..110f)
        require(renderScale in 0.5f..1f && renderWidth in setOf(1280, 1920, 2560))
        require(targetFps in 24..120 && uiScale in 0.5f..1.5f && uiDistance in 0.6f..4f)
        require(uiOpacity in 0.4f..1f && uiOffsetX in -1f..1f && uiOffsetY in -1f..1f)
    }
}

class RenderBudget {
    var renderFps = 60
    var trackingFps = 24
    var renderScale = 0.85f
    var pauseTracking = false
}

/** Android thermal levels: NONE=0 … SEVERE=3 … CRITICAL=4. Never overrides the OS. */
class PerformanceController {
    val budget = RenderBudget()
    private var dynamicScale = 1f
    private var lastAdjustment = 0L
    private var averageMillis = 16.7f
    private var lastHandMillis = 0L
    fun update(settings: XrSettings, frameWorkMillis: Float, thermal: Int, handPresent: Boolean, now: Long): RenderBudget {
        if (handPresent) lastHandMillis = now
        if (frameWorkMillis.isFinite() && frameWorkMillis > 0f) averageMillis += 0.08f * (frameWorkMillis - averageMillis)
        val maxFps = when (settings.performanceMode) { PerformanceMode.ECONOMY -> 30; PerformanceMode.BALANCED -> 60; PerformanceMode.PERFORMANCE -> 120 }
        budget.renderFps = minOf(settings.targetFps, maxFps, if (thermal >= 3) 30 else 120)
        val desiredFrame = 1000f / budget.renderFps
        if (now - lastAdjustment >= 1000) {
            dynamicScale = when {
                averageMillis > desiredFrame * 0.92f -> (dynamicScale - 0.08f).coerceAtLeast(0.55f)
                averageMillis < desiredFrame * 0.6f -> (dynamicScale + 0.025f).coerceAtMost(1f)
                else -> dynamicScale
            }
            lastAdjustment = now
        }
        budget.renderScale = minOf(settings.renderScale, if (settings.dynamicResolution) dynamicScale else 1f, if (thermal >= 3) 0.65f else 1f)
        val trackingCap = when (settings.performanceMode) { PerformanceMode.ECONOMY -> 15; PerformanceMode.BALANCED -> 30; PerformanceMode.PERFORMANCE -> 60 }
        budget.trackingFps = minOf(settings.trackingFps, trackingCap, if (now - lastHandMillis > 1500 || thermal >= 3) 5 else 60)
        budget.pauseTracking = !settings.trackingEnabled || thermal >= 4
        return budget
    }
}
