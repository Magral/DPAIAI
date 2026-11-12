package io.github.dovecoteescapee.byedpi.utility

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import org.json.JSONArray
import org.json.JSONObject

/** Complete evidence and permanent exclusions for one service in one network profile. */
class MobileStrategyStore(private val prefs: SharedPreferences,private val id: String,profile: String) {
    private val key = "mobile_history.$id.$profile"
    fun entries(): List<JSONObject> = runCatching {
        val rows = JSONArray(prefs.getString(key,"[]"))
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { CatalogProtocol.validCommand(it.optString("command")) }
    }.getOrDefault(emptyList())
    private fun isExcluded(row: JSONObject) = row.optBoolean("excluded",row.optInt("bad") > 0 && row.optInt("good") == 0)
    fun excludedIds(): Set<String> = entries().filter { isExcluded(it) }.map { CatalogProtocol.id(it.optString("command")) }.toSet()
    fun excluded(command: String) = CatalogProtocol.id(command) in excludedIds()
    fun known(command: String) = entries().any { it.optString("command") == command && it.optInt("good") > 0 }
    fun choices(): List<JSONObject> {
        val expected = ServiceTargets.target(id).endpoints.size*2
        return entries().filter { !isExcluded(it) && it.optBoolean("verified") && it.optInt("passed") == expected && it.optInt("total") == expected && it.optInt("schema") == 2 }
            .sortedWith(compareBy<JSONObject> { MobileScanPolicy.previewScore(id,it) }
                .thenBy { it.optLong("latencyMs",Long.MAX_VALUE).takeIf { n -> n > 0 } ?: Long.MAX_VALUE }
                .thenBy { CatalogProtocol.id(it.optString("command")) })
    }
    fun freshResult(command: String,now: Long = System.currentTimeMillis()): JSONObject? {
        val row = choices().firstOrNull { it.optString("command") == command } ?: return null
        val checked = row.optLong("checkedAt")
        if (checked <= 0 || now < checked || now-checked >= MobileScanPolicy.INTERVAL) return null
        return row.optJSONObject("result")?.takeIf { ProbeAssessment.evaluate(id,it).eligible }
    }
    private fun update(command: String,edit: (JSONObject)->Unit) {
        require(CatalogProtocol.validCommand(command))
        val rows = entries().toMutableList()
        val row = rows.firstOrNull { it.optString("command") == command } ?: JSONObject().put("command",command).also { rows.add(it) }
        edit(row); row.put("at",System.currentTimeMillis())
        prefs.edit().putString(key,JSONArray(rows).toString()).apply()
    }
    fun accept(command: String,result: JSONObject,useful: Long = 0): Boolean {
        if (!ProbeAssessment.evaluate(id,result).eligible) { reject(command,result);return false }
        if (excluded(command)) return false
        val a = ProbeAssessment.evaluate(id,result)
        update(command) { it.put("checkedAt",System.currentTimeMillis()).put("result",JSONObject(result.toString())).put("schema",2).put("verified",true).put("passed",a.requiredSuccess+a.extraSuccess).put("total",a.requiredTotal+a.extraTotal)
            .put("latencyMs",ProbeAssessment.latency(result)).put("good",it.optInt("good")+1).put("usefulMillis",it.optLong("usefulMillis")+useful.coerceAtLeast(0))
            result.optJSONArray("preview")?.let { preview -> it.put("preview",preview) }
        }
        return true
    }
    fun report(command: String) {
        update(command) { it.put("verified",false).put("excluded",true).put("reported",true)
            .put("reason","Пользователь сообщил о проблеме").put("reports",it.optInt("reports")+1) }
    }
    fun defer(command: String,result: JSONObject) {
        update(command) { it.put("verified",false).put("reason",probeFailureDetail(result)) }
    }
    fun reject(command: String,result: JSONObject = JSONObject(),rollback: Boolean = false) {
        val a = ProbeAssessment.evaluate(id,result)
        update(command) { it.put("schema",2).put("verified",false).put("excluded",true).put("passed",a.requiredSuccess+a.extraSuccess).put("total",a.requiredTotal+a.extraTotal)
            .put("reason",probeFailureDetail(result)).put("bad",it.optInt("bad")+1).put("rollbacks",it.optInt("rollbacks")+if (rollback) 1 else 0) }
    }
    fun resetExclusions() {
        val rows = entries()
        rows.forEach { if (isExcluded(it)) it.put("excluded",false).put("verified",false) }
        prefs.edit().putString(key,JSONArray(rows).toString()).apply()
    }
}

// Include failed endpoints first; successful rows must not hide the cause.
fun probeFailureDetail(result: JSONObject): String {
    val error = result.optString("error").takeIf { it.isNotBlank() }
    val rows = result.optJSONArray("rows")
    val failed = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        .filter { !it.optBoolean("ok") }.take(4).map {
            "${it.optString("host","узел")}: ${it.optString("detail","нет успешного ответа")}"
        }
    return (listOfNotNull(error)+failed).joinToString("; ").ifBlank { "Полная проверка не пройдена" }.take(1200)
}
