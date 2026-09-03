package com.daniel.tvdeinsight.data.local

import androidx.room.Entity
import androidx.room.Index
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.CategoryNameSanitizer
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform

@Entity(
    tableName = "trip_history",
    primaryKeys = ["sourceDeviceId", "id"],
    indices = [
        Index(value = ["sourceDeviceId", "deduplicationKey"], unique = true),
        Index(value = ["recordedAtMillis"]),
        Index(value = ["platform"])
    ]
)
data class TripEntity(
    val id: Long,
    val recordedAtMillis: Long,
    val platform: String,
    val valorPorKm: Double,
    val valorPorHora: Double,
    val valorPorKmBruto: Double,
    val netTripValue: Double?,
    val tollAmount: Double,
    val isVehicleCostPerKmApplied: Boolean,
    val pickupDistanceKm: Double?,
    val destinationDistanceKm: Double?,
    val tripValue: Double,
    val pickupDurationMinutes: Double?,
    val destinationDurationMinutes: Double?,
    val currentLocationAddress: String?,
    val currentLocationLatitude: Double?,
    val currentLocationLongitude: Double?,
    val pickupAddress: String?,
    val destinationAddress: String?,
    val category: String?,
    val decisionType: String,
    val activeCriteria: String,
    val criterionDecisions: String,
    val isStopRejection: Boolean,
    val screenshotFileName: String?,
    val sourceDeviceId: String,
    val deduplicationKey: String
)

data class HourlyTripSummary(
    val hour: Int,
    val tripCount: Int,
    val averageTripValue: Double?,
    val averagePerKm: Double?,
    val averagePerHour: Double?
)

data class PickupAddressSummary(
    val address: String,
    val tripCount: Int,
    val averageTripValue: Double?,
    val averagePerKm: Double?,
    val averagePerHour: Double?
)

object TripEntityMapper {
    fun fromDomain(entry: OfferHistoryEntry): TripEntity = TripEntity(
        id = entry.id,
        recordedAtMillis = entry.recordedAtMillis,
        platform = entry.platform.name,
        valorPorKm = entry.valorPorKm,
        valorPorHora = entry.valorPorHora,
        valorPorKmBruto = entry.valorPorKmBruto,
        netTripValue = entry.netTripValue,
        tollAmount = entry.tollAmount,
        isVehicleCostPerKmApplied = entry.isVehicleCostPerKmApplied,
        pickupDistanceKm = entry.pickupDistanceKm,
        destinationDistanceKm = entry.destinationDistanceKm,
        tripValue = entry.tripValue,
        pickupDurationMinutes = entry.pickupDurationMinutes,
        destinationDurationMinutes = entry.destinationDurationMinutes,
        currentLocationAddress = entry.currentLocationAddress,
        currentLocationLatitude = entry.currentLocationLatitude,
        currentLocationLongitude = entry.currentLocationLongitude,
        pickupAddress = entry.pickupAddress,
        destinationAddress = entry.destinationAddress,
        category = entry.category,
        decisionType = entry.decisionType.name,
        activeCriteria = entry.activeCriteria.joinToString(",") { it.name },
        criterionDecisions = entry.criterionDecisions.entries.joinToString("|") { (criterion, decision) ->
            "${criterion.name}=${decision.name}"
        },
        isStopRejection = entry.isStopRejection,
        screenshotFileName = entry.screenshotFileName,
        sourceDeviceId = entry.sourceDeviceId,
        deduplicationKey = entry.deduplicationKey()
    )

    fun toDomain(entity: TripEntity): OfferHistoryEntry {
        val platform = OfferPlatform.entries.firstOrNull { it.name == entity.platform }
            ?: OfferPlatform.UNKNOWN
        return OfferHistoryEntry(
        id = entity.id,
        recordedAtMillis = entity.recordedAtMillis,
        platform = platform,
        valorPorKm = entity.valorPorKm,
        valorPorHora = entity.valorPorHora,
        valorPorKmBruto = entity.valorPorKmBruto,
        netTripValue = entity.netTripValue,
        tollAmount = entity.tollAmount,
        isVehicleCostPerKmApplied = entity.isVehicleCostPerKmApplied,
        pickupDistanceKm = entity.pickupDistanceKm,
        destinationDistanceKm = entity.destinationDistanceKm,
        tripValue = entity.tripValue,
        pickupDurationMinutes = entity.pickupDurationMinutes,
        destinationDurationMinutes = entity.destinationDurationMinutes,
        currentLocationAddress = entity.currentLocationAddress,
        currentLocationLatitude = entity.currentLocationLatitude,
        currentLocationLongitude = entity.currentLocationLongitude,
        pickupAddress = entity.pickupAddress,
        destinationAddress = entity.destinationAddress,
        category = CategoryNameSanitizer.cleanForPlatform(entity.category, platform),
        decisionType = DecisionType.entries.firstOrNull { it.name == entity.decisionType } ?: DecisionType.ANALISAR,
        activeCriteria = entity.activeCriteria.splitToSequence(',')
            .mapNotNull { value -> EvaluationCriterion.entries.firstOrNull { it.name == value } }
            .toSet(),
        criterionDecisions = entity.criterionDecisions.splitToSequence('|')
            .mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val criterion = EvaluationCriterion.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                val decision = DecisionType.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
                criterion to decision
        }.toMap(),
        isStopRejection = entity.isStopRejection,
        screenshotFileName = entity.screenshotFileName,
        sourceDeviceId = entity.sourceDeviceId
        )
    }

    private fun OfferHistoryEntry.deduplicationKey(): String = listOf(
        platform.name,
        recordedAtMillis / 3_600_000L,
        tripValue.roundKey(),
        pickupDistanceKm.roundKey(),
        destinationDistanceKm.roundKey(),
        pickupDurationMinutes.roundKey(),
        destinationDurationMinutes.roundKey(),
        pickupAddress.normalizedKey(),
        destinationAddress.normalizedKey(),
        category.normalizedKey()
    ).joinToString("|")

    private fun Double?.roundKey(): String = this?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty()
    private fun String?.normalizedKey(): String = orEmpty().trim().lowercase().replace("\\s+".toRegex(), " ")
}
