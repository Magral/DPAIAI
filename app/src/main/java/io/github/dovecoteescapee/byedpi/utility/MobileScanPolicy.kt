package io.github.dovecoteescapee.byedpi.utility

object MobileScanPolicy {
    fun enabled(prefs: android.content.SharedPreferences,id: String) = prefs.getBoolean("mobile_scan_enabled.$id",true)

    fun previewScore(id: String,result: org.json.JSONObject): Long = if (id == "youtube")
        io.github.dovecoteescapee.byedpi.catalog.PreviewQuality.decode(result.optJSONArray("preview")).score else 0L
    const val INTERVAL = 3L*24*60*60*1000
    fun remaining(started: Long,now: Long): Long = when {
        started <= 0 -> 0
        now < started -> INTERVAL
        else -> (INTERVAL-(now-started)).coerceAtLeast(0)
    }
    fun due(automatic: Boolean,explicit: Boolean,started: Long,now: Long) = explicit || (automatic && remaining(started,now) == 0L)
    fun fastest(results: List<Pair<String,org.json.JSONObject>>,id: String): Pair<String,org.json.JSONObject>? =
        results.filter { ProbeAssessment.evaluate(id,it.second).eligible }.minWithOrNull(
            compareBy<Pair<String,org.json.JSONObject>> { ProbeAssessment.latency(it.second) }.thenBy { previewScore(id,it.second) })
}
