package com.daniel.tvdeinsight.reservations

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PresentedRide(
    val date: String,
    val time: String,
    val category: String,
    val payout: Double,
    val distanceKm: Double,
    val origin: String,
    val destination: String,
    val recordedAt: Long,
    val id: String,
    val accepted: Boolean = false,
    val refusalReason: String = "critérios não avaliados",
    val pickupDistanceKm: Double? = null,
    val categoryPassed: Boolean = false,
    val tripValuePassed: Boolean = false,
    val perKmPassed: Boolean = false,
    val tripDistancePassed: Boolean = false,
    val availabilityPassed: Boolean = false,
    val pickupDistancePassed: Boolean? = null,
    val simulated: Boolean = false
)

/** Histórico local das viagens vistas, independente de serem elegíveis ou reservadas. */
/** Compatibilidade de leitura com o JSON usado antes da migração SQLite. */
internal object LegacyRideHistoryStore {
    private const val PREFERENCES = "historico_viagens"
    private const val ENTRIES = "entradas"
    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private var cache: MutableList<PresentedRide>? = null

    fun record(
        context: Context,
        candidate: RideCandidate,
        evaluation: RideEvaluation,
        pickupDistanceKm: Double? = null,
        simulated: Boolean = false
    ): Boolean {
        val route = completeRoute(candidate.origin, candidate.destination)
        val entry = PresentedRide(
            date = candidate.tripDate.ifBlank { TripDateResolver.resolve(candidate.sourceText) },
            time = candidate.timeText.ifBlank { formatTime(candidate.startMinutes) },
            category = candidate.displayedCategory.ifBlank { candidate.category },
            payout = candidate.payout,
            distanceKm = candidate.distanceKm,
            origin = route.first,
            destination = route.second,
            recordedAt = System.currentTimeMillis(),
            id = candidate.historyId,
            accepted = evaluation.accepted,
            refusalReason = evaluation.reasons.joinToString("; ").ifBlank { "aceita todos os critérios" },
            pickupDistanceKm = pickupDistanceKm,
            categoryPassed = evaluation.categoryPassed,
            tripValuePassed = evaluation.tripValuePassed,
            perKmPassed = evaluation.perKmPassed,
            tripDistancePassed = evaluation.tripDistancePassed,
            availabilityPassed = evaluation.availabilityPassed,
            pickupDistancePassed = evaluation.pickupDistancePassed,
            simulated = simulated
        )
        val added = synchronized(lock) {
            val current = entriesLocked(context)
            val existingIndex = current.indexOfFirst { it.id == entry.id }
            if (existingIndex >= 0) {
                // O cartão pode carregar as moradas depois dos restantes campos.
                // Atualiza a mesma entrada sem criar um novo ID.
                val previous = current[existingIndex]
                val previousRoute = completeRoute(previous.origin, previous.destination)
                val merged = previous.copy(
                    origin = previousRoute.first.ifBlank { entry.origin },
                    destination = previousRoute.second.ifBlank { entry.destination },
                    accepted = entry.accepted,
                    refusalReason = entry.refusalReason,
                    pickupDistanceKm = entry.pickupDistanceKm ?: previous.pickupDistanceKm,
                    categoryPassed = entry.categoryPassed,
                    tripValuePassed = entry.tripValuePassed,
                    perKmPassed = entry.perKmPassed,
                    tripDistancePassed = entry.tripDistancePassed,
                    availabilityPassed = entry.availabilityPassed,
                    pickupDistancePassed = entry.pickupDistancePassed ?: previous.pickupDistancePassed
                    ,simulated = previous.simulated || entry.simulated
                )
                if (merged != previous) {
                    current[existingIndex] = merged
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putString(ENTRIES, toJson(current).toString()).apply()
                }
                return@synchronized false
            }
            val updated = (listOf(entry) + current).take(MAX_ENTRIES)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(ENTRIES, toJson(updated).toString()).apply()
            cache = updated.toMutableList()
            true
        }
        if (added) {
            DiagnosticLogger.log(
                "Histórico: viagem apresentada: data=${entry.date}, hora=${entry.time}, " +
                "id=${entry.id}, categoria=${entry.category}, valor=${MoneyParser.format(entry.payout)}, " +
                    "origem=${entry.origin}, destino=${entry.destination}"
            )
        }
        return added
    }

    fun list(context: Context): List<PresentedRide> = synchronized(lock) { entriesLocked(context).toList() }

