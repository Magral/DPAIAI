package io.github.magral.dpipilot.tv

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.*

/** Persisted, inexact system safety net; Android always-on remains the primary supervisor. */
class MaintenanceJob : JobService() {
    companion object {
        private const val ID = 771
        fun schedule(context: Context) {
            val job = JobInfo.Builder(ID,ComponentName(context,MaintenanceJob::class.java))
                .setPersisted(TvState.prefs(context).getBoolean("boot",true)).setPeriodic(15*60*1000L).build()
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job)
        }
        fun scheduled(context: Context): Boolean = runCatching { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs.any { it.id == ID } }.getOrDefault(false)
        fun cancel(context: Context) { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(ID) }
        fun backgroundAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = TvState.prefs(this)
        prefs.edit().putLong("watchdog_at",System.currentTimeMillis()).apply()
        if (!prefs.getBoolean("enabled",false) || VpnService.prepare(this) != null) {
            prefs.edit().putString("watchdog_decision","восстановление отключено или нет разрешения VPN").apply()
            return false
        }
        prefs.edit().putString("watchdog_decision",if (TvState.running) "служба работает, проверен heartbeat" else "служба не работает, проверены фоновые разрешения").apply()
        val now = SystemClock.uptimeMillis()
        val recoveryClock = SystemClock.elapsedRealtime()
        if (TvState.running && RecoveryPolicy.hung(now,prefs.getLong("heartbeat",0)) &&
            RecoveryPolicy.allowRestart(recoveryClock,prefs.getLong("last_process_recovery",0))) {
            prefs.edit().putLong("last_process_recovery",recoveryClock).putString("watchdog_decision","зависший процесс перезапущен").commit()
            // Kill only this application's process. Android restarts its sticky foreground VPN.
            android.os.Process.killProcess(android.os.Process.myPid())
            return false
        }
        if (!TvState.running && backgroundAllowed(this)) {
            runCatching { TvVpnService.start(this,"watchdog"); prefs.edit().putString("watchdog_decision","запрошен фоновый запуск службы").apply() }.onFailure {
                TvState.update(this,"Система ограничила восстановление службы; проверьте разрешения фоновой работы")
            }
        }
        return false
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
