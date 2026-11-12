package io.github.dovecoteescapee.byedpi.utility

import android.content.Context

/** The phone product always serves the two supported clients; an empty allowlist is forbidden. */
object MobileSetup {
    fun prepare(context: Context) {
        val prefs = context.getPreferences()
        if(!prefs.contains("mobile_dns_mode") && !prefs.getBoolean("mobile_network_dns",true)) {
            val dns=prefs.getString("dns_ip","1.1.1.1").orEmpty()
            if(DnsResolver.valid(dns) && dns.isNotEmpty()) {
                val edit=prefs.edit().putString("mobile_dns_mode","manual").putString("mobile_dns_manual",dns)
                listOf("instagram","youtube").forEach { edit.putString(MobileDns.key(context,it),dns) };edit.apply()
            }
        }
        val selected = TargetApps.installed(context).map { it.packageName }.filter { it in ServiceTargets.combined.packages }.toSet()
        check(selected.isNotEmpty()) { "Установите YouTube или Instagram" }
        var plan = MobilePlan.saved(prefs)
        for (id in listOf("instagram","youtube")) {
            val good = prefs.getString("mobile_good.$id.${MobileStrategyPolicy.profile(context)}",null)
            val current = MobilePlan.command(plan,id)
            val preserved = current.takeIf { !MobileHistory.store(context,id).excluded(it) && prefs.getString("tuned_$id",null) == it }
            val preferred = MobileHistory.store(context,id).choices().firstOrNull { io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol.id(it.optString("command")) == good }?.optString("command")
                ?: MobileHistory.backups(context,id).firstOrNull()
            val fallback = preserved ?: preferred ?: if (MobileHistory.store(context,id).excluded(MobilePlan.command(plan,id))) MobileStrategyPolicy.window(context,ServiceTargets.target(id)).firstOrNull() else null
            fallback?.let { plan = MobilePlan.replace(plan,id,it) }
        }
        prefs.edit().putStringSet(TargetApps.KEY,selected).apply()
        MobileStrategyPolicy.apply(prefs,MobilePlan.BOTH,plan)
    }
}
