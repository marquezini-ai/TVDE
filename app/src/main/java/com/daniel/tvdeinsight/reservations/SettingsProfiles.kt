package com.daniel.tvdeinsight.reservations

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Perfis locais para alternar rapidamente entre turnos e regras diferentes. */
object SettingsProfiles {
    private const val PREFERENCES = "perfis_reserva"
    private const val PROFILES = "perfis"

    fun names(context: Context): List<String> = synchronized(this) {
        val root = read(context)
        root.keys().asSequence().toList().sorted()
    }

    fun save(context: Context, name: String, settings: ReservationSettings): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return false
        val root = read(context)
        root.put(cleanName, encode(settings))
        write(context, root)
        DiagnosticLogger.log("Perfil guardado: $cleanName")
        return true
    }

    fun load(context: Context, name: String): ReservationSettings? = synchronized(this) {
        val item = read(context).optJSONObject(name.trim()) ?: return@synchronized null
        decode(item)
    }

    fun delete(context: Context, name: String) {
        val root = read(context)
        root.remove(name.trim())
        write(context, root)
        DiagnosticLogger.log("Perfil eliminado: ${name.trim()}")
    }

    private fun encode(settings: ReservationSettings): JSONObject = JSONObject().apply {
        put("categories", JSONArray(settings.categories.toList()))
        put("minimumPerKm", settings.minimumPerKm)
        put("minimumTripValue", settings.minimumTripValue)
        put("homeAddress", settings.homeAddress)
        put("maxPickupDistanceKm", settings.maxPickupDistanceKm)
        put("maxTripDistanceKm", settings.maxTripDistanceKm)
        put("startMinutes", settings.startMinutes)
        put("endMinutes", settings.endMinutes)
        put("refreshDelayMillis", settings.refreshDelayMillis)
        put("searchWaitMillis", settings.searchWaitMillis)
        put("dryRun", false)
        put("maxDailyReservations", settings.maxDailyReservations)
        put("criteriaLocked", settings.criteriaLocked)
        put("availabilityLocked", settings.availabilityLocked)
        put("refreshLocked", settings.refreshLocked)
        put("weeklyAvailability", JSONObject().apply {
            settings.weeklyAvailability.forEach { (day, schedule) ->
                put(day.toString(), JSONObject().apply {
                    put("start", schedule.startMinutes)
                    put("end", schedule.endMinutes)
                })
            }
        })
        put("enabledDays", JSONArray(settings.enabledDays.sorted()))
    }

    private fun decode(item: JSONObject): ReservationSettings {
        val start = item.optInt("startMinutes", 18 * 60)
        val end = item.optInt("endMinutes", 5 * 60)
        val weekly = item.optJSONObject("weeklyAvailability")?.let { json ->
            buildMap {
                for (day in 1..7) {
                    json.optJSONObject(day.toString())?.let { schedule ->
                        put(day, DailyAvailability(schedule.optInt("start", start), schedule.optInt("end", end)))
                    }
                }
            }
        }.orEmpty()
        val categories = item.optJSONArray("categories")?.let { array ->
            buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
        }.orEmpty()
        return ReservationSettings(
            categories = categories,
            minimumPerKm = item.optDouble("minimumPerKm", 0.0),
            minimumTripValue = item.optDouble("minimumTripValue", 0.0),
            homeAddress = item.optString("homeAddress", ""),
            maxPickupDistanceKm = item.optInt("maxPickupDistanceKm", 10).coerceIn(0, 10),
            maxTripDistanceKm = TripDistanceScale.nearest(item.optDouble("maxTripDistanceKm", 100.0)),
            startMinutes = start,
            endMinutes = end,
            weeklyAvailability = weekly.ifEmpty { WeeklyAvailability.defaultSchedules(start, end) },
            enabledDays = item.optJSONArray("enabledDays")?.let { days -> buildSet { for (index in 0 until days.length()) days.optInt(index).takeIf { it in 1..7 }?.let(::add) } } ?: (1..7).toSet(),
            refreshDelayMillis = item.optLong("refreshDelayMillis", 250L).coerceIn(50L, 5000L),
            searchWaitMillis = item.optLong("searchWaitMillis", 1000L).coerceIn(100L, 10000L),
            dryRun = false,
            maxDailyReservations = item.optInt("maxDailyReservations", 20).coerceIn(1, 100),
            criteriaLocked = item.optBoolean("criteriaLocked", false),
            availabilityLocked = item.optBoolean("availabilityLocked", false),
            refreshLocked = item.optBoolean("refreshLocked", false)
        )
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PROFILES, "{}").orEmpty())
    }.getOrDefault(JSONObject())

    private fun write(context: Context, root: JSONObject) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(PROFILES, root.toString()).apply()
    }
}
