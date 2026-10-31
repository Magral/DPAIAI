package io.github.magral.dpipilot.tv

import kotlin.math.ln

/** Quality ranks the measured sample, not the bitrate/resolution of the viewer's current video. */
data class ProbeQuality(val controlOk: Boolean,val passed: Int,val latencyMs: Long,val media: MediaResult) {
    val eligible: Boolean get() = controlOk && media.status != MediaStatus.FAILED
    fun value(): Double {
        if (!controlOk) return passed.toDouble()
        val base = 100.0-(latencyMs.coerceAtLeast(0).coerceAtMost(30000)/1000.0)
        return when (media.status) {
            MediaStatus.FAILED -> 20.0
            MediaStatus.UNAVAILABLE -> base
            MediaStatus.PASSED -> base+100+ln(1+media.minKbps.coerceAtLeast(0).toDouble())*10+media.consistency.coerceIn(0,100)/5.0
        }
    }
}
object QualityPolicy {
    fun better(candidate: ProbeQuality,current: ProbeQuality,forced: Boolean): Boolean {
        if (!candidate.eligible) return false
        if (forced || !current.eligible) return true
        // Unknown metadata availability must never look better than successful actual media delivery.
        if (current.media.status == MediaStatus.PASSED && candidate.media.status != MediaStatus.PASSED) return false
        if (candidate.media.status == MediaStatus.PASSED && current.media.status != MediaStatus.PASSED) return true
        if (candidate.media.status == MediaStatus.PASSED) {
            return candidate.media.minKbps > current.media.minKbps*1.25 &&
                candidate.media.consistency >= current.media.consistency-10 && candidate.latencyMs <= current.latencyMs*2+500
        }
        // With metadata unavailable, require a substantial repeatable latency improvement.
        return candidate.latencyMs+500 < current.latencyMs*0.65
    }
    fun regression(previous: ProbeQuality,current: ProbeQuality): Boolean =
        (previous.controlOk && !current.controlOk) ||
        (previous.media.status == MediaStatus.PASSED && current.media.status == MediaStatus.FAILED) ||
        (previous.media.status == MediaStatus.PASSED && current.media.status == MediaStatus.PASSED && current.media.minKbps < previous.media.minKbps*0.6)
}

data class Trial(val previous: Int,val candidate: Int,val started: Long,val restorable: Boolean,val baseline: ProbeQuality) {
    fun expired(now: Long): Boolean = now < started || now-started >= 120000L
    fun rollback(now: Long,repeatedRegression: Boolean,trafficStall: Boolean): Boolean = restorable && now >= started &&
        (repeatedRegression || (trafficStall && now-started >= 45000L))
}

data class QualityRecord(var successes: Int = 0,var failures: Int = 0,var stalls: Int = 0,var rollbacks: Int = 0,
    var usefulSeconds: Long = 0,var activeSeconds: Long = 0,var minKbps: Long = 0,var latencyMs: Long = 0,var lastSeen: Long = 0) {
    fun rank(): Double = (successes+1.0)/(successes+failures+2.0)*100+
        ln(1+usefulSeconds.coerceIn(0,36000).toDouble())*8+ln(1+minKbps.coerceIn(0,100000).toDouble())*2-
        stalls.coerceAtMost(20)*12-rollbacks.coerceAtMost(20)*20
}
