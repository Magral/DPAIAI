package io.github.dovecoteescapee.byedpi.utility

import org.json.JSONObject

/** Evidence about public endpoints, never a claim that an account or video playback works. */
object ProbeAssessment {
    data class Result(val requiredSuccess: Int,val requiredTotal: Int,val extraSuccess: Int,val extraTotal: Int) {
        val eligible get() = requiredTotal > 0 && requiredSuccess == requiredTotal
        val complete get() = eligible && extraSuccess == extraTotal
        fun text() = "основные $requiredSuccess/$requiredTotal"+if (extraTotal > 0) "; дополнительные $extraSuccess/$extraTotal" else ""
    }
    fun evaluate(target: String,result: JSONObject): Result {
        val specs = ServiceTargets.target(target).endpoints
        val raw = result.optJSONArray("rows")
        val rows = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
        var requiredSuccess = 0; var extraSuccess = 0
        for (round in 1..2) for (spec in specs) {
            val matches = rows.filter { it.optString("host") == spec.host && it.optString("kind") == spec.kind && it.optInt("round") == round }
            val row = matches.singleOrNull()
            val passed = result.optString("error").isEmpty() && row != null && row.optBoolean("ok") && !row.optBoolean("skipped") &&
                (spec.kind !in listOf("youtube","image") || row.optBoolean("public"))
            if (passed) { if (spec.required) requiredSuccess++ else extraSuccess++ }
        }
        return Result(requiredSuccess,specs.count { it.required }*2,extraSuccess,specs.count { !it.required }*2)
    }
}
