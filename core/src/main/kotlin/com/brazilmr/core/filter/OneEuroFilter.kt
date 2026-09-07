package com.brazilmr.core.filter

import kotlin.math.PI
import kotlin.math.abs

/** Casiez et al., CHI 2012. Cutoffs in Hz, beta in Hz / (units / second). */
data class OneEuroConfig(val minimumCutoff: Float = 2f, val beta: Float = 8f, val derivativeCutoff: Float = 1f) {
    init {
        require(minimumCutoff.isFinite() && minimumCutoff > 0f)
        require(beta.isFinite() && beta >= 0f)
        require(derivativeCutoff.isFinite() && derivativeCutoff > 0f)
    }
}

class OneEuroFilter(var config: OneEuroConfig = OneEuroConfig()) {
    private var initialized = false
    private var lastTime = 0L
    private var previousRaw = 0f
    private var filtered = 0f
    private var derivative = 0f
    fun reset() { initialized = false }
    fun filter(value: Float, timestampNanos: Long): Float {
        if (!value.isFinite()) return if (initialized) filtered else 0f
        if (initialized && timestampNanos <= lastTime) return filtered
        if (!initialized || timestampNanos - lastTime > 500_000_000L) {
            initialized = true; lastTime = timestampNanos; previousRaw = value; filtered = value; derivative = 0f
            return value
        }
        val dt = ((timestampNanos - lastTime) * 1e-9).toFloat().coerceAtLeast(1e-6f)
        val rawDerivative = (value - previousRaw) / dt
        val da = alpha(config.derivativeCutoff, dt)
        derivative += da * (rawDerivative - derivative)
        val cutoff = config.minimumCutoff + config.beta * abs(derivative)
        filtered += alpha(cutoff, dt) * (value - filtered)
        lastTime = timestampNanos; previousRaw = value
        return filtered
    }
    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