    fun updateOutcome(context: Context, historyId: String, accepted: Boolean, reason: String) {
        synchronized(lock) {
            val current = entriesLocked(context)
            val index = current.indexOfFirst { it.id == historyId }
            if (index < 0) return
            val previous = current[index]
            val updated = previous.copy(accepted = accepted, refusalReason = reason)
            if (updated == previous) return
            current[index] = updated
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(ENTRIES, toJson(current).toString()).apply()
            cache = current
        }
        DiagnosticLogger.log(
            "Histórico atualizado: id=$historyId, estado=${if (accepted) "ACEITE" else "RECUSADA"}, motivo=$reason"
        )
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(ENTRIES).apply()
            cache = mutableListOf()
        }
        DiagnosticLogger.log("Histórico de viagens limpo pelo utilizador")
    }

    private fun entriesLocked(context: Context): MutableList<PresentedRide> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENTRIES, null) ?: return mutableListOf<PresentedRide>().also { cache = it }
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return mutableListOf<PresentedRide>().also { cache = it }
        val result = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val storedRoute = completeRoute(item.optString("origin"), item.optString("destination"))
                val refusalReason = item.optString("refusalReason", "critérios não avaliados")
                // Versões anteriores guardavam apenas o motivo final da recusa.
                // Reconstrói as cores individuais para que esses cartões antigos
                // não apareçam todos vermelhos depois da atualização.
                val hasCriterionResults = item.has("categoryPassed") ||
                    item.has("tripValuePassed") ||
                    item.has("perKmPassed") ||
                    item.has("tripDistancePassed") ||
                    item.has("availabilityPassed")
                val hasPickupResult = item.has("pickupDistancePassed")
                val pickupDistanceKm = if (item.has("pickupDistanceKm") && !item.isNull("pickupDistanceKm")) {
                    item.optDouble("pickupDistanceKm")
                } else {
                    null
                }
                add(
                    PresentedRide(
                        date = item.optString("date"),
                        time = item.optString("time"),
                        category = item.optString("category"),
                        payout = item.optDouble("payout", 0.0),
                        distanceKm = item.optDouble("distanceKm", 0.0),
                        origin = storedRoute.first,
                        destination = storedRoute.second,
                        recordedAt = item.optLong("recordedAt", 0L),
                        id = item.optString("id", item.optString("key")),
                        accepted = item.optBoolean("accepted", false),
                        refusalReason = refusalReason,
                        pickupDistanceKm = pickupDistanceKm,
                        categoryPassed = if (hasCriterionResults) item.optBoolean("categoryPassed", false)
                            else !refusalReason.contains("categoria não selecionada", ignoreCase = true),
                        tripValuePassed = if (hasCriterionResults) item.optBoolean("tripValuePassed", false)
                            else !refusalReason.contains("valor total abaixo do mínimo", ignoreCase = true),
                        perKmPassed = if (hasCriterionResults) item.optBoolean("perKmPassed", false)
                            else !refusalReason.contains("valor/km abaixo do mínimo", ignoreCase = true),
                        tripDistancePassed = if (hasCriterionResults) item.optBoolean("tripDistancePassed", true)
                            else !refusalReason.contains("distância da viagem", ignoreCase = true),
                        availabilityPassed = if (hasCriterionResults) item.optBoolean("availabilityPassed", false)
                            else !refusalReason.contains("hora fora da disponibilidade", ignoreCase = true),
                        pickupDistancePassed = if (hasPickupResult && !item.isNull("pickupDistancePassed")) {
                            item.optBoolean("pickupDistancePassed")
                        } else if (pickupDistanceKm != null) {
                            !refusalReason.contains("distância de recolha", ignoreCase = true)
                        } else {
                            null
                        },
                        simulated = item.optBoolean("simulated", false)
                    )
                )
            }
        }.sortedByDescending { it.recordedAt }.toMutableList()
        cache = result
        return result
    }

    private fun toJson(entries: List<PresentedRide>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("date", entry.date)
                put("time", entry.time)
                put("category", entry.category)
                put("payout", entry.payout)
                put("distanceKm", entry.distanceKm)
                put("origin", entry.origin)
                put("destination", entry.destination)
                put("recordedAt", entry.recordedAt)
                put("id", entry.id)
                put("accepted", entry.accepted)
                put("refusalReason", entry.refusalReason)
                if (entry.pickupDistanceKm != null) put("pickupDistanceKm", entry.pickupDistanceKm)
                put("categoryPassed", entry.categoryPassed)
                put("tripValuePassed", entry.tripValuePassed)
                put("perKmPassed", entry.perKmPassed)
                put("tripDistancePassed", entry.tripDistancePassed)
                put("availabilityPassed", entry.availabilityPassed)
                if (entry.pickupDistancePassed != null) put("pickupDistancePassed", entry.pickupDistancePassed)
                put("simulated", entry.simulated)
            })
        }
    }

    private fun formatTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun completeRoute(origin: String, destination: String): Pair<String, String> =
        if (origin.isBlank() || destination.isBlank()) "" to ""
        else origin.trim() to destination.trim()
}

