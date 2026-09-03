package com.daniel.tvdeinsight.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OfferHistoryEntryTest {

    @Test
    fun `creates a history entry with the offer values and calculated metrics`() {
        val offer = TripOffer(
            price = 12.5,
            distanceKm = 8.0,
            durationMinutes = 24.0,
            pickupDistanceKm = 1.5,
            pickupDurationMinutes = 4.0,
            tripDistanceKm = 6.5,
            tripDurationMinutes = 20.0,
            pickupAddress = "Rua da Prata, Lisboa",
            destinationAddress = "Avenida da Liberdade, Lisboa",
            category = "Comfort",
            platform = OfferPlatform.UBER
        )
        val decision = RuleResult(
            type = DecisionType.ACEITAR,
            valorPorKm = 1.56,
            valorPorHora = 31.25,
            activeCriteria = setOf(EvaluationCriterion.KM),
            criterionDecisions = mapOf(EvaluationCriterion.KM to DecisionType.ACEITAR),
            platform = OfferPlatform.UBER,
            pickupDistanceKm = 1.5
        )

        val entry = OfferHistoryEntry.from(offer, decision, recordedAtMillis = 1_234L)

        assertEquals(OfferPlatform.UBER, entry.platform)
        assertEquals(12.5, entry.tripValue, 0.0)
        assertEquals(1.56, entry.valorPorKm, 0.0)
        assertEquals(31.25, entry.valorPorHora, 0.0)
        assertEquals(1.5, entry.pickupDistanceKm!!, 0.0)
        assertEquals(6.5, entry.destinationDistanceKm!!, 0.0)
        assertEquals(4.0, entry.pickupDurationMinutes!!, 0.0)
        assertEquals(20.0, entry.destinationDurationMinutes!!, 0.0)
        assertEquals("Rua da Prata, Lisboa", entry.pickupAddress)
        assertEquals("Avenida da Liberdade, Lisboa", entry.destinationAddress)
        assertEquals("Comfort", entry.category)
        assertEquals(DecisionType.ACEITAR, entry.decisionType)
        assertEquals(setOf(EvaluationCriterion.KM), entry.activeCriteria)
        assertEquals(DecisionType.ACEITAR, entry.criterionDecisions[EvaluationCriterion.KM])
    }
}
