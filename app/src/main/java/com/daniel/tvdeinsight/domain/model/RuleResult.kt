package com.daniel.tvdeinsight.domain.model

enum class DecisionType { ACEITAR, REJEITAR, ANALISAR }

data class RuleResult(
    val type: DecisionType,
    val valorPorKm: Double,
    val valorPorHora: Double,
    /** Valor por km antes do custo operacional do veículo. */
    val valorPorKmBruto: Double = valorPorKm,
    val activeCriteria: Set<EvaluationCriterion>,
    val criterionDecisions: Map<EvaluationCriterion, DecisionType>,
    val isVehicleCostPerKmApplied: Boolean = false,
    /** Valor da viagem depois do custo do veículo, nulo quando esse custo não está ativo. */
    val netTripValue: Double? = null,
    /** Portagem mostrada no cartão Bolt e descontada do valor líquido. */
    val tollAmount: Double = 0.0,
    val isStopRejection: Boolean = false,
    val platform: OfferPlatform = OfferPlatform.UNKNOWN,
    val pickupDistanceKm: Double? = null
)
