package com.daniel.tvdeinsight.domain.usecase

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.TripOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluateOfferUseCaseTest {
    private val useCase = EvaluateOfferUseCase()
    private val settings = RuleSettings(
        isPickupCriterionEnabled = true,
        isHourCriterionEnabled = true,
        goodEurPerHour = 20.0,
        minEurPerHour = 15.0,
        idealPickupDistanceKm = 2.5,
        acceptablePickupDistanceKm = 4.0
    )

    @Test
    fun `pickup within ideal distance keeps an accepted offer accepted`() {
        assertEquals(DecisionType.ACEITAR, evaluateAtPickupDistance(2.0).type)
    }

    @Test
    fun `pickup between ideal and acceptable distance requires review`() {
        assertEquals(DecisionType.ANALISAR, evaluateAtPickupDistance(3.0).type)
    }

    @Test
    fun `pickup above acceptable distance rejects the offer`() {
        assertEquals(DecisionType.REJEITAR, evaluateAtPickupDistance(4.5).type)
    }

    @Test
    fun `disabled pickup criterion does not affect an hour-only evaluation`() {
        val decision = useCase(
            offer = TripOffer(
                price = 12.0,
                distanceKm = 6.0,
                durationMinutes = 24.0,
                pickupDistanceKm = 8.0
            ),
            settings = settings.copy(isPickupCriterionEnabled = false)
        )

        assertEquals(DecisionType.ACEITAR, decision.type)
        assertEquals(setOf(EvaluationCriterion.HORA), decision.activeCriteria)
        assertEquals(DecisionType.ACEITAR, decision.criterionDecisions[EvaluationCriterion.HORA])
    }

    @Test
    fun `all evaluation criteria can be disabled without silently enabling hour`() {
        val decision = useCase(
            offer = TripOffer(price = 10.0, distanceKm = 5.0, durationMinutes = 20.0),
            settings = settings.copy(
                isPickupCriterionEnabled = false,
                isKmCriterionEnabled = false,
                isHourCriterionEnabled = false,
                isLongTripCriterionEnabled = false,
                rejectTripsWithStops = false
            )
        )

        assertEquals(emptySet<EvaluationCriterion>(), decision.activeCriteria)
        assertEquals(DecisionType.ANALISAR, decision.type)
    }

    @Test
    fun `inactive metrics keep their own color while the active hour criterion rejects`() {
        val decision = useCase(
            offer = TripOffer(
                price = 10.0,
                distanceKm = 5.0,
                durationMinutes = 30.0,
                pickupDistanceKm = 1.0
            ),
            settings = settings.copy(
                isPickupCriterionEnabled = false,
                isKmCriterionEnabled = false,
                isHourCriterionEnabled = true,
                minEurPerHour = 25.0,
                goodEurPerHour = 30.0
            )
        )

        assertEquals(setOf(EvaluationCriterion.HORA), decision.activeCriteria)
        assertEquals(DecisionType.REJEITAR, decision.type)
        assertEquals(DecisionType.ACEITAR, decision.criterionDecisions[EvaluationCriterion.RECOLHA])
        assertEquals(DecisionType.ACEITAR, decision.criterionDecisions[EvaluationCriterion.KM])
        assertEquals(DecisionType.REJEITAR, decision.criterionDecisions[EvaluationCriterion.HORA])
    }

    @Test
    fun `km and hour require both active metrics to be green`() {
        val decision = useCase(
            offer = TripOffer(price = 10.0, distanceKm = 10.0, durationMinutes = 36.0),
            settings = settings.copy(
                isPickupCriterionEnabled = false,
                isKmCriterionEnabled = true,
                isHourCriterionEnabled = true
            )
        )

        assertEquals(DecisionType.ANALISAR, decision.type)
        assertEquals(DecisionType.ACEITAR, decision.criterionDecisions[EvaluationCriterion.KM])
        assertEquals(DecisionType.ANALISAR, decision.criterionDecisions[EvaluationCriterion.HORA])
    }

    @Test
    fun `km thresholds are applied to the card decision for both platforms`() {
        val kmOnlySettings = settings.copy(
            isPickupCriterionEnabled = false,
            isKmCriterionEnabled = true,
            isHourCriterionEnabled = false,
            minEurPerKm = 0.50,
            goodEurPerKm = 1.0
        )
        val baseOffer = TripOffer(price = 8.0, distanceKm = 10.0, durationMinutes = 20.0)

        val uberDecision = useCase(baseOffer.copy(platform = OfferPlatform.UBER), kmOnlySettings)
        val boltDecision = useCase(baseOffer.copy(platform = OfferPlatform.BOLT), kmOnlySettings)

        assertEquals(DecisionType.ANALISAR, uberDecision.type)
        assertEquals(DecisionType.ANALISAR, boltDecision.type)
        assertEquals(DecisionType.ACEITAR, useCase(baseOffer, kmOnlySettings.copy(goodEurPerKm = 0.5)).type)
        assertEquals(DecisionType.REJEITAR, useCase(baseOffer, kmOnlySettings.copy(minEurPerKm = 1.0)).type)
    }

    @Test
    fun `hour thresholds are applied independently from kilometres`() {
        val hourOnlySettings = settings.copy(
            isPickupCriterionEnabled = false,
            isKmCriterionEnabled = false,
            isHourCriterionEnabled = true,
            minEurPerHour = 15.0,
            goodEurPerHour = 20.0
        )
        val offer = TripOffer(price = 8.0, distanceKm = 40.0, durationMinutes = 24.0)

        assertEquals(DecisionType.ACEITAR, useCase(offer, hourOnlySettings).type)
        assertEquals(
            DecisionType.REJEITAR,
            useCase(offer, hourOnlySettings.copy(minEurPerHour = 21.0, goodEurPerHour = 25.0)).type
        )
    }

    @Test
    fun `long trips reject only above the configured destination limit`() {
        val longTripOnlySettings = settings.copy(
            isPickupCriterionEnabled = false,
            isKmCriterionEnabled = false,
            isHourCriterionEnabled = false,
            isLongTripCriterionEnabled = true,
            longTripMinimumKm = 25.0
        )
        val baseOffer = TripOffer(
            price = 12.0,
            distanceKm = 80.0,
            durationMinutes = 30.0,
            tripDistanceKm = 25.0
        )

        val accepted = useCase(baseOffer, longTripOnlySettings)
        val rejected = useCase(baseOffer.copy(tripDistanceKm = 25.1), longTripOnlySettings)

        assertEquals(setOf(EvaluationCriterion.VIAGEM_LONGA), accepted.activeCriteria)
        assertEquals(DecisionType.ACEITAR, accepted.type)
        assertEquals(DecisionType.ACEITAR, accepted.criterionDecisions[EvaluationCriterion.VIAGEM_LONGA])
        assertEquals(DecisionType.REJEITAR, rejected.type)
        assertEquals(DecisionType.REJEITAR, rejected.criterionDecisions[EvaluationCriterion.VIAGEM_LONGA])
    }

    @Test
    fun `long trip criterion accumulates with an active hourly rejection`() {
        val settingsWithLongTrips = settings.copy(
            isPickupCriterionEnabled = false,
            isKmCriterionEnabled = false,
            isHourCriterionEnabled = true,
            isLongTripCriterionEnabled = true,
            longTripMinimumKm = 10.0,
            minEurPerHour = 30.0,
            goodEurPerHour = 35.0
        )
        val offer = TripOffer(
            price = 5.0,
            distanceKm = 7.0,
            durationMinutes = 55.0,
            tripDistanceKm = 5.0
        )

        val decision = useCase(offer, settingsWithLongTrips)

        assertEquals(DecisionType.REJEITAR, decision.criterionDecisions[EvaluationCriterion.HORA])
        assertEquals(DecisionType.ACEITAR, decision.criterionDecisions[EvaluationCriterion.VIAGEM_LONGA])
        assertEquals(DecisionType.REJEITAR, decision.type)
    }

    @Test
    fun `minimum trip value rejects below its binary threshold and accepts at the threshold`() {
        val minimumValueOnly = settings.copy(
            isPickupCriterionEnabled = false,
            isKmCriterionEnabled = false,
            isHourCriterionEnabled = false,
            isLongTripCriterionEnabled = false,
            isMinimumTripValueCriterionEnabled = true,
            minimumTripValue = 3.25
        )
        val baseOffer = TripOffer(price = 3.0, distanceKm = 3.0, durationMinutes = 10.0)

        val rejected = useCase(baseOffer, minimumValueOnly)
        val accepted = useCase(baseOffer.copy(price = 3.25), minimumValueOnly)

        assertEquals(setOf(EvaluationCriterion.VALOR_MINIMO), rejected.activeCriteria)
        assertEquals(DecisionType.REJEITAR, rejected.criterionDecisions[EvaluationCriterion.VALOR_MINIMO])
        assertEquals(DecisionType.REJEITAR, rejected.type)
        assertEquals(DecisionType.ACEITAR, accepted.criterionDecisions[EvaluationCriterion.VALOR_MINIMO])
        assertEquals(DecisionType.ACEITAR, accepted.type)
    }

    @Test
    fun `Bolt toll is subtracted before calculating the km metric and net trip value`() {
        val decision = useCase(
            offer = TripOffer(
                price = 10.0,
                distanceKm = 5.0,
                durationMinutes = 20.0,
                tollAmount = 0.40,
                platform = OfferPlatform.BOLT
            ),
            settings = settings.copy(
                isVehicleCostPerKmEnabled = false,
                isPickupCriterionEnabled = false,
                isKmCriterionEnabled = false,
                isHourCriterionEnabled = false
            )
        )

        assertEquals(1.92, decision.valorPorKmBruto, 0.0)
        assertEquals(1.92, decision.valorPorKm, 0.0)
        assertEquals(9.60, decision.netTripValue!!, 0.0)
        assertEquals(0.40, decision.tollAmount, 0.0)
    }

    @Test
    fun `thresholds are limited and snapped to the values offered by the bars`() {
        val normalized = RuleSettings(
            minEurPerKm = 0.74,
            goodEurPerKm = 1.26,
            minEurPerHour = 40.1,
            goodEurPerHour = 42.0,
            idealPickupDistanceKm = 2.24,
            acceptablePickupDistanceKm = 16.0,
            longTripMinimumKm = 102.0,
            minimumTripValue = 5.2
        ).normalizedThresholds()

        assertEquals(0.75, normalized.minEurPerKm, 0.0)
        assertEquals(1.25, normalized.goodEurPerKm, 0.0)
        assertEquals(40.0, normalized.minEurPerHour, 0.0)
        assertEquals(40.0, normalized.goodEurPerHour, 0.0)
        assertEquals(2.0, normalized.idealPickupDistanceKm, 0.0)
        assertEquals(10.0, normalized.acceptablePickupDistanceKm, 0.0)
        assertEquals(100.0, normalized.longTripMinimumKm, 0.0)
        assertEquals(5.0, normalized.minimumTripValue, 0.0)

        val hourIncrements = RuleSettings(
            minEurPerHour = 15.12,
            goodEurPerHour = 20.38
        ).normalizedThresholds()
        assertEquals(15.0, hourIncrements.minEurPerHour, 0.0)
        assertEquals(20.5, hourIncrements.goodEurPerHour, 0.0)
        assertEquals(5.0, RuleSettings(longTripMinimumKm = 1.0).normalizedThresholds().longTripMinimumKm, 0.0)
        assertEquals(2.5, RuleSettings(minimumTripValue = 1.0).normalizedThresholds().minimumTripValue, 0.0)
        assertEquals(3.25, RuleSettings(minimumTripValue = 3.13).normalizedThresholds().minimumTripValue, 0.0)
    }

    private fun evaluateAtPickupDistance(pickupDistanceKm: Double) = useCase(
        offer = TripOffer(
            price = 12.0,
            distanceKm = 6.0,
            durationMinutes = 24.0,
            pickupDistanceKm = pickupDistanceKm
        ),
        settings = settings
    )
}
