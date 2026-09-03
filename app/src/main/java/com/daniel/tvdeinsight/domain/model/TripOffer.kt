package com.daniel.tvdeinsight.domain.model

data class TripOffer(
    val price: Double,
    val distanceKm: Double,
    val durationMinutes: Double,
    val additionalInfo: String = "",
    val pickupDistanceKm: Double? = null,
    val pickupDurationMinutes: Double? = null,
    val tripDistanceKm: Double? = null,
    val tripDurationMinutes: Double? = null,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    val category: String? = null,
    /** Portagem visível no cartão da Bolt; é um custo a descontar apenas do valor líquido. */
    val tollAmount: Double = 0.0,
    val hasStops: Boolean = false,
    val platform: OfferPlatform = OfferPlatform.UNKNOWN
)
