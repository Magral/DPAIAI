package io.github.dovecoteescapee.byedpi.services

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import io.github.dovecoteescapee.byedpi.catalog.MobileCatalogUpdater
import kotlinx.coroutines.*

class CatalogUpdateJob : JobService() {
    private var work: Job? = null
    companion object {
        fun schedule(context: Context) {
            val job = JobInfo.Builder(780,ComponentName(context,CatalogUpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(24*60*60*1000L).build()
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.none { it.id == 780 }) scheduler.schedule(job)
        }
    }
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { MobileCatalogUpdater.refresh(this@CatalogUpdateJob) }
            finally {
                if (isActive) {
                    val prefs = MobileCatalogUpdater.preferences(this@CatalogUpdateJob)
                    if (prefs.getLong("catalog_checked",0) < prefs.getLong("catalog_attempt",0)) {
                        val retry = JobInfo.Builder(781,ComponentName(this@CatalogUpdateJob,CatalogUpdateJob::class.java))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(60*60*1000L).setPersisted(true).build()
                        (getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(retry)
                    }
                    jobFinished(params,false)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
}
