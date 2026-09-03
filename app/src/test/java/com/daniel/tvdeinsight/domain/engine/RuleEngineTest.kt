package com.daniel.tvdeinsight.domain.engine

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.TripOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val engine = RuleEngine()

    @Test
    fun `stop rule rejects before financial classification`() {
        val decision = engine.evaluate(
            TripOffer(20.0, 1.0, 5.0, "Inclui 1 paragem"),
            RuleSettings()
        )

        assertEquals(DecisionType.REJEITAR, decision.type)
        assertTrue(decision.isStopRejection)
    }

    @Test
    fun `disabled stop rule evaluates the selected metric`() {
        val settings = RuleSettings(
            rejectTripsWithStops = false,
            isKmCriterionEnabled = true,
            isHourCriterionEnabled = false
        )
        val decision = engine.evaluate(TripOffer(10.0, 5.0, 20.0, "1 parada"), settings)

        assertEquals(DecisionType.ACEITAR, decision.type)
        assertEquals(2.0, decision.valorPorKm, 0.001)
    }

    @Test
    fun `intermediate threshold produces review`() {
        val decision = engine.evaluate(TripOffer(6.0, 5.0, 20.0), RuleSettings())

        assertEquals(DecisionType.ANALISAR, decision.type)
    }

    @Test
    fun `vehicle cost is deducted from the value per km when its data is complete`() {
        val settings = RuleSettings(
            rejectTripsWithStops = false,
            isKmCriterionEnabled = true,
            isHourCriterionEnabled = false,
            isVehicleCostPerKmEnabled = true,
            vehicleConsumptionPer100Km = 20.0,
            vehiclePricePerUnit = 0.50
        )

        val decision = engine.evaluate(TripOffer(9.0, 10.0, 30.0), settings)

        assertEquals(0.80, decision.valorPorKm, 0.001)
        assertTrue(decision.isVehicleCostPerKmApplied)
        assertEquals(8.0, decision.netTripValue!!, 0.001)
    }

    @Test
    fun `incomplete vehicle data never changes the value per km`() {
        val settings = RuleSettings(
            rejectTripsWithStops = false,
            isKmCriterionEnabled = true,
            isHourCriterionEnabled = false,
            isVehicleCostPerKmEnabled = true,
            vehicleConsumptionPer100Km = 20.0,
            vehiclePricePerUnit = 0.0
        )

        val decision = engine.evaluate(TripOffer(9.0, 10.0, 30.0), settings)

        assertEquals(0.90, decision.valorPorKm, 0.001)
        assertTrue(!decision.isVehicleCostPerKmApplied)
        assertEquals(null, decision.netTripValue)
    }
}
