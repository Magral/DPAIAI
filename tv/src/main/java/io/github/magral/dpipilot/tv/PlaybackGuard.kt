package io.github.magral.dpipilot.tv

/** Useful incoming traffic is a reason to avoid a disruptive switch, never proof of video playback. */
class PlaybackGuard {
    private var baselineAt = -1L
    private var baselineBytes = 0L
    private var usefulAt = -1L
    fun reset() { baselineAt = -1; baselineBytes = 0; usefulAt = -1 }
    fun observe(now: Long,received: Long) {
        if (baselineAt < 0 || now < baselineAt || received < baselineBytes) {
            reset(); baselineAt = now; baselineBytes = received; return
        }
        if (received-baselineBytes >= 256*1024L) {
            usefulAt = now; baselineAt = now; baselineBytes = received
        } else if (now-baselineAt > 30000L) {
            baselineAt = now; baselineBytes = received
        }
    }
    fun recent(now: Long): Boolean = usefulAt >= 0 && now >= usefulAt && now-usefulAt < 90000L
}

object SelectionPolicy {
    // Routine diagnostics never replace a live tunnel with a candidate that has not passed probes.
    // A manual request / observed stall may require one bounded trial even if every probe fails.
    fun replacement(current: Int,confirmed: Int?,forcedTrial: Int?,force: Boolean,usefulTraffic: Boolean): Int? = when {
        usefulTraffic && !force -> null
        confirmed != null && confirmed != current -> confirmed
        force && forcedTrial != null && forcedTrial != current -> forcedTrial
        else -> null
    }
}
