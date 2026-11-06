package io.github.magral.dpipilot.tv

/** Wall-clock, persisted per network: restarting the process never resets the three-day budget. */
object TvCheckPolicy {
    /** Retry partial reachability or a previously confirmed strategy; zero unknown responses stay fast. */
    fun confirmFailure(ok: Boolean,inconclusive: Boolean,passed: Int,known: Boolean): Boolean =
        !ok && !inconclusive && (known || passed in 1..5)
    const val INTERVAL = 3L*24*60*60*1000
    fun remaining(started: Long,now: Long): Long = when {
        started <= 0L -> 0L
        now < started -> INTERVAL // Clock rollback must not create a retry storm.
        else -> (INTERVAL-(now-started)).coerceAtLeast(0L)
    }
    fun shouldCheck(automatic: Boolean,explicit: Boolean,started: Long,now: Long) =
        explicit || (automatic && remaining(started,now) == 0L)
    fun ordered(indices: List<Int>,latency: (Int) -> Long): List<Int> = indices.distinct().sortedWith(
        compareBy<Int> { latency(it).takeIf { ms -> ms > 0L } ?: Long.MAX_VALUE }.thenBy { it })
    /** Current is first on a tie; every included result must have passed the full reachability test. */
    fun fastest(current: Pair<Int,ProbeQuality>?,candidates: List<Pair<Int,ProbeQuality>>): Int? =
        ((if (current == null) emptyList() else listOf(current))+candidates)
            .filter { it.second.eligible && it.second.latencyMs > 0 }
            .minWithOrNull(compareBy<Pair<Int,ProbeQuality>> { it.second.preview.score }.thenBy { it.second.latencyMs })?.first
}
