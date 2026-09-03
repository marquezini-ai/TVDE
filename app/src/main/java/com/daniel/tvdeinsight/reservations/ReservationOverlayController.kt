package com.daniel.tvdeinsight.reservations

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Atualiza a participação de Reservas no único floating da aplicação.
 * O serviço só é removido quando Gravação também estiver desligada.
 */
object ReservationOverlayController {
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!enabled) {
            AppPreferences.setOverlayVisible(context, false)
            AppPreferences.setSearching(context, false)
            FloatingControlService.sync(context)
            return true
        }
        if (!Settings.canDrawOverlays(context)) {
            AppPreferences.setOverlayVisible(context, false)
            FloatingControlService.sync(context)
            return false
        }
        AppPreferences.setOverlayVisible(context, true)
        FloatingControlService.sync(context)
        return true
    }

    fun overlayPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
