package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import org.json.JSONObject

object MobileHistory {
    fun store(context: Context,id: String,profile: String = MobileStrategyPolicy.profile(context)) =
        MobileStrategyStore(context.getPreferences(),id,profile)
    fun entries(context: Context,id: String): List<JSONObject> = store(context,id).entries()
    fun backups(context: Context,id: String): List<String> = store(context,id).choices().map { it.getString("command") }
    fun summary(context: Context) = ServiceTargets.individual.joinToString("\n") { target ->
        val store = store(context,target.id)
        "${target.name}: рабочих ${store.choices().size}; исключено ${store.entries().count { it.optBoolean("excluded") }}; "+
            store.choices().take(8).joinToString(" | ") { "${it.optLong("latencyMs")} мс; ${it.optInt("passed")}/${it.optInt("total")}" }
    }
}
