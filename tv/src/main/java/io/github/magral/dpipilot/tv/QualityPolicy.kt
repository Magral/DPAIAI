package io.github.magral.dpipilot.tv

import kotlin.math.ln

/** Ranks bounded reachability checks; never claims to measure the viewer's video bitrate. */
data class ProbeQuality(val controlOk: Boolean,val passed: Int,val latencyMs: Long) {
    val eligible: Boolean get() = controlOk
    fun value(): Double = if (!controlOk) passed.toDouble() else
        100.0-(latencyMs.coerceIn(0,30000)/1000.0)
}
object QualityPolicy {
    fun better(candidate: ProbeQuality,current: ProbeQuality,forced: Boolean): Boolean {
        if (!candidate.eligible) return false
        if (forced || !current.eligible) return true
        // Useful live traffic is protected by PlaybackGuard before this comparison.
        // Only a large latency improvement can justify an optional idle trial.
        return candidate.latencyMs+500 < current.latencyMs*0.65
    }
    fun regression(previous: ProbeQuality,current: ProbeQuality): Boolean =
        previous.controlOk && !current.controlOk
}

data class Trial(val previous: Int,val candidate: Int,val started: Long,val restorable: Boolean,val baseline: ProbeQuality) {
    fun expired(now: Long): Boolean = now < started || now-started >= 120000L
    fun rollback(now: Long,repeatedRegression: Boolean,trafficStall: Boolean): Boolean = restorable && now >= started &&
        (repeatedRegression || (trafficStall && now-started >= 45000L))
}

data class QualityRecord(var successes: Int = 0,var failures: Int = 0,var stalls: Int = 0,var rollbacks: Int = 0,
    var usefulSeconds: Long = 0,var activeSeconds: Long = 0,var latencyMs: Long = 0,var lastSeen: Long = 0) {
    fun rank(): Double = (successes+1.0)/(successes+failures+2.0)*100+
        ln(1+usefulSeconds.coerceIn(0,36000).toDouble())*8-
        stalls.coerceAtMost(20)*12-rollbacks.coerceAtMost(20)*20
}
