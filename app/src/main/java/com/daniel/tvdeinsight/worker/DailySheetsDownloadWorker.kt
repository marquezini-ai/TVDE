package com.daniel.tvdeinsight.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.daniel.tvdeinsight.data.repository.TripSyncRepository
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DailySheetsDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: TripSyncRepository
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        AppLogger.info("Worker Sheets download iniciado: tentativa=${runAttemptCount}")
        return try {
            val completed = syncRepository.downloadAll()
            if (!completed) {
                AppLogger.warn("Worker Sheets download adiado: configuração ainda indisponível")
                return Result.retry()
            }
            AppLogger.info("Worker Sheets download horário concluído com sucesso")
            Result.success()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppLogger.error("Download diário do Sheets falhou", error)
            Result.retry()
        }
    }
}
