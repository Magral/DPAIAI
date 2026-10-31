package io.github.magral.dpipilot.tv

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.SystemClock

object AutonomyAudit {
    fun report(context: Context): String {
        val prefs = TvState.prefs(context)
        val now = System.currentTimeMillis()
        val bootStarted = now-SystemClock.elapsedRealtime()
        val receipt = prefs.getLong("boot_event_at",0)
        val started = prefs.getLong("service_start_at",0)
        val enabled = prefs.getBoolean("enabled",false)
        val bootEnabled = prefs.getBoolean("boot",true)
        val receiverEnabled = runCatching {
            context.packageManager.getReceiverInfo(ComponentName(context,BootReceiver::class.java),0).enabled &&
                context.packageManager.getComponentEnabledSetting(ComponentName(context,BootReceiver::class.java)) in listOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }.getOrDefault(false)
        val beat = prefs.getLong("heartbeat",0)
        val awake = SystemClock.uptimeMillis()
        val heartbeatFresh = TvState.running && beat in 1..awake && awake-beat <= 90000L
        return listOf(
            "Автономность: проверка состояния, без имитации перезагрузки",
            "${if (enabled) "OK" else "ВНИМАНИЕ"}: авторежим ${if (enabled) "включён пользователем" else "вручную выключен"}",
            "${if (bootEnabled && receiverEnabled) "OK" else "ВНИМАНИЕ"}: автозагрузка ${if (bootEnabled) "включена" else "выключена"}; компонент приёмника ${if (receiverEnabled) "включён" else "недоступен"}",
            "${if (VpnService.prepare(context) == null) "OK" else "ТРЕБУЕТСЯ ДЕЙСТВИЕ"}: системное разрешение VPN",
            "${if (MaintenanceJob.backgroundAllowed(context)) "OK" else "ТРЕБУЕТСЯ ДЕЙСТВИЕ"}: фоновое восстановление",
            "${if (MaintenanceJob.scheduled(context)) "OK" else "ВНИМАНИЕ"}: резервная задача ${if (MaintenanceJob.scheduled(context)) "назначена" else "не найдена"}",
            "${if (heartbeatFresh) "OK" else "НЕ ПОДТВЕРЖДЕНО"}: служба отвечает сейчас; VPN-интерфейс ${if (TvState.vpnEstablished) "создан службой" else "не создан"}",
            when {
                receipt >= bootStarted-60000 && receipt <= now -> "ПОДТВЕРЖДЕНО: BOOT_COMPLETED получен в текущем сеансе системы"
                receipt == 0L -> "НЕ ПРОВЕРЕНО: после установки событие загрузки ещё не получено"
                else -> "НЕ ПОДТВЕРЖДЕНО: записи о загрузке текущего сеанса нет; возможны ограничения прошивки"
            },
            if (started >= bootStarted-60000 && prefs.getString("service_start_reason","") == "загрузка системы")
                "ПОДТВЕРЖДЕНО: служба запущена после загрузки системы"
            else "Запуск именно после загрузки не подтверждён; текущая причина: ${prefs.getString("service_start_reason","нет")}",
            "Резервная проверка запускалась: ${if (prefs.getLong("watchdog_at",0) >= bootStarted-60000) "в текущем сеансе" else "в текущем сеансе не подтверждено"}; последнее решение: ${prefs.getString("watchdog_decision","нет записей")}",
            "Постоянный VPN Android: ${TvState.alwaysOn}. Его доступность и реальный холодный запуск проверяются на приставке."
        ).joinToString("\n")
    }
}
