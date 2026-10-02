package com.daniel.tvdeinsight

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.data.network.NetworkStatusMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.daniel.tvdeinsight.worker.BackendSyncScheduler
import com.example.cameraseguranca.CameraSafetyDependencies
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.service.RecordingService

@HiltAndroidApp
class TvdeInsightApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var networkStatusMonitor: NetworkStatusMonitor

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
        AppLogger.info("Observador de rede iniciado: internetValidada=${networkStatusMonitor.hasValidatedInternet.value}")
        val incompleteRecordings = RecordingStorage.deleteOrphanedPartialFiles(this)
        if (incompleteRecordings > 0) {
            AppLogger.info("Limpeza de gravação incompleta concluída: $incompleteRecordings ficheiro(s).")
        }
        if (RecordingService.clearStateAfterProcessStart(this)) {
            AppLogger.warn("Gravação anterior terminou de forma inesperada; indicador circular limpo no arranque.")
        }
        AppLogger.info(
            "Aplicação iniciada: versão=${BuildConfig.VERSION_NAME}, " +
                "código=${BuildConfig.VERSION_CODE}, admin=${BuildConfig.IS_ADMIN_APP}"
        )
        BackendSyncScheduler.schedule(this)
        BackendSyncScheduler.enqueueImmediate(this)
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "offer-screenshot-retention", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.daniel.tvdeinsight.worker.OfferScreenshotCleanupWorker>(
                1, java.util.concurrent.TimeUnit.HOURS).build())
        CameraSafetyDependencies.initialize(this)
    }
}
