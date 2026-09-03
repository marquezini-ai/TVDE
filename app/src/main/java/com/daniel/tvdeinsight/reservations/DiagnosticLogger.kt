package com.daniel.tvdeinsight.reservations

import com.daniel.tvdeinsight.logging.AppLogger

/** Mantém toda a telemetria das reservas no log exportável do TVDE Insight. */
internal object DiagnosticLogger {
    fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) AppLogger.info("Reservas Bolt | $message")
        else AppLogger.warn("Reservas Bolt | $message", throwable)
    }
}
