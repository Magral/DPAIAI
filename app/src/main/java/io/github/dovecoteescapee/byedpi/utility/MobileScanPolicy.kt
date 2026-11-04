package io.github.dovecoteescapee.byedpi.utility

object MobileScanPolicy {
    const val INTERVAL = 3L*24*60*60*1000
    fun remaining(started: Long,now: Long): Long = when {
        started <= 0 -> 0
        now < started -> INTERVAL
        else -> (INTERVAL-(now-started)).coerceAtLeast(0)
    }
    fun due(automatic: Boolean,explicit: Boolean,started: Long,now: Long) = explicit || (automatic && remaining(started,now) == 0L)
    fun fastest(results: List<Pair<String,org.json.JSONObject>>,id: String): Pair<String,org.json.JSONObject>? =
        results.filter { ProbeAssessment.evaluate(id,it.second).eligible }.minByOrNull { ProbeAssessment.latency(it.second) }
}
