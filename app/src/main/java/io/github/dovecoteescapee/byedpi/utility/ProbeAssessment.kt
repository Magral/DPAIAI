package io.github.dovecoteescapee.byedpi.utility

import org.json.JSONObject

/** Evidence about public endpoints, never a claim that an account or video playback works. */
object ProbeAssessment {
    data class Result(val requiredSuccess: Int,val requiredTotal: Int,val extraSuccess: Int,val extraTotal: Int,val mediaReady: Boolean = true) {
        val eligible get() = mediaReady && requiredTotal > 0 && requiredSuccess == requiredTotal
        val complete get() = eligible && extraSuccess == extraTotal
        fun text() = "обязательные $requiredSuccess/$requiredTotal; дополнительные $extraSuccess/$extraTotal"
    }
    fun latency(result: JSONObject): Long {
        val rows = result.optJSONArray("rows") ?: return Long.MAX_VALUE
        val values = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.takeIf { row ->
            row.optBoolean("ok") && (result.optString("target").takeIf { id -> id in listOf("youtube","instagram") }?.let { id ->
                ServiceTargets.target(id).endpoints.any { spec -> spec.required && spec.host==row.optString("host") && spec.kind==row.optString("kind") }
            } ?: true)
        }?.optLong("elapsedMs")?.takeIf { n -> n > 0 } }.sorted()
        return values.getOrNull(values.size/2) ?: result.optLong("elapsed",Long.MAX_VALUE).takeIf { it > 0 } ?: Long.MAX_VALUE
    }
    fun evaluate(target: String,result: JSONObject): Result {
        val specs = ServiceTargets.target(target).endpoints
        val raw = result.optJSONArray("rows")
        val rows = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
        var requiredSuccess = 0; var extraSuccess = 0
        for (round in 1..ServiceTargets.rounds(target)) for (spec in specs) {
            val matches = rows.filter { it.optString("host") == spec.host && it.optString("kind") == spec.kind && it.optInt("round") == round }
            val row = matches.singleOrNull()
            val passed = result.optString("error").isEmpty() && row != null && row.optBoolean("ok") && !row.optBoolean("skipped") &&
                (spec.kind !in listOf("youtube","image") || row.optBoolean("public"))
            if (passed) { if (spec.required) requiredSuccess++ else extraSuccess++ }
        }
        val mediaReady=true // Anonymous sample availability is not strategy evidence.
        return Result(requiredSuccess,specs.count { it.required }*ServiceTargets.rounds(target),extraSuccess,specs.count { !it.required }*ServiceTargets.rounds(target),mediaReady)
    }
}
