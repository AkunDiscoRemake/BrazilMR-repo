package com.brazilmr.core.input

/** One activation per fixation. Moving away is required to re-arm; empty space never clicks. */
class DwellSelector(private val durationMillis: Long=950) {
    private var target=-1
    private var since=0L
    private var latched=false
    var progress=0f;private set
    fun latch(id: Int) { target=id;latched=true;progress=0f }
    fun reset() { target=-1;latched=false;progress=0f }
    fun update(id: Int,now: Long): Boolean {
        if(id<0) { reset();return false }
        if(target!=id) { target=id;since=now;latched=false;progress=0f;return false }
        if(latched) { progress=0f;return false }
        progress=((now-since).toFloat()/durationMillis).coerceIn(0f,1f)
        if(now-since>=durationMillis) { latched=true;progress=0f;return true }
        return false
    }
}
