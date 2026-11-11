package com.anjy.atlas

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Sync while Atlas is closed: about every half hour, only when there's a network, at times Android picks to save
 * battery (it batches this with other apps' work). So a reminder set on the PC reaches the phone, and rings, even if
 * Atlas isn't opened in between. Survives restarts. Only while the devices are linked.
 */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!Sync.linked(this)) return false
        val app = applicationContext
        Thread {
            val r = try { Sync.runOnce(app) } catch (e: Exception) { Log.w("AtlasSync", "background sync", e); "failed" }
            // a failed run is retried by Android with its own back-off; "Already syncing" means one is under way
            jobFinished(params, r.startsWith("Couldn't"))
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 7301
        private const val EVERY_MS = 30 * 60_000L

        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(ID) != null) return
                js.schedule(JobInfo.Builder(ID, ComponentName(ctx, SyncJob::class.java))
                    .setPeriodic(EVERY_MS, 10 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build())
            } catch (e: Exception) { Log.w("AtlasSync", "can't schedule background sync", e) }
        }

        fun cancel(ctx: Context) {
            try { ctx.getSystemService(JobScheduler::class.java)?.cancel(ID) } catch (_: Exception) {}
        }
    }
}
