package com.daniel.tvdeinsight.reservations

import android.content.Context
import com.daniel.tvdeinsight.BuildConfig
import kotlin.math.abs

data class ReservationSettings(
    val categories: Set<String> = emptySet(),
    val minimumPerKm: Double = 0.0,
    val minimumTripValue: Double = 0.0,
    val homeAddress: String = "",
    val maxPickupDistanceKm: Int = 10,
    val maxTripDistanceKm: Double = 100.0,
    val startMinutes: Int = 18 * 60,
    val endMinutes: Int = 5 * 60,
    /** Chave 1=segunda ... 7=domingo. Vazio mantém o formato legado. */
    val weeklyAvailability: Map<Int, DailyAvailability> = emptyMap(),
    /** Dias ativos no filtro de disponibilidade: segunda=1 ... domingo=7. */
    val enabledDays: Set<Int> = (1..7).toSet(),
    val refreshDelayMillis: Long = 200L,
    val searchWaitMillis: Long = 900L,
    val dryRun: Boolean = false,
    val dailyLimitEnabled: Boolean = true,
    val maxDailyReservations: Int = 20,
    val criteriaLocked: Boolean = false,
    val availabilityLocked: Boolean = false,
    val refreshLocked: Boolean = false
)

object AppPreferences {
    private const val SETTINGS = "preferencias_reserva"
    private const val STATE = "estado_automacao"
    private const val KEY_CATEGORIES = "categorias"
    private const val KEY_MIN_PER_KM = "minimo_por_km"
    private const val KEY_MIN_TRIP = "minimo_viagem"
    private const val KEY_HOME_ADDRESS = "morada_casa"
    private const val KEY_MAX_PICKUP_DISTANCE = "distancia_maxima_recolha_km"
    private const val KEY_MAX_TRIP_DISTANCE = "distancia_maxima_viagem_km"
    private const val KEY_START = "hora_inicio"
    private const val KEY_END = "hora_fim"
    private const val KEY_WEEKLY_AVAILABILITY = "disponibilidade_semanal"
    private const val KEY_ENABLED_DAYS = "dias_disponibilidade_ativos"
    private const val KEY_REFRESH_DELAY = "tempo_accepted_scheduled_ms"
    private const val KEY_SEARCH_WAIT = "tempo_busca_ms"
    private const val KEY_DRY_RUN = "modo_teste"
    private const val KEY_DAILY_LIMIT_ENABLED = "limite_reservas_diarias_ativo"
    private const val KEY_MAX_DAILY_RESERVATIONS = "limite_reservas_diarias"
    private const val KEY_CRITERIA_LOCKED = "criterios_bloqueados"
    private const val KEY_AVAILABILITY_LOCKED = "disponibilidade_bloqueada"
    private const val KEY_REFRESH_LOCKED = "ciclo_atualizacao_bloqueado"
    private const val KEY_OVERLAY = "overlay_visivel"
    /**
     * A ativação de gravação usa o mesmo floating de Reservas. Este estado fica
     * aqui (em vez de no DataStore de Gravação) para que os dois controladores
     * consigam decidir de forma síncrona se o único overlay deve permanecer na
     * tela, inclusive durante a restauração do processo.
     */
    private const val KEY_RECORDING_OVERLAY = "overlay_gravacao_visivel"
    private const val KEY_SEARCHING = "busca_ativa"
    private const val KEY_POS_X = "overlay_x"
    private const val KEY_POS_Y = "overlay_y"

