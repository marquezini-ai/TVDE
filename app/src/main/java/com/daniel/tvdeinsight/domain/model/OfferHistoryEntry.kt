package com.daniel.tvdeinsight.domain.model

/** Dados de uma oferta já avaliada, apresentados no histórico. */
data class OfferHistoryEntry(
    val id: Long,
    val recordedAtMillis: Long,
    val platform: OfferPlatform,
    val valorPorKm: Double,
    val valorPorHora: Double,
    val valorPorKmBruto: Double = valorPorKm,
    val netTripValue: Double? = null,
    val tollAmount: Double = 0.0,
    val isVehicleCostPerKmApplied: Boolean = false,
    val pickupDistanceKm: Double?,
    val destinationDistanceKm: Double?,
    val tripValue: Double,
    val pickupDurationMinutes: Double? = null,
    val destinationDurationMinutes: Double? = null,
    /** Posição do motorista no momento em que a oferta foi registada. */
    val currentLocationAddress: String? = null,
    val currentLocationLatitude: Double? = null,
    val currentLocationLongitude: Double? = null,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    val category: String? = null,
    val decisionType: DecisionType = DecisionType.ANALISAR,
    val activeCriteria: Set<EvaluationCriterion> = emptySet(),
    /** Resultado de cada critério no instante em que a oferta foi avaliada. */
    val criterionDecisions: Map<EvaluationCriterion, DecisionType> = emptyMap(),
    val isStopRejection: Boolean = false,
    /** Nome do ficheiro privado da captura da oferta; nunca é enviado para a Sheet. */
    val screenshotFileName: String? = null,
    /** Identificador do dispositivo que originou a linha no Google Sheets. */
    val sourceDeviceId: String = ""
) {
    companion object {
        fun from(
            offer: TripOffer,
            decision: RuleResult,
            recordedAtMillis: Long = System.currentTimeMillis(),
            currentLocationAddress: String? = null,
            currentLocationLatitude: Double? = null,
            currentLocationLongitude: Double? = null
        ): OfferHistoryEntry = OfferHistoryEntry(
            id = recordedAtMillis,
            recordedAtMillis = recordedAtMillis,
            platform = offer.platform,
            valorPorKm = decision.valorPorKm,
            valorPorHora = decision.valorPorHora,
            valorPorKmBruto = decision.valorPorKmBruto,
            netTripValue = decision.netTripValue,
            tollAmount = decision.tollAmount,
            isVehicleCostPerKmApplied = decision.isVehicleCostPerKmApplied,
            pickupDistanceKm = offer.pickupDistanceKm,
            destinationDistanceKm = offer.tripDistanceKm,
            tripValue = offer.price,
            pickupDurationMinutes = offer.pickupDurationMinutes,
            destinationDurationMinutes = offer.tripDurationMinutes,
            currentLocationAddress = currentLocationAddress,
            currentLocationLatitude = currentLocationLatitude,
            currentLocationLongitude = currentLocationLongitude,
            pickupAddress = offer.pickupAddress,
            destinationAddress = offer.destinationAddress,
            category = CategoryNameSanitizer.cleanForPlatform(offer.category, offer.platform),
            decisionType = decision.type,
            activeCriteria = decision.activeCriteria,
            criterionDecisions = decision.criterionDecisions,
            isStopRejection = decision.isStopRejection
        )
    }
}
