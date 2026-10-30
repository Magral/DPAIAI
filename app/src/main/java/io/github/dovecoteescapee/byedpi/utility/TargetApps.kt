package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TargetApps {
    const val KEY = "pilot_target_apps"
    data class App(val packageName: String, val label: String)
    private val popular = listOf(
        "com.google.android.youtube", "com.instagram.android", "org.telegram.messenger",
        "org.telegram.messenger.web", "com.instagram.lite", "com.google.android.youtube.tv"
    )
    fun selected(context: Context): Set<String> =
        context.getPreferences().getStringSet(KEY, emptySet())!!.toSet() - context.packageName

    @Suppress("DEPRECATION")
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val discovered = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
        return (popular + discovered).distinct().filter { it != context.packageName }.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) null else App(pkg, pm.getApplicationLabel(info).toString())
            } catch (_: PackageManager.NameNotFoundException) { null }
        }.sortedWith(compareBy<App> { if (it.packageName in popular) 0 else 1 }.thenBy { it.label.lowercase() })
    }
}
