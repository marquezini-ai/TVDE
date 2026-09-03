package com.example.cameraseguranca

import android.content.Context
import com.example.cameraseguranca.data.SettingsRepository
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.service.RecordingCleanupJobService
import com.example.cameraseguranca.service.RecordingOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Dependências do módulo de Gravação, hospedadas pela aplicação TVDE Insight. */
object CameraSafetyDependencies {
    @Volatile private var repository: SettingsRepository? = null

    fun settingsRepository(context: Context): SettingsRepository =
        repository ?: synchronized(this) {
            repository ?: SettingsRepository(context.applicationContext).also { repository = it }
        }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val settingsRepository = settingsRepository(appContext)
        RecordingCleanupJobService.schedule(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settings = settingsRepository.settings.first()
            RecordingStorage.deleteExpired(appContext, settings.autoDeleteInterval)
            RecordingOverlayController.sync(appContext, settings.floatingControlEnabled)
        }
    }
}
