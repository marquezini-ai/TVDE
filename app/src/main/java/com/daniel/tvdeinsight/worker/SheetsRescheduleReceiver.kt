package com.daniel.tvdeinsight.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daniel.tvdeinsight.logging.AppLogger

/** Restaura os alarmes depois de reinício, atualização ou alteração do relógio. */
class SheetsRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.info("Sheets: restaurando alarmes após ${intent.action.orEmpty()}")
        SheetsSyncScheduler.scheduleHourly(context)
    }
}
