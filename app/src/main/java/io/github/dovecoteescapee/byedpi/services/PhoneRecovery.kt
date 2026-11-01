package io.github.dovecoteescapee.byedpi.services

import android.app.job.*
import android.content.*
import android.net.VpnService
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.*

object PhoneRecovery {
    fun desired(context: Context) = context.getPreferences().getBoolean("mobile_enabled",false) && context.getPreferences().getBoolean("mobile_recovery",true)
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.allPendingJobs.none { it.id == 782 }) scheduler.schedule(JobInfo.Builder(782,ComponentName(context,PhoneRecoveryJob::class.java)).setPersisted(true).setPeriodic(15*60*1000L).build())
    }
    fun recover(context: Context) {
        if (!desired(context) || appStatus.first == AppStatus.Running) return
        if (VpnService.prepare(context) != null) { MobileStrategyPolicy.state = "Для восстановления откройте приложение и разрешите VPN"; return }
        val prefs = context.getPreferences(); val now = System.currentTimeMillis(); val previous = prefs.getLong("mobile_recovery_attempt",0)
        if (previous > 0 && now >= previous && now-previous < 30*60*1000) return
        prefs.edit().putLong("mobile_recovery_attempt",now).apply()
        runCatching { ServiceManager.start(context,Mode.VPN) }.onFailure { MobileStrategyPolicy.state = "Android ограничил восстановление: ${it.javaClass.simpleName}; откройте приложение" }
    }
}
class PhoneRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) { PhoneRecovery.schedule(context); PhoneRecovery.recover(context) }
    }
}
class PhoneRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean { PhoneRecovery.recover(this); return false }
    override fun onStopJob(params: JobParameters) = false
}
