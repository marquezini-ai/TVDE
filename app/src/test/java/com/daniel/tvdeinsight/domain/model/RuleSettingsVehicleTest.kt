package com.daniel.tvdeinsight.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSettingsVehicleTest {

    @Test fun `vehicle inputs preserve the requested decimal precision`() {
        val settings = RuleSettings(
            vehicleType = VehicleType.ELECTRIC,
            vehicleConsumptionPer100Km = 16.36,
            vehiclePricePerUnit = 0.278
        ).normalizedThresholds()

        assertEquals(VehicleType.ELECTRIC, settings.vehicleType)
        assertEquals(16.4, settings.vehicleConsumptionPer100Km, 0.0)
        assertEquals(0.28, settings.vehiclePricePerUnit, 0.0)
    }

    @Test fun `criteria lock is preserved and old Waze selection migrates to Google Maps`() {
        val settings = RuleSettings(
            areEvaluationCriteriaLocked = true,
            navigationApp = NavigationApp.WAZE
        ).normalizedThresholds()

        assertTrue(settings.areEvaluationCriteriaLocked)
        assertEquals(NavigationApp.GOOGLE_MAPS, settings.navigationApp)
    }
}
