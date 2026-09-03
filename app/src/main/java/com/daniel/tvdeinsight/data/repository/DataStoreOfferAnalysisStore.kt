package com.daniel.tvdeinsight.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daniel.tvdeinsight.data.location.DeviceLocationProvider
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

private val Context.offerHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "offer_history")

@Singleton
class DataStoreOfferAnalysisStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceLocationProvider: DeviceLocationProvider
) : OfferAnalysisStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val historyLoaded = CompletableDeferred<Unit>()
    private val mutableLatestDecision = MutableStateFlow<RuleResult?>(null)
    private val mutableHistory = MutableStateFlow<List<OfferHistoryEntry>>(emptyList())

    override val latestDecision: StateFlow<RuleResult?> = mutableLatestDecision
    override val history: StateFlow<List<OfferHistoryEntry>> = mutableHistory

    /** Leitura usada apenas na migração inicial do histórico antigo para Room. */
    suspend fun readPersistedHistory(): List<OfferHistoryEntry> {
        historyLoaded.await()
        return mutableHistory.value
    }

    init {
        scope.launch {
            context.offerHistoryDataStore.data
                .map { preferences -> decode(preferences[PreferencesKeys.HISTORY]) }
                .collect { entries ->
                    mutableHistory.value = entries
                    if (!historyLoaded.isCompleted) historyLoaded.complete(Unit)
                }
        }
    }

    override fun publish(offer: TripOffer, decision: RuleResult): Long {
        mutableLatestDecision.value = decision
        val recordedAtMillis = System.currentTimeMillis()
        scope.launch {
            val location = deviceLocationProvider.captureRecentLocation()
            writeMutex.withLock {
                historyLoaded.await()
                val entry = OfferHistoryEntry.from(
                    offer = offer,
                    decision = decision,
                    recordedAtMillis = recordedAtMillis,
                    currentLocationAddress = location?.address,
                    currentLocationLatitude = location?.latitude,
                    currentLocationLongitude = location?.longitude
                )
                if (mutableHistory.value.any { saved -> saved.isDuplicateOf(entry) }) {
                    AppLogger.debug("Oferta duplicada ignorada no histórico: plataforma=${entry.platform}")
                    return@withLock
                }
                val updated = (listOf(entry) + mutableHistory.value)
                    .distinctBy { it.id }
                    .sortedByDescending(OfferHistoryEntry::recordedAtMillis)
                    .take(MAX_HISTORY_ENTRIES)
                mutableHistory.value = updated
                context.offerHistoryDataStore.edit { preferences ->
                    preferences[PreferencesKeys.HISTORY] = encode(updated)
                }
            }
        }
        return recordedAtMillis
    }

    override fun attachScreenshot(entryId: Long, fileName: String) {
        scope.launch {
            writeMutex.withLock {
                historyLoaded.await()
                val updated = mutableHistory.value.map { entry ->
                    if (entry.id == entryId) entry.copy(screenshotFileName = fileName) else entry
                }
                if (updated == mutableHistory.value) return@withLock
                mutableHistory.value = updated
                context.offerHistoryDataStore.edit { preferences ->
                    preferences[PreferencesKeys.HISTORY] = encode(updated)
                }
            }
        }
    }

    private fun encode(entries: List<OfferHistoryEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("id", entry.id)
                    .put("recordedAtMillis", entry.recordedAtMillis)
                    .put("platform", entry.platform.name)
                    .put("valorPorKm", entry.valorPorKm)
                    .put("valorPorHora", entry.valorPorHora)
                    .put("valorPorKmBruto", entry.valorPorKmBruto)
                    .put("netTripValue", entry.netTripValue ?: JSONObject.NULL)
                    .put("tollAmount", entry.tollAmount)
                    .put("isVehicleCostPerKmApplied", entry.isVehicleCostPerKmApplied)
                    .put("pickupDistanceKm", entry.pickupDistanceKm ?: JSONObject.NULL)
                    .put("destinationDistanceKm", entry.destinationDistanceKm ?: JSONObject.NULL)
                    .put("tripValue", entry.tripValue)
                    .put("pickupDurationMinutes", entry.pickupDurationMinutes ?: JSONObject.NULL)
                    .put("destinationDurationMinutes", entry.destinationDurationMinutes ?: JSONObject.NULL)
                    .put("currentLocationAddress", entry.currentLocationAddress ?: JSONObject.NULL)
                    .put("currentLocationLatitude", entry.currentLocationLatitude ?: JSONObject.NULL)
                    .put("currentLocationLongitude", entry.currentLocationLongitude ?: JSONObject.NULL)
                    .put("pickupAddress", entry.pickupAddress ?: JSONObject.NULL)
                    .put("destinationAddress", entry.destinationAddress ?: JSONObject.NULL)
                    .put("category", entry.category ?: JSONObject.NULL)
                    .put("decisionType", entry.decisionType.name)
                    .put("activeCriteria", JSONArray(entry.activeCriteria.map { it.name }))
                    .put(
                        "criterionDecisions",
                        JSONObject().apply {
                            entry.criterionDecisions.forEach { (criterion, decision) ->
                                put(criterion.name, decision.name)
                            }
                        }
                    )
                    .put("isStopRejection", entry.isStopRejection)
                    .put("screenshotFileName", entry.screenshotFileName ?: JSONObject.NULL)
            )
        }
    }.toString()

    private fun decode(serializedHistory: String?): List<OfferHistoryEntry> = runCatching {
        if (serializedHistory.isNullOrBlank()) return emptyList()
        val entries = JSONArray(serializedHistory)
        buildList {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val id = item.optLong("id", 0L)
                val timestamp = item.optLong("recordedAtMillis", 0L)
                val price = item.optDouble("tripValue", Double.NaN)
                val perKm = item.optDouble("valorPorKm", Double.NaN)
                val perHour = item.optDouble("valorPorHora", Double.NaN)
                if (id <= 0L || timestamp <= 0L || !price.isFinite() || !perKm.isFinite() || !perHour.isFinite()) {
                    continue
                }
                add(
                    OfferHistoryEntry(
                        id = id,
                        recordedAtMillis = timestamp,
                        platform = OfferPlatform.entries.firstOrNull { it.name == item.optString("platform") }
                            ?: OfferPlatform.UNKNOWN,
                        valorPorKm = perKm,
                        valorPorHora = perHour,
                        valorPorKmBruto = item.optDouble("valorPorKmBruto", perKm)
                            .takeIf { it.isFinite() } ?: perKm,
                        netTripValue = item.optionalDouble("netTripValue"),
                        tollAmount = item.optDouble("tollAmount", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        isVehicleCostPerKmApplied = item.optBoolean("isVehicleCostPerKmApplied", false),
                        pickupDistanceKm = item.optionalDouble("pickupDistanceKm"),
                        destinationDistanceKm = item.optionalDouble("destinationDistanceKm"),
                        tripValue = price,
                        pickupDurationMinutes = item.optionalDouble("pickupDurationMinutes"),
                        destinationDurationMinutes = item.optionalDouble("destinationDurationMinutes"),
                        currentLocationAddress = item.optionalString("currentLocationAddress"),
                        currentLocationLatitude = item.optionalDouble("currentLocationLatitude"),
                        currentLocationLongitude = item.optionalDouble("currentLocationLongitude"),
                        pickupAddress = item.optionalString("pickupAddress"),
                        destinationAddress = item.optionalString("destinationAddress"),
                        category = item.optionalString("category"),
                        decisionType = DecisionType.entries.firstOrNull {
                            it.name == item.optString("decisionType")
                        } ?: DecisionType.ANALISAR,
                        activeCriteria = item.optionalCriteria(),
                        criterionDecisions = item.optionalCriterionDecisions(),
                        isStopRejection = item.optBoolean("isStopRejection", false),
                        screenshotFileName = item.optionalString("screenshotFileName")
                    )
                )
            }
        }.take(MAX_HISTORY_ENTRIES)
    }.getOrDefault(emptyList())

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotEmpty() } else null

    private fun JSONObject.optionalCriteria(): Set<EvaluationCriterion> {
        val values = optJSONArray("activeCriteria") ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) {
                EvaluationCriterion.entries.firstOrNull { it.name == values.optString(index) }?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalCriterionDecisions(): Map<EvaluationCriterion, DecisionType> {
        val values = optJSONObject("criterionDecisions") ?: return emptyMap()
        return buildMap {
            EvaluationCriterion.entries.forEach { criterion ->
                val decision = DecisionType.entries.firstOrNull {
                    it.name == values.optString(criterion.name)
                }
                if (decision != null) put(criterion, decision)
            }
        }
    }

    private fun OfferHistoryEntry.isDuplicateOf(other: OfferHistoryEntry): Boolean =
        abs(recordedAtMillis - other.recordedAtMillis) <= HISTORY_DUPLICATE_WINDOW_MS &&
            platform == other.platform &&
            tripValue.isCloseTo(other.tripValue) &&
            pickupDistanceKm.isCloseTo(other.pickupDistanceKm) &&
            destinationDistanceKm.isCloseTo(other.destinationDistanceKm) &&
            pickupDurationMinutes.isCloseTo(other.pickupDurationMinutes) &&
            destinationDurationMinutes.isCloseTo(other.destinationDurationMinutes) &&
            pickupAddress.normalizedText() == other.pickupAddress.normalizedText() &&
            destinationAddress.normalizedText() == other.destinationAddress.normalizedText() &&
            category.normalizedText() == other.category.normalizedText()

    private fun Double?.isCloseTo(other: Double?): Boolean = when {
        this == null || other == null -> this == other
        else -> abs(this - other) < NUMERIC_DUPLICATE_TOLERANCE
    }

    private fun String?.normalizedText(): String = this?.trim()?.lowercase().orEmpty()

    private object PreferencesKeys {
        val HISTORY = stringPreferencesKey("entries")
    }

    private companion object {
        // Mantemos muitos meses de ofertas sem deixar o DataStore crescer sem limite.
        const val MAX_HISTORY_ENTRIES = 5_000
        const val HISTORY_DUPLICATE_WINDOW_MS = 60 * 60 * 1_000L
        const val NUMERIC_DUPLICATE_TOLERANCE = 0.01
    }
}
