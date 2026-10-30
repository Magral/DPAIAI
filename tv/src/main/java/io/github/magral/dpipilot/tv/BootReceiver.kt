package io.github.magral.dpipilot.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = TvState.prefs(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (boot) prefs.edit().putLong("boot_event_at",System.currentTimeMillis()).apply()
        TvState.update(context,if (boot) "Android сообщил о загрузке системы" else "Android сообщил об обновлении приложения")
        if (!prefs.getBoolean("enabled",false) || (boot && !prefs.getBoolean("boot",true))) {
            TvState.update(context,"Автоматический запуск пропущен: VPN выключен пользователем или выключена автозагрузка")
            return
        }
        MaintenanceJob.schedule(context)
        if (VpnService.prepare(context) != null) {
            TvState.update(context,"Для автозапуска откройте приложение и разрешите VPN")
            return
        }
        runCatching { TvVpnService.start(context,if (boot) "boot" else "update") }.onFailure {
            TvState.update(context,"Android ограничил автозапуск: ${it.javaClass.simpleName}: ${it.message?.take(160)}; проверьте фоновую работу или постоянный VPN")
        }
    }
}
