package com.daniel.tvdeinsight

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.daniel.tvdeinsight.worker.SheetsSyncScheduler
import com.example.cameraseguranca.CameraSafetyDependencies

@HiltAndroidApp
class TvdeInsightApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
        AppLogger.info(
            "Aplicação iniciada: versão=${BuildConfig.VERSION_NAME}, " +
                "código=${BuildConfig.VERSION_CODE}, admin=${BuildConfig.IS_ADMIN_APP}"
        )
        AppLogger.info("Sheets configurado no build: ${com.daniel.tvdeinsight.BuildConfig.GOOGLE_SHEETS_SPREADSHEET_ID.isNotBlank() && com.daniel.tvdeinsight.BuildConfig.GOOGLE_SERVICE_ACCOUNT_ASSET.isNotBlank()}")
        SheetsSyncScheduler.scheduleHourly(this)
        CameraSafetyDependencies.initialize(this)
    }
}
