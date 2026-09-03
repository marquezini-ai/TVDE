package com.daniel.tvdeinsight.reservations

import android.content.Context

object AutomationStateStore {
    private const val PREFERENCES = "diagnostico_automacao"
    private const val PHASE = "fase"
    private const val DETAIL = "detalhe"
    private const val LAST_ERROR = "ultimo_erro"
    private const val LAST_UPDATED = "ultima_atualizacao"
    private const val SCANS = "leituras"
    private const val ACCEPTED = "aceites"
    private const val REJECTED = "recusadas"
    private const val FAILED = "falhas"
    private const val CONSECUTIVE_FAILURES = "falhas_consecutivas"
    private const val DAY = "dia_contadores"
    private val lock = Any()
    private var lastBoltVisible: Boolean? = null
    private var lastPedidosVisible: Boolean? = null
    private var lastLogAt = 0L

    fun publishScreen(context: Context, boltVisible: Boolean, pedidosVisible: Boolean) {
        val now = System.currentTimeMillis()
        val shouldLog = synchronized(lock) {
            val changed = lastBoltVisible != boltVisible || lastPedidosVisible != pedidosVisible
            val periodic = now - lastLogAt >= 1000L
            lastBoltVisible = boltVisible
            lastPedidosVisible = pedidosVisible
            if (changed || periodic) lastLogAt = now
            changed || periodic
        }
        if (shouldLog) DiagnosticLogger.log("Tela: bolt=$boltVisible, pedidos=$pedidosVisible")
        context.sendBroadcast(
            AutomationContract.screenStateIntent(context, boltVisible, pedidosVisible)
        )
    }

    fun transition(context: Context, phase: AutomationPhase, detail: String = "", error: String = "") {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(PHASE, phase.name)
            .putString(DETAIL, detail)
            .putString(LAST_ERROR, error)
            .putLong(LAST_UPDATED, System.currentTimeMillis())
            .apply()
        if (error.isNotBlank()) {
            increment(context, FAILED)
        }
        DiagnosticLogger.log("Estado automação: ${phase.name}; detalhe=$detail${error.takeIf { it.isNotBlank() }?.let { "; erro=$it" }.orEmpty()}")
    }

    fun recordScan(context: Context) = increment(context, SCANS)

    fun recordRejected(context: Context) = increment(context, REJECTED)

    fun recordAccepted(context: Context) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        resetCountersIfNewDay(prefs)
        prefs.edit()
            .putInt(ACCEPTED, prefs.getInt(ACCEPTED, 0) + 1)
            .putInt(CONSECUTIVE_FAILURES, 0)
            .apply()
    }

    fun acceptedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        resetCountersIfNewDay(prefs)
        return prefs.getInt(ACCEPTED, 0)
    }

    fun snapshot(context: Context): AutomationDiagnostics {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        resetCountersIfNewDay(prefs)
        val phase = runCatching {
            AutomationPhase.valueOf(prefs.getString(PHASE, AutomationPhase.STOPPED.name).orEmpty())
        }.getOrDefault(AutomationPhase.STOPPED)
        return AutomationDiagnostics(
            phase = phase,
            detail = prefs.getString(DETAIL, "").orEmpty(),
            lastError = prefs.getString(LAST_ERROR, "").orEmpty(),
            updatedAt = prefs.getLong(LAST_UPDATED, 0L),
            scans = prefs.getInt(SCANS, 0),
            acceptedToday = prefs.getInt(ACCEPTED, 0),
            rejected = prefs.getInt(REJECTED, 0),
            failures = prefs.getInt(FAILED, 0)
        )
    }

    private fun increment(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (key == ACCEPTED) resetCountersIfNewDay(prefs)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun resetCountersIfNewDay(prefs: android.content.SharedPreferences) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
            .format(java.util.Date())
        if (prefs.getString(DAY, null) == today) return
        prefs.edit()
            .putString(DAY, today)
            .putInt(ACCEPTED, 0)
            .apply()
    }
}

data class AutomationDiagnostics(
    val phase: AutomationPhase,
    val detail: String,
    val lastError: String,
    val updatedAt: Long,
    val scans: Int,
    val acceptedToday: Int,
    val rejected: Int,
    val failures: Int
)
