package com.daniel.tvdeinsight.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.daniel.tvdeinsight.data.sync.SyncGatewayResult
import com.daniel.tvdeinsight.data.sync.backend.BackendRegistrationManager
import com.daniel.tvdeinsight.data.sync.backend.BackendSyncGateway
import com.daniel.tvdeinsight.data.sync.backend.RegistrationOutcome
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackendSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val registrationManager: BackendRegistrationManager,
    private val syncGateway: BackendSyncGateway
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        AppLogger.info("Backend sync iniciado: tentativa=$runAttemptCount")
        when (val registration = registrationManager.registerPending()) {
            is RegistrationOutcome.Active -> Unit
            RegistrationOutcome.ActivationRequired -> return Result.success()
            RegistrationOutcome.KeyRecoveryRequired -> return Result.failure()
            is RegistrationOutcome.PermanentFailure -> {
                AppLogger.warn("Backend registro bloqueado: código=${registration.code}")
                return Result.failure()
            }
            is RegistrationOutcome.RetryLater -> return retry(registration.code, registration.retryAfterSeconds)
        }

        repeat(MAX_SYNC_REQUESTS_PER_RUN) {
            when (val result = syncGateway.synchronize()) {
                is SyncGatewayResult.Completed -> {
                    if (!result.hasMore) {
                        AppLogger.info("Backend sync concluído: pendentes=${result.pendingEvents}")
                        return Result.success()
                    }
                }
                SyncGatewayResult.NotConfigured -> return Result.success()
                is SyncGatewayResult.PermanentFailure -> {
                    AppLogger.warn("Backend sync bloqueado: código=${result.code}")
                    return Result.failure()
                }
                is SyncGatewayResult.RetryLater -> return retry(result.code, result.retryAfterSeconds)
            }
        }
        AppLogger.info("Backend sync continuará em nova execução para drenar pendências")
        return Result.retry()
    }

    private fun retry(code: String, retryAfterSeconds: Long?): Result {
        AppLogger.warn("Backend sync adiado: código=$code")
        if (retryAfterSeconds != null) {
            BackendSyncScheduler.enqueueRetryAfter(applicationContext, retryAfterSeconds)
            return Result.success()
        }
        return Result.retry()
    }

    private companion object {
        const val MAX_SYNC_REQUESTS_PER_RUN = 5
    }
}
