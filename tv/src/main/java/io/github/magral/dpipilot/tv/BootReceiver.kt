package io.github.magral.dpipilot.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = TvState.prefs(context)
        if (!prefs.getBoolean("enabled",false) || !prefs.getBoolean("boot",true)) return
        MaintenanceJob.schedule(context)
        if (VpnService.prepare(context) != null) {
            TvState.update(context,"Для автозапуска откройте приложение и разрешите VPN")
            return
        }
        runCatching { TvVpnService.start(context) }.onFailure {
            TvState.update(context,"Android ограничил автозапуск: откройте приложение или включите постоянный VPN в настройках ТВ")
        }
    }
}
