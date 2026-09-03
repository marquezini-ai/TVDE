package com.daniel.tvdeinsight.reservations

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daniel.tvdeinsight.R
import java.util.Locale

object ReservationNotifications {
    private const val RESERVATION_NOTIFICATION_ID = 4002
    private const val RESERVATION_CHANNEL = "reservas"

    fun createChannels(context: Context) {
        DiagnosticLogger.log("A criar/verificar canais de notificação")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RESERVATION_CHANNEL,
                context.getString(R.string.canal_reservas),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    fun reservation(context: Context, ride: RideCandidate) {
        DiagnosticLogger.log("Notificação de viagem reservada: ${ride.sourceText}")
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val details = String.format(
            Locale("pt", "PT"),
            "%s às %02d:%02d · %.2f km · %.2f €",
            ride.category,
            ride.startMinutes / 60,
            ride.startMinutes % 60,
            ride.distanceKm,
            ride.payout
        )
        NotificationManagerCompat.from(context).notify(
            RESERVATION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, RESERVATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Viagem Reservada")
                .setContentText(details)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }
}