    fun loadSettings(context: Context): ReservationSettings {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val legacyStart = prefs.getInt(KEY_START, 18 * 60).coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE)
        val legacyEnd = prefs.getInt(KEY_END, 5 * 60).coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE)
        val weekly = decodeWeeklyAvailability(prefs.getString(KEY_WEEKLY_AVAILABILITY, null))
        val enabledDays = prefs.getStringSet(KEY_ENABLED_DAYS, null)
            ?.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }
            ?.toSet()
            ?: (1..7).toSet()
        val savedTripDistance = when (val raw = prefs.all[KEY_MAX_TRIP_DISTANCE]) {
            is Number -> raw.toDouble()
            is String -> raw.replace(',', '.').toDoubleOrNull() ?: 100.0
            else -> 100.0
        }
        return ReservationSettings(
            categories = prefs.getStringSet(KEY_CATEGORIES, emptySet())?.toSet().orEmpty(),
            minimumPerKm = prefs.getString(KEY_MIN_PER_KM, "0")?.toDoubleOrNull() ?: 0.0,
            minimumTripValue = prefs.getString(KEY_MIN_TRIP, "0")?.toDoubleOrNull() ?: 0.0,
            homeAddress = prefs.getString(KEY_HOME_ADDRESS, "").orEmpty(),
            maxPickupDistanceKm = prefs.getInt(KEY_MAX_PICKUP_DISTANCE, 10).coerceIn(0, 10),
            maxTripDistanceKm = TripDistanceScale.nearest(savedTripDistance),
            startMinutes = legacyStart,
            endMinutes = legacyEnd,
            weeklyAvailability = weekly.ifEmpty { WeeklyAvailability.defaultSchedules(legacyStart, legacyEnd) },
            enabledDays = enabledDays,
            // No Cliente estes valores não são editáveis e permanecem previsíveis.
            refreshDelayMillis = if (BuildConfig.IS_ADMIN_APP) {
                prefs.getLong(KEY_REFRESH_DELAY, 200L).coerceIn(50L, 5000L)
            } else 200L,
            searchWaitMillis = if (BuildConfig.IS_ADMIN_APP) {
                prefs.getLong(KEY_SEARCH_WAIT, 900L).coerceIn(100L, 10000L)
            } else 900L,
            // O modo de simulação foi removido. Valores legados não podem
            // impedir a reserva nem marcar o histórico como simulação.
            dryRun = false,
            dailyLimitEnabled = prefs.getBoolean(KEY_DAILY_LIMIT_ENABLED, true),
            maxDailyReservations = prefs.getInt(KEY_MAX_DAILY_RESERVATIONS, 20).coerceIn(1, 30),
            criteriaLocked = prefs.getBoolean(KEY_CRITERIA_LOCKED, false),
            availabilityLocked = prefs.getBoolean(KEY_AVAILABILITY_LOCKED, false),
            refreshLocked = prefs.getBoolean(KEY_REFRESH_LOCKED, false)
        )
    }

    fun saveSettings(context: Context, settings: ReservationSettings) {
        val legacySchedule = settings.weeklyAvailability[1]
            ?: DailyAvailability(settings.startMinutes, settings.endMinutes)
        val weekly = settings.weeklyAvailability.ifEmpty {
            WeeklyAvailability.defaultSchedules(legacySchedule.startMinutes, legacySchedule.endMinutes)
        }
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_CATEGORIES, settings.categories)
            .putString(KEY_MIN_PER_KM, settings.minimumPerKm.toString())
            .putString(KEY_MIN_TRIP, settings.minimumTripValue.toString())
            .putString(KEY_HOME_ADDRESS, settings.homeAddress.trim())
            .putInt(KEY_MAX_PICKUP_DISTANCE, settings.maxPickupDistanceKm.coerceIn(0, 10))
            .putString(KEY_MAX_TRIP_DISTANCE, TripDistanceScale.nearest(settings.maxTripDistanceKm).toString())
            // Mantém estes dois campos para versões antigas; a app atual usa o mapa semanal.
            .putInt(KEY_START, legacySchedule.startMinutes.coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE))
            .putInt(KEY_END, legacySchedule.endMinutes.coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE))
            .putString(KEY_WEEKLY_AVAILABILITY, encodeWeeklyAvailability(weekly))
            .putStringSet(KEY_ENABLED_DAYS, settings.enabledDays.filter { it in 1..7 }.map(Int::toString).toSet())
            .putLong(KEY_REFRESH_DELAY, settings.refreshDelayMillis.coerceIn(50L, 5000L))
            .putLong(KEY_SEARCH_WAIT, settings.searchWaitMillis.coerceIn(100L, 10000L))
            .putBoolean(KEY_DRY_RUN, false)
            .putBoolean(KEY_DAILY_LIMIT_ENABLED, settings.dailyLimitEnabled)
            .putInt(KEY_MAX_DAILY_RESERVATIONS, settings.maxDailyReservations.coerceIn(1, 30))
            .putBoolean(KEY_CRITERIA_LOCKED, settings.criteriaLocked)
            .putBoolean(KEY_AVAILABILITY_LOCKED, settings.availabilityLocked)
            .putBoolean(KEY_REFRESH_LOCKED, settings.refreshLocked)
            .apply()
        DiagnosticLogger.log("Configurações guardadas: $settings")
    }

    private fun encodeWeeklyAvailability(schedules: Map<Int, DailyAvailability>): String =
        org.json.JSONObject().apply {
            schedules.forEach { (day, schedule) ->
                put(day.toString(), org.json.JSONObject().apply {
                    put("start", schedule.startMinutes.coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE))
                    put("end", schedule.endMinutes.coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE))
                })
            }
        }.toString()

    private fun decodeWeeklyAvailability(raw: String?): Map<Int, DailyAvailability> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                for (day in 1..7) {
                    val item = json.optJSONObject(day.toString()) ?: continue
                    put(
                        day,
                        DailyAvailability(
                            item.optInt("start", 18 * 60).coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE),
                            item.optInt("end", 5 * 60).coerceIn(0, WeeklyAvailability.LAST_SELECTABLE_MINUTE)
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun isOverlayVisible(context: Context) = state(context).getBoolean(KEY_OVERLAY, false)
    fun isRecordingOverlayVisible(context: Context) =
        state(context).getBoolean(KEY_RECORDING_OVERLAY, false)

    fun isAnyOverlayVisible(context: Context) =
        isOverlayVisible(context) || isRecordingOverlayVisible(context)

    fun isSearching(context: Context) = state(context).getBoolean(KEY_SEARCHING, false)

    fun setOverlayVisible(context: Context, visible: Boolean) {
        state(context).edit().putBoolean(KEY_OVERLAY, visible).apply()
        DiagnosticLogger.log("Overlay visível=$visible")
        context.sendBroadcast(AutomationContract.overlayStateIntent(context))
    }

    fun setRecordingOverlayVisible(context: Context, visible: Boolean) {
        state(context).edit().putBoolean(KEY_RECORDING_OVERLAY, visible).apply()
        DiagnosticLogger.log("Overlay de gravação visível=$visible")
        context.sendBroadcast(AutomationContract.overlayStateIntent(context))
    }

    fun setSearching(context: Context, searching: Boolean) {
        state(context).edit().putBoolean(KEY_SEARCHING, searching).apply()
        DiagnosticLogger.log("Busca ativa=$searching")
        context.sendBroadcast(AutomationContract.overlayStateIntent(context))
    }

    fun getOverlayPosition(context: Context): Pair<Int, Int>? {
        val prefs = state(context)
        if (!prefs.contains(KEY_POS_X) || !prefs.contains(KEY_POS_Y)) return null
        return prefs.getInt(KEY_POS_X, 0) to prefs.getInt(KEY_POS_Y, 0)
    }

    fun saveOverlayPosition(context: Context, x: Int, y: Int) {
        state(context).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
        DiagnosticLogger.log("Posição do overlay guardada: x=$x, y=$y")
    }

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
}

/** Escala exata usada no limite de distância das Reservas. */
object TripDistanceScale {
    val values: List<Double> = buildList {
        for (step in 1..12) add(step * 2.5)
        for (distance in 40..150 step 10) add(distance.toDouble())
        for (distance in 200..600 step 50) add(distance.toDouble())
    }

    fun nearest(value: Double): Double = values.minBy { abs(it - value) }

    fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale("pt", "PT"), value)
}

