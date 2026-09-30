package com.daniel.tvdeinsight.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.daniel.tvdeinsight.data.repository.SettingsRepository
import com.daniel.tvdeinsight.data.screenshot.OfferScreenshotStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class OfferScreenshotCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val settings: SettingsRepository,
    private val screenshots: OfferScreenshotStore
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        screenshots.deleteOlderThan(settings.settings.first().screenshotRetentionHours)
        return Result.success()
    }
}
