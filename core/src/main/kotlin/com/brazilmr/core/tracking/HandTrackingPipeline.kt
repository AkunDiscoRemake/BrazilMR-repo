package com.brazilmr.core.tracking

import com.brazilmr.core.filter.OneEuroConfig
import com.brazilmr.core.filter.OneEuroFilter

/** One analyzer thread owns this pipeline and all filters. */
class HandTrackingPipeline(config: OneEuroConfig = OneEuroConfig()) {
    private val filters = Array(126) { OneEuroFilter(config) }
    private val lastSeen = LongArray(2)
    val output = HandFrame()
    var leftEnabled = true
    var rightEnabled = true
    fun configure(config: OneEuroConfig) { for (filter in filters) filter.config = config }
    fun process(raw: HandFrame): HandFrame {
        output.copyFrom(raw)
        filterHand(output.left, 0, leftEnabled, raw.timestampNanos)
        filterHand(output.right, 1, rightEnabled, raw.timestampNanos)
        return output
    }
    private fun filterHand(hand: HandData, side: Int, enabled: Boolean, time: Long) {
        if (!enabled || !hand.present || hand.confidence < 0.5f || !valid(hand)) {
            hand.present = false
            return
        }
        val offset = side * 63
        if (lastSeen[side] == 0L || time - lastSeen[side] > 250_000_000L) {
            for (i in 0 until 63) filters[offset + i].reset()
        }
        for (i in 0 until 63) hand.landmarks[i] = filters[offset + i].filter(hand.landmarks[i], time)
        hand.updateOrientation()
        lastSeen[side] = time
    }
    private fun valid(hand: HandData): Boolean {
        for (i in 0 until 63) if (!hand.landmarks[i].isFinite() || !hand.geometry[i].isFinite()) return false
        return true
    }
}
