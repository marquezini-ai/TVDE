package com.example.cameraseguranca.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.CameraSafetyDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Executa diariamente a política de retenção dos vídeos privados do aplicativo. */
class RecordingCleanupJobService : JobService() {
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        jobScope.launch {
            val settings = CameraSafetyDependencies.settingsRepository(applicationContext).settings.first()
            RecordingStorage.deleteExpired(this@RecordingCleanupJobService, settings.autoDeleteInterval)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope.cancel()
        // Pede uma nova tentativa caso o Android interrompa a limpeza.
        return true
    }

    override fun onDestroy() {
        jobScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7107
        private const val RUN_EVERY_MILLIS = 24L * 60L * 60L * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, RecordingCleanupJobService::class.java)
            )
                .setPeriodic(RUN_EVERY_MILLIS)
                .build()
            scheduler.schedule(job)
        }
    }
}
