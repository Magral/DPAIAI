package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TargetApps {
    const val KEY = "pilot_target_apps"
    data class App(val packageName: String, val label: String)
    private val popular = listOf(
        "com.google.android.youtube", "com.instagram.android",
        "com.instagram.lite", "com.google.android.youtube.tv"
    )
    // Exclude obsolete targets from saved selections and from the generic launcher list.
    private val removedPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
    fun removeLegacyTarget(context: Context) {
        val prefs = context.getPreferences()
        val apps = prefs.getStringSet(KEY,emptySet())!!.toSet()
        prefs.edit().remove("tuned_telegram").putStringSet(KEY,apps-removedPackages).apply()
        context.getSharedPreferences("telegram_bridge",Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun selected(context: Context): Set<String> =
        context.getPreferences().getStringSet(KEY, emptySet())!!.toSet().intersect(popular.toSet()) - context.packageName - removedPackages

    @Suppress("DEPRECATION")
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        return popular.distinct().filter { it != context.packageName && it !in removedPackages }.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) null else App(pkg, pm.getApplicationLabel(info).toString())
            } catch (_: PackageManager.NameNotFoundException) { null }
        }.sortedWith(compareBy<App> { if (it.packageName in popular) 0 else 1 }.thenBy { it.label.lowercase() })
    }
}
