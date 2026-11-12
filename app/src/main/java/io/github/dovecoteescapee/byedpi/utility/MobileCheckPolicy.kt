package io.github.dovecoteescapee.byedpi.utility

import org.json.JSONObject

/** TV's single confirming retry, with each mobile service's own full threshold. */
object MobileCheckPolicy {
    fun confirmFailure(id: String,result: JSONObject,known: Boolean): Boolean {
        val score = ProbeAssessment.evaluate(id,result)
        val passed = score.requiredSuccess+score.extraSuccess
        val total = score.requiredTotal+score.extraTotal
        return !score.eligible && !result.optBoolean("cancelled") && !result.optBoolean("inconclusive") &&
            result.optString("error").isEmpty() && total > 0 && (known || passed in 1 until total)
    }
}
