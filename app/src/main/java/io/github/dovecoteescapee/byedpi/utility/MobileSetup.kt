package io.github.dovecoteescapee.byedpi.utility

import android.content.Context

/** The phone product always serves the two supported clients; an empty allowlist is forbidden. */
object MobileSetup {
    fun prepare(context: Context) {
        val prefs = context.getPreferences()
        val selected = TargetApps.installed(context).map { it.packageName }.filter { it in ServiceTargets.combined.packages }.toSet()
        check(selected.isNotEmpty()) { "Установите YouTube или Instagram" }
        var plan = MobilePlan.saved(prefs)
        for (id in listOf("instagram","youtube")) MobileHistory.backups(context,id).firstOrNull()?.let { plan = MobilePlan.replace(plan,id,it) }
        prefs.edit().putStringSet(TargetApps.KEY,selected).apply()
        MobileStrategyPolicy.apply(prefs,MobilePlan.BOTH,plan)
    }
}
