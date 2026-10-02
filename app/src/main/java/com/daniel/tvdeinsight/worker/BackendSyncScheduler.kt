package com.daniel.tvdeinsight.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackendSyncScheduler {
    private const val PERIODIC_WORK = "backend_sync_periodic_v1"
    private const val IMMEDIATE_WORK = "backend_sync_immediate_v1"
    private const val RETRY_AFTER_WORK = "backend_sync_retry_after_v1"

    fun schedule(context: Context) {
        cancelLegacyScheduling(context)
        val request = PeriodicWorkRequestBuilder<BackendSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackendSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueRetryAfter(context: Context, seconds: Long) {
        val request = OneTimeWorkRequestBuilder<BackendSyncWorker>()
            .setInitialDelay(seconds.coerceIn(10, TimeUnit.HOURS.toSeconds(24)), TimeUnit.SECONDS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY_AFTER_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun cancelLegacyScheduling(context: Context) {
        val workManager = WorkManager.getInstance(context)
        LEGACY_WORK_NAMES.forEach(workManager::cancelUniqueWork)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        listOf(
            "com.daniel.tvdeinsight.action.SHEETS_UPLOAD" to 4100,
            "com.daniel.tvdeinsight.action.SHEETS_DOWNLOAD" to 4130
        ).forEach { (action, requestCode) ->
            val intent = Intent(action).setClassName(
                context.packageName,
                "com.daniel.tvdeinsight.worker.SheetsSyncAlarmReceiver"
            )
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private val LEGACY_WORK_NAMES = listOf(
        "sheets_upload_alarm_execution",
        "sheets_download_alarm_execution",
        "sheets_upload_04h",
        "sheets_download_05h",
        "sheets_upload_test_15m",
        "sheets_upload_test_15m_v2",
        "sheets_upload_test_now_v2",
        "sheets_upload_test_now_v3",
        "sheets_upload_hourly",
        "sheets_download_hourly_half"
    )
}
