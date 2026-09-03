package com.daniel.tvdeinsight.domain.usecase

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.TripOffer
import javax.inject.Inject

class EvaluateOfferUseCase @Inject constructor() {

    operator fun invoke(offer: TripOffer, settings: RuleSettings): RuleResult {
        val normalizedSettings = settings.normalizedThresholds()
        val vehicleCostApplied = normalizedSettings.hasVehicleCostPerKm
        val tollAmount = if (offer.platform == OfferPlatform.BOLT) {
            offer.tollAmount.coerceAtLeast(0.0)
        } else {
            0.0
        }
        val valueAfterToll = (offer.price - tollAmount).coerceAtLeast(0.0)
        // Na Bolt, a portagem não pertence ao rendimento da viagem. É descontada
        // antes de dividir pelo total de quilómetros e de aplicar o custo do veículo.
        val valorPorKmBruto = if (offer.distanceKm > 0.0) valueAfterToll / offer.distanceKm else 0.0
        val valorPorKm = if (vehicleCostApplied) {
            (valorPorKmBruto - normalizedSettings.vehicleCostPerKm).coerceAtLeast(0.0)
        } else {
            valorPorKmBruto
        }
        val netTripValue = if (vehicleCostApplied || tollAmount > 0.0) {
            offer.price - (normalizedSettings.vehicleCostPerKm * offer.distanceKm) - tollAmount
        } else {
            null
        }
        val valorPorHora = if (offer.durationMinutes > 0.0) {
            (offer.price / offer.durationMinutes) * 60.0
        } else {
            0.0
        }
        val activeCriteria = normalizedSettings.activeCriteria
        // Calcula os três indicadores para que o card mostre a cor real de cada valor,
        // mesmo quando esse indicador não é um foco ativo da decisão.
        val criterionDecisions = EvaluationCriterion.entries.associateWith { criterion ->
            when (criterion) {
                EvaluationCriterion.RECOLHA -> pickupDistanceDecision(offer.pickupDistanceKm, normalizedSettings)
                    ?: DecisionType.ANALISAR
                EvaluationCriterion.KM -> financialDecision(
                    value = valorPorKm,
                    good = normalizedSettings.goodEurPerKm,
                    minimum = normalizedSettings.minEurPerKm
                )
                EvaluationCriterion.HORA -> financialDecision(
                    value = valorPorHora,
                    good = normalizedSettings.goodEurPerHour,
                    minimum = normalizedSettings.minEurPerHour
                )
                EvaluationCriterion.VIAGEM_LONGA -> longTripDecision(
                    tripDistanceKm = offer.tripDistanceKm,
                    settings = normalizedSettings
                )
                EvaluationCriterion.VALOR_MINIMO -> minimumTripValueDecision(
                    tripValue = offer.price,
                    settings = normalizedSettings
                )
            }
        }

        if (offer.hasStops && normalizedSettings.rejectTripsWithStops) {
            return RuleResult(
                type = DecisionType.REJEITAR,
                valorPorKm = valorPorKm,
                valorPorHora = valorPorHora,
                valorPorKmBruto = valorPorKmBruto,
                activeCriteria = activeCriteria,
                criterionDecisions = criterionDecisions,
                isVehicleCostPerKmApplied = vehicleCostApplied,
                netTripValue = netTripValue,
                tollAmount = tollAmount,
                isStopRejection = true,
                platform = offer.platform,
                pickupDistanceKm = offer.pickupDistanceKm
            )
        }

        return RuleResult(
            type = if (activeCriteria.isEmpty()) {
                DecisionType.ANALISAR
            } else {
                combineCriteria(activeCriteria.map { criterionDecisions.getValue(it) })
            },
            valorPorKm = valorPorKm,
            valorPorHora = valorPorHora,
            valorPorKmBruto = valorPorKmBruto,
            activeCriteria = activeCriteria,
            criterionDecisions = criterionDecisions,
            isVehicleCostPerKmApplied = vehicleCostApplied,
            netTripValue = netTripValue,
            tollAmount = tollAmount,
            isStopRejection = false,
            platform = offer.platform,
            pickupDistanceKm = offer.pickupDistanceKm
        )
    }

    private fun financialDecision(value: Double, good: Double, minimum: Double): DecisionType {
        val lowerLimit = minOf(minimum, good)
        val upperLimit = maxOf(minimum, good)
        return when {
        value >= upperLimit -> DecisionType.ACEITAR
        value < lowerLimit -> DecisionType.REJEITAR
        else -> DecisionType.ANALISAR
        }
    }

    private fun combineCriteria(decisions: Collection<DecisionType>): DecisionType = when {
        decisions.any { it == DecisionType.REJEITAR } -> DecisionType.REJEITAR
        decisions.all { it == DecisionType.ACEITAR } -> DecisionType.ACEITAR
        else -> DecisionType.ANALISAR
    }

    private fun pickupDistanceDecision(
        pickupDistanceKm: Double?,
        settings: RuleSettings
    ): DecisionType? {
        val pickup = pickupDistanceKm ?: return null
        val ideal = settings.idealPickupDistanceKm.coerceAtLeast(0.0)
        val acceptable = settings.acceptablePickupDistanceKm.coerceAtLeast(ideal)

        return when {
            pickup > acceptable -> DecisionType.REJEITAR
            pickup > ideal -> DecisionType.ANALISAR
            else -> DecisionType.ACEITAR
        }
    }

    private fun longTripDecision(tripDistanceKm: Double?, settings: RuleSettings): DecisionType =
        if (tripDistanceKm != null && tripDistanceKm <= settings.longTripMinimumKm) {
            DecisionType.ACEITAR
        } else {
            DecisionType.REJEITAR
        }

    /** Critério binário: abaixo do valor mínimo rejeita; no limite ou acima aceita. */
    private fun minimumTripValueDecision(tripValue: Double, settings: RuleSettings): DecisionType =
        if (tripValue >= settings.minimumTripValue) DecisionType.ACEITAR else DecisionType.REJEITAR
}
