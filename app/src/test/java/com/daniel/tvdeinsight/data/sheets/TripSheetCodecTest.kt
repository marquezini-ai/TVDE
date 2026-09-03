package com.daniel.tvdeinsight.data.sheets

import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

class TripSheetCodecTest {
    @Test fun `formats monetary and distance fields with two Portuguese decimals`() {
        val row = TripSheetCodec.toRow(
            OfferHistoryEntry(
                id = 1L,
                recordedAtMillis = 2L,
                platform = OfferPlatform.UBER,
                valorPorKm = 0.9,
                valorPorHora = 18.5,
                valorPorKmBruto = 1.05,
                netTripValue = 3.1,
                tollAmount = 0.4,
                pickupDistanceKm = 1.6,
                destinationDistanceKm = 2.0,
                tripValue = 7.05,
                pickupDurationMinutes = 4.0,
                destinationDurationMinutes = 8.0
            )
        )

        assertEquals("7,05", row[5])
        assertEquals("3,10", row[6])
        assertEquals("0,40", row[7])
        assertEquals("0,90", row[8])
        assertEquals("1,05", row[9])
        assertEquals("18,50", row[10])
        assertEquals("1,60", row[11])
        assertEquals("2,00", row[14])
        assertEquals("4,00", row[12])
        assertEquals("8,00", row[15])
    }
}
