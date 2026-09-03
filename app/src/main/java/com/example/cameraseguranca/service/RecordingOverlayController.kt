package com.example.cameraseguranca.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.daniel.tvdeinsight.reservations.AppPreferences
import com.daniel.tvdeinsight.reservations.FloatingControlService

/**
 * Atualiza a participação de Gravação no único floating da aplicação.
 * Mantemos a fachada para não acoplar a UI de Gravação às Reservas, mas nenhum
 * segundo WindowManager é criado daqui.
 */
object RecordingOverlayController {
    fun sync(context: Context, shouldShow: Boolean) {
        val appContext = context.applicationContext
        AppPreferences.setRecordingOverlayVisible(appContext, shouldShow)

        // Limpa uma instância antiga que pudesse estar visível após uma
        // atualização, antes de entregar a renderização ao serviço unificado.
        appContext.stopService(Intent(appContext, OverlayService::class.java))
        FloatingControlService.sync(appContext)
    }
}
