package com.daniel.tvdeinsight.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.daniel.tvdeinsight.logging.AppLogger
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * AlarmManager define os instantes nominais; WorkManager executa a operação
 * quando existir Internet e preserva-a mesmo que o processo seja encerrado.
 */
object SheetsSyncScheduler {
    const val ACTION_UPLOAD = "com.daniel.tvdeinsight.action.SHEETS_UPLOAD"
    const val ACTION_DOWNLOAD = "com.daniel.tvdeinsight.action.SHEETS_DOWNLOAD"

    private const val UPLOAD_EXECUTION_WORK = "sheets_upload_alarm_execution"
    private const val DOWNLOAD_EXECUTION_WORK = "sheets_download_alarm_execution"
    private const val UPLOAD_MINUTE = 0
    private const val DOWNLOAD_MINUTE = 30
    private const val UPLOAD_REQUEST_CODE = 4100
    private const val DOWNLOAD_REQUEST_CODE = 4130

    fun scheduleHourly(context: Context) {
        cancelLegacyWork(context)
        scheduleNextAlarm(context, ACTION_UPLOAD)
        scheduleNextAlarm(context, ACTION_DOWNLOAD)
    }

    fun scheduleNextAlarm(context: Context, action: String) {
        val minute = when (action) {
            ACTION_UPLOAD -> UPLOAD_MINUTE
            ACTION_DOWNLOAD -> DOWNLOAD_MINUTE
            else -> return
        }
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val candidate = now.withMinute(minute).withSecond(0).withNano(0)
        val nextRun = if (candidate.isAfter(now)) candidate else candidate.plusHours(1)
        val triggerAtMillis = nextRun.toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLogger.error("AlarmManager indisponível; não foi possível agendar $action")
            return
        }

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        val pendingIntent = alarmPendingIntent(context, action)
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
        AppLogger.info(
            "Sheets alarme agendado: tipo=${actionLabel(action)}, próxima=${nextRun.toLocalDateTime()}, " +
                "modo=${if (exactAllowed) "EXATO" else "INEXATO_SEM_PERMISSÃO"}"
        )
    }

    fun enqueueFromAlarm(context: Context, action: String) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = when (action) {
            ACTION_UPLOAD -> OneTimeWorkRequestBuilder<DailySheetsUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            ACTION_DOWNLOAD -> OneTimeWorkRequestBuilder<DailySheetsDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            else -> return
        }
        val uniqueName = if (action == ACTION_UPLOAD) UPLOAD_EXECUTION_WORK else DOWNLOAD_EXECUTION_WORK
        AppLogger.info(
            "Sheets alarme disparado: tipo=${actionLabel(action)}, id=${request.id}, rede=CONNECTED"
        )
        val operation = workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        observeEnqueue(workManager, uniqueName, operation.result)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun alarmPendingIntent(context: Context, action: String): PendingIntent {
        val requestCode = if (action == ACTION_UPLOAD) UPLOAD_REQUEST_CODE else DOWNLOAD_REQUEST_CODE
        val intent = Intent(context, SheetsSyncAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun <T> observeEnqueue(
        workManager: WorkManager,
        uniqueName: String,
        result: com.google.common.util.concurrent.ListenableFuture<T>
    ) {
        result.addListener(
            {
                runCatching { result.get() }
                    .onSuccess {
                        AppLogger.info("WorkManager aceitou execução: nome=$uniqueName")
                        logUniqueWorkState(workManager, uniqueName)
                    }
                    .onFailure { error ->
                        AppLogger.error("WorkManager rejeitou execução: nome=$uniqueName", error)
                    }
            },
            DIRECT_EXECUTOR
        )
    }

    private fun logUniqueWorkState(workManager: WorkManager, uniqueName: String) {
        val stateResult = workManager.getWorkInfosForUniqueWork(uniqueName)
        stateResult.addListener(
            {
                runCatching { stateResult.get() }
                    .onSuccess { workInfos ->
                        val states = workInfos.joinToString(separator = "; ") { workInfo ->
                            "id=${workInfo.id}, estado=${workInfo.state}, tentativa=${workInfo.runAttemptCount}"
                        }.ifBlank { "nenhum registo" }
                        AppLogger.info("WorkManager execução: nome=$uniqueName, $states")
                    }
                    .onFailure { error ->
                        AppLogger.error("WorkManager execução indisponível: nome=$uniqueName", error)
                    }
            },
            DIRECT_EXECUTOR
        )
    }

    private fun cancelLegacyWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        LEGACY_WORK_NAMES.forEach { name -> workManager.cancelUniqueWork(name) }
        AppLogger.info("Sheets: agendamentos WorkManager antigos cancelados")
    }

    private fun actionLabel(action: String): String =
        if (action == ACTION_UPLOAD) "UPLOAD" else "DOWNLOAD"

    private val LEGACY_WORK_NAMES = listOf(
        "sheets_upload_04h",
        "sheets_download_05h",
        "sheets_upload_test_15m",
        "sheets_upload_test_15m_v2",
        "sheets_upload_test_now_v2",
        "sheets_upload_test_now_v3",
        "sheets_upload_hourly",
        "sheets_download_hourly_half"
    )
    private val DIRECT_EXECUTOR = Executor { command -> command.run() }
}
