package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import org.json.JSONArray
import org.json.JSONObject

object MobileHistory {
    private fun key(context: Context,id: String) = "mobile_history.$id.${MobileStrategyPolicy.profile(context)}"
    fun entries(context: Context,id: String): List<JSONObject> = runCatching {
        val rows = JSONArray(context.getPreferences().getString(key(context,id),"[]"))
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { CatalogProtocol.validCommand(it.optString("command")) }
    }.getOrDefault(emptyList())
    fun backups(context: Context,id: String): List<String> = entries(context,id).filter { it.optInt("good") > 0 }
        .sortedByDescending { score(it) }.map { it.getString("command") }.distinct().take(5)
    private fun score(row: JSONObject): Long = row.optInt("good")*100L+row.optLong("usefulMillis")/60000-row.optInt("bad")*150L-row.optInt("rollbacks")*300L
    fun record(context: Context,id: String,command: String,good: Boolean,usefulMillis: Long = 0,rollback: Boolean = false) {
        require(CatalogProtocol.validCommand(command))
        val rows = entries(context,id).toMutableList()
        val row = rows.firstOrNull { it.optString("command") == command } ?: JSONObject().put("command",command).also { rows.add(it) }
        row.put(if (good) "good" else "bad",row.optInt(if (good) "good" else "bad")+1)
            .put("usefulMillis",row.optLong("usefulMillis")+usefulMillis.coerceAtLeast(0)).put("at",System.currentTimeMillis())
        if (rollback) row.put("rollbacks",row.optInt("rollbacks")+1)
        val retained = rows.sortedWith(compareByDescending<JSONObject> { score(it) }.thenByDescending { it.optLong("at") }).take(8)
        context.getPreferences().edit().putString(key(context,id),JSONArray(retained).toString()).apply()
    }
    fun summary(context: Context) = ServiceTargets.individual.joinToString("\n") { target ->
        "${target.name}: резервов ${backups(context,target.id).size}; "+entries(context,target.id).joinToString(" | ") {
            "${CatalogProtocol.id(it.optString("command"))}: успехов ${it.optInt("good")}, сбоев ${it.optInt("bad")}, откатов ${it.optInt("rollbacks")}, полезный обмен ${it.optLong("usefulMillis")/60000} мин"
        }
    }
}
