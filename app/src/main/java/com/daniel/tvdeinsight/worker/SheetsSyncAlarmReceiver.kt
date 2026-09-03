package com.daniel.tvdeinsight.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daniel.tvdeinsight.logging.AppLogger

/** Recebe o alarme, agenda o ciclo seguinte e entrega a sincronização ao WorkManager. */
class SheetsSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action != SheetsSyncScheduler.ACTION_UPLOAD && action != SheetsSyncScheduler.ACTION_DOWNLOAD) return
        AppLogger.info("Sheets: alarme recebido, ação=$action")
        SheetsSyncScheduler.scheduleNextAlarm(context, action)
        SheetsSyncScheduler.enqueueFromAlarm(context, action)
    }
}
