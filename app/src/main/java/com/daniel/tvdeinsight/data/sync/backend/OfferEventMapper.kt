package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.local.TripEntity
import kotlin.math.roundToLong

object OfferEventMapper {
    private val validCriteria = setOf("RECOLHA", "KM", "HORA", "VIAGEM_LONGA", "VALOR_MINIMO")
    private val validDecisions = setOf("ACEITAR", "ANALISAR", "REJEITAR")

    fun fromTrip(eventId: String, trip: TripEntity): OfferEventPayload? {
        if (trip.platform !in setOf("UBER", "BOLT")) return null
        if (trip.decisionType !in validDecisions) return null
        val activeCriteria = trip.activeCriteria.split(',').map(String::trim)
            .filter(String::isNotEmpty).distinct()
        if (activeCriteria.any { it !in validCriteria }) return null
        val criterionDecisions = trip.criterionDecisions.split('|').mapNotNull { value ->
            val parts = value.split('=', limit = 2)
            if (parts.size != 2 || parts[0] !in validCriteria || parts[1] !in validDecisions) null
            else CriterionDecisionPayload(parts[0], parts[1])
        }.distinctBy { it.criterion }
        return OfferEventPayload(
            eventId = eventId,
            recordedAtEpochMs = trip.recordedAtMillis.takeIf { it >= EARLIEST_EVENT_EPOCH_MS } ?: return null,
            platform = trip.platform,
            category = normalizedText(trip.category, 80),
            decision = trip.decisionType,
            tripValueCents = scaled(trip.tripValue, 100.0, 10_000_000) ?: return null,
            netTripValueCents = trip.netTripValue?.let { scaled(it, 100.0, 10_000_000) ?: return null },
            tollAmountCents = scaled(trip.tollAmount, 100.0, 1_000_000) ?: return null,
            valuePerKmCents = scaled(trip.valorPorKm, 100.0, 1_000_000) ?: return null,
            grossValuePerKmCents = scaled(trip.valorPorKmBruto, 100.0, 1_000_000) ?: return null,
            valuePerHourCents = scaled(trip.valorPorHora, 100.0, 10_000_000) ?: return null,
            pickupDistanceMeters = trip.pickupDistanceKm?.let { scaled(it, 1_000.0, 2_000_000) ?: return null },
            pickupDurationSeconds = trip.pickupDurationMinutes?.let { scaled(it, 60.0, 172_800) ?: return null },
            destinationDistanceMeters = trip.destinationDistanceKm?.let { scaled(it, 1_000.0, 2_000_000) ?: return null },
            destinationDurationSeconds = trip.destinationDurationMinutes?.let { scaled(it, 60.0, 172_800) ?: return null },
            pickupAddress = normalizedText(trip.pickupAddress, 500),
            destinationAddress = normalizedText(trip.destinationAddress, 500),
            currentLocationAddress = normalizedText(trip.currentLocationAddress, 500),
            currentLatitudeMicrodegrees = trip.currentLocationLatitude?.let {
                scaledSigned(it, 1_000_000.0, -90_000_000, 90_000_000) ?: return null
            },
            currentLongitudeMicrodegrees = trip.currentLocationLongitude?.let {
                scaledSigned(it, 1_000_000.0, -180_000_000, 180_000_000) ?: return null
            },
            vehicleCostApplied = trip.isVehicleCostPerKmApplied,
            activeCriteria = activeCriteria,
            criterionDecisions = criterionDecisions,
            stopRejection = trip.isStopRejection
        )
    }

    private fun scaled(value: Double, factor: Double, maximum: Int): Int? {
        if (!value.isFinite() || value < 0.0) return null
        val result = (value * factor).roundToLong()
        return result.takeIf { it in 0..maximum.toLong() }?.toInt()
    }

    private fun scaledSigned(value: Double, factor: Double, minimum: Int, maximum: Int): Int? {
        if (!value.isFinite()) return null
        val result = (value * factor).roundToLong()
        return result.takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()
    }

    private fun normalizedText(value: String?, maximum: Int): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.take(maximum)

    private const val EARLIEST_EVENT_EPOCH_MS = 1_577_836_800_000L
}
