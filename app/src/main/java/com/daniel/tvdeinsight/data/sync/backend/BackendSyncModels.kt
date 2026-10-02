package com.daniel.tvdeinsight.data.sync.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CriterionDecisionPayload(val criterion: String, val decision: String)

@Serializable
data class OfferEventPayload(
    @SerialName("event_id") val eventId: String,
    @SerialName("recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    val platform: String,
    val category: String? = null,
    val decision: String,
    @SerialName("trip_value_cents") val tripValueCents: Int,
    @SerialName("net_trip_value_cents") val netTripValueCents: Int? = null,
    @SerialName("toll_amount_cents") val tollAmountCents: Int = 0,
    @SerialName("value_per_km_cents") val valuePerKmCents: Int,
    @SerialName("gross_value_per_km_cents") val grossValuePerKmCents: Int,
    @SerialName("value_per_hour_cents") val valuePerHourCents: Int,
    @SerialName("pickup_distance_meters") val pickupDistanceMeters: Int? = null,
    @SerialName("pickup_duration_seconds") val pickupDurationSeconds: Int? = null,
    @SerialName("destination_distance_meters") val destinationDistanceMeters: Int? = null,
    @SerialName("destination_duration_seconds") val destinationDurationSeconds: Int? = null,
    @SerialName("pickup_address") val pickupAddress: String? = null,
    @SerialName("pickup_municipality") val pickupMunicipality: String? = null,
    @SerialName("destination_address") val destinationAddress: String? = null,
    @SerialName("current_location_address") val currentLocationAddress: String? = null,
    @SerialName("current_latitude_microdegrees") val currentLatitudeMicrodegrees: Int? = null,
    @SerialName("current_longitude_microdegrees") val currentLongitudeMicrodegrees: Int? = null,
    @SerialName("vehicle_cost_applied") val vehicleCostApplied: Boolean = false,
    @SerialName("active_criteria") val activeCriteria: List<String> = emptyList(),
    @SerialName("criterion_decisions") val criterionDecisions: List<CriterionDecisionPayload> = emptyList(),
    @SerialName("stop_rejection") val stopRejection: Boolean = false
)

@Serializable
data class AggregateQueryPayload(
    val platforms: List<String>,
    val metric: String,
    @SerialName("value_mode") val valueMode: String,
    val shift: String,
    @SerialName("card_color") val cardColor: String,
    val category: CategoryFilterPayload? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String
)

@Serializable
data class CategoryFilterPayload(val platform: String, val name: String)

@Serializable
data class BackendSyncRequest(
    val cursor: String? = null,
    val events: List<OfferEventPayload>,
    @SerialName("aggregate_query") val aggregateQuery: AggregateQueryPayload
)

@Serializable
data class EventResultPayload(
    @SerialName("event_id") val eventId: String,
    val status: String,
    val code: String? = null
)

@Serializable
data class OwnChangePayload(
    val sequence: Long,
    val operation: String,
    val event: OfferEventPayload
)

@Serializable
data class PickupMunicipalityAggregatePayload(
    val municipality: String,
    @SerialName("median_cents") val medianCents: Int,
    @SerialName("event_count") val eventCount: Int
)

@Serializable
data class HeatmapAggregatePayload(
    @SerialName("day_of_week") val dayOfWeek: String,
    val shift: String,
    @SerialName("median_cents") val medianCents: Int? = null,
    @SerialName("event_count") val eventCount: Int
)

@Serializable
data class DailyAggregatePayload(
    val date: String,
    @SerialName("average_cents") val averageCents: Int? = null,
    @SerialName("event_count") val eventCount: Int
)

@Serializable
data class GlobalAggregatesPayload(
    val query: AggregateQueryPayload,
    @SerialName("pickup_municipalities") val pickupMunicipalities: List<PickupMunicipalityAggregatePayload>,
    val heatmap: List<HeatmapAggregatePayload>,
    @SerialName("daily_calendar") val dailyCalendar: List<DailyAggregatePayload>,
    @SerialName("recorded_dates") val recordedDates: List<String>,
    @SerialName("generated_at") val generatedAt: Long
)

@Serializable
data class BackendSyncResponse(
    @SerialName("event_results") val eventResults: List<EventResultPayload>,
    @SerialName("own_changes") val ownChanges: List<OwnChangePayload>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("global_aggregates") val globalAggregates: GlobalAggregatesPayload,
    @SerialName("aggregate_version") val aggregateVersion: Int,
    @SerialName("policy_version") val policyVersion: Int,
    @SerialName("server_time") val serverTime: Long
)