object AutomationContract {
    const val BOLT_PACKAGE = "ee.mtakso.driver"
    const val ACTION_OVERLAY_STATE = "com.daniel.tvdeinsight.action.OVERLAY_STATE"
    const val ACTION_PROBE_SCREEN = "com.daniel.tvdeinsight.action.PROBE_SCREEN"
    const val ACTION_SCREEN_STATE = "com.daniel.tvdeinsight.action.SCREEN_STATE"
    const val ACTION_START_SEARCH = "com.daniel.tvdeinsight.action.START_SEARCH"
    const val ACTION_STOP_SEARCH = "com.daniel.tvdeinsight.action.STOP_SEARCH"
    const val EXTRA_BOLT_VISIBLE = "bolt_visible"
    const val EXTRA_PEDIDOS_VISIBLE = "pedidos_visible"

    fun overlayStateIntent(context: Context) = android.content.Intent(ACTION_OVERLAY_STATE)
        .setPackage(context.packageName)

    fun screenStateIntent(context: Context, boltVisible: Boolean, pedidosVisible: Boolean) =
        android.content.Intent(ACTION_SCREEN_STATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_BOLT_VISIBLE, boltVisible)
            .putExtra(EXTRA_PEDIDOS_VISIBLE, pedidosVisible)

    fun commandIntent(context: Context, action: String) =
        android.content.Intent(action).setPackage(context.packageName)
}

object MoneyParser {
    fun parse(input: String): Double {
        val cleaned = input.replace("€", "", ignoreCase = true)
            .replace("EUR", "", ignoreCase = true)
            .replace(" ", "").trim()
        return when {
            cleaned.contains(',') && cleaned.contains('.') ->
                cleaned.replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0
            cleaned.contains(',') -> cleaned.replace(',', '.').toDoubleOrNull() ?: 0.0
            else -> cleaned.toDoubleOrNull() ?: 0.0
        }
    }

    fun format(value: Double): String = "%.2f".format(java.util.Locale("pt", "PT"), value)
}
