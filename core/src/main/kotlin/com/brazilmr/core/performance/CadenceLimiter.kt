package com.brazilmr.core.performance

/** Deadline-based pacing. Unlike `now-last >= period`, 24 FPS on a 30 FPS producer does not collapse to 15. */
class CadenceLimiter {
    private var period = 0L
    private var next = Long.MIN_VALUE
    private var lastAccepted = Long.MIN_VALUE
    @Synchronized fun reset() { period=0;next=Long.MIN_VALUE;lastAccepted=Long.MIN_VALUE }
    @Synchronized fun ready(nowNanos: Long, framesPerSecond: Int): Boolean {
        require(framesPerSecond in 1..240)
        val requested=1_000_000_000L/framesPerSecond
        if(requested!=period) {
            period=requested
            if(lastAccepted!=Long.MIN_VALUE) next=lastAccepted+period
        }
        return (lastAccepted==Long.MIN_VALUE || nowNanos>lastAccepted) && (next==Long.MIN_VALUE || nowNanos>=next)
    }
    @Synchronized fun acquire(nowNanos: Long, framesPerSecond: Int): Boolean {
        if(!ready(nowNanos,framesPerSecond)) return false
        next=if(next==Long.MIN_VALUE || nowNanos-next>period) nowNanos+period else next+period
        lastAccepted=nowNanos
        return true
    }
}
