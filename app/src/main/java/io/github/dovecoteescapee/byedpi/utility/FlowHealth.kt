package io.github.dovecoteescapee.byedpi.utility

/** Per-service counters; idle or buffered playback must never look like stalled demand. */
class FlowHealth {
    data class Sample(val at: Long,val tx: Long,val rx: Long,val writes: Long)
    private var baseline: Sample? = null
    private var bad = 0
    var usefulAt = 0L; private set
    var usefulMillis = 0L; private set
    var stalled = false; private set
    fun reset() { baseline = null; bad = 0; stalled = false; usefulAt = 0 }
    fun observe(now: Sample) {
        val old = baseline
        if (old == null || now.at-old.at !in 1..90000 || now.tx < old.tx || now.rx < old.rx || now.writes < old.writes) { baseline = now; bad = 0; stalled = false; return }
        val elapsed = now.at-old.at
        if (now.rx-old.rx >= 256*1024 && elapsed <= 30000) { usefulAt = now.at; usefulMillis += elapsed; bad = 0; stalled = false; baseline = now; return }
        if (elapsed < 45000) return
        val demand = now.tx-old.tx >= 16*1024 || now.writes-old.writes >= 20
        bad = if (demand && now.rx-old.rx < 64*1024) bad+1 else 0
        stalled = bad >= 2 && (usefulAt == 0L || now.at-usefulAt > 90000)
        baseline = now
    }
}
