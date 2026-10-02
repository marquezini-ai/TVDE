package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.local.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferEventMapperTest {
    @Test
    fun mapsRoomTripToBackendUnitsWithoutScreenshot() {
        val payload = OfferEventMapper.fromTrip("93b11ab0-d92e-4a5a-b20a-25116eae6dd7", trip())!!

        assertEquals(649, payload.tripValueCents)
        assertEquals(13400, payload.destinationDistanceMeters)
        assertEquals(720, payload.destinationDurationSeconds)
        assertEquals(41157000, payload.currentLatitudeMicrodegrees)
        assertEquals(listOf("RECOLHA", "KM", "HORA"), payload.activeCriteria)
        assertEquals(3, payload.criterionDecisions.size)
    }

    @Test
    fun rejectsUnsupportedOrInvalidLocalValues() {
        assertNull(OfferEventMapper.fromTrip("93b11ab0-d92e-4a5a-b20a-25116eae6dd7", trip().copy(platform = "UNKNOWN")))
        assertNull(OfferEventMapper.fromTrip("93b11ab0-d92e-4a5a-b20a-25116eae6dd7", trip().copy(tripValue = Double.NaN)))
        assertNull(OfferEventMapper.fromTrip("93b11ab0-d92e-4a5a-b20a-25116eae6dd7", trip().copy(recordedAtMillis = 1L)))
    }

    private fun trip() = TripEntity(
        id = 1_800_000_000_000,
        recordedAtMillis = 1_800_000_000_000,
        platform = "UBER",
        valorPorKm = 0.55,
        valorPorHora = 22.0,
        valorPorKmBruto = 0.65,
        netTripValue = 6.0,
        tollAmount = 0.0,
        isVehicleCostPerKmApplied = true,
        pickupDistanceKm = 2.3,
        destinationDistanceKm = 13.4,
        tripValue = 6.49,
        pickupDurationMinutes = 7.0,
        destinationDurationMinutes = 12.0,
        currentLocationAddress = "Porto",
        currentLocationLatitude = 41.157,
        currentLocationLongitude = -8.629,
        pickupAddress = "Rua A",
        destinationAddress = "Rua B",
        category = "UberX",
        decisionType = "ACEITAR",
        activeCriteria = "RECOLHA,KM,HORA",
        criterionDecisions = "RECOLHA=ACEITAR|KM=ACEITAR|HORA=ACEITAR",
        isStopRejection = false,
        screenshotFileName = "private.png",
        sourceDeviceId = "device-a",
        deduplicationKey = "dedupe"
    )
}
