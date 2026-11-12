package io.github.dovecoteescapee.byedpi.utility

import org.json.JSONObject

/** Fast failures are only deferred evidence; a timeout never becomes an exclusion. */
object MobileSearchPolicy {
    const val FAST_TIMEOUT = 5000
    fun timeout(normal: Int,fast: Boolean) = if (fast) minOf(normal,FAST_TIMEOUT) else normal
    fun conclusiveFailure(result: JSONObject): Boolean {
        if (result.optBoolean("deferred") || result.optBoolean("inconclusive") || result.optBoolean("cancelled") || result.optString("error").isNotEmpty()) return false
        val rows = result.optJSONArray("rows") ?: return false
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.any { !it.optBoolean("ok") && !it.optBoolean("timeout") && !it.optBoolean("unavailable") }
    }
}
