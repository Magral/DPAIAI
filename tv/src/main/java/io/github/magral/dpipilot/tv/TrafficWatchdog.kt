package io.github.magral.dpipilot.tv

/** Conservative byte counters only. It never inspects accounts, video titles or payloads. */
class TrafficWatchdog {
    data class Sample(val at: Long,val sentPackets: Long,val sentBytes: Long,val receivedBytes: Long)
    private var baseline: Sample? = null
    fun reset() { baseline = null }
    fun stalled(now: Sample): Boolean {
        val old = baseline
        if (old == null || now.at < old.at || now.sentPackets < old.sentPackets ||
            now.sentBytes < old.sentBytes || now.receivedBytes < old.receivedBytes) {
            baseline = now; return false
        }
        // A sleep/wake gap is not an observed continuous stall.
        if (now.at-old.at > 90000L) { baseline = now; return false }
        if (now.at-old.at < 45000L) return false
        baseline = now
        val tx = now.sentBytes-old.sentBytes
        val packets = now.sentPackets-old.sentPackets
        val rx = now.receivedBytes-old.receivedBytes
        // Idle/buffered playback and small telemetry exchanges must not trigger a reset.
        return (packets >= 20 && rx == 0L) || (tx >= 16*1024 && rx < 2048L)
    }
}

object RecoveryPolicy {
    const val STALE_MILLIS = 180000L
    const val MIN_RESTART_GAP = 1800000L
    fun hung(now: Long,heartbeat: Long): Boolean = heartbeat > 0 && now >= heartbeat && now-heartbeat > STALE_MILLIS
    fun allowRestart(now: Long,previous: Long): Boolean = previous <= 0 || now < previous || now-previous >= MIN_RESTART_GAP
}
