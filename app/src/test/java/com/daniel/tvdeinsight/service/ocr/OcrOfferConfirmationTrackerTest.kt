package com.daniel.tvdeinsight.service.ocr

import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.TripOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrOfferConfirmationTrackerTest {

    @Test fun `different second reading never replaces the first complete card`() {
        val tracker = OcrOfferConfirmationTracker(
            confirmationWindowMs = 4_000,
            duplicateWindowMs = 20_000
        )
        val correct = offer(price = 7.05)
        val priorityBonus = offer(price = 0.85)

        assertEquals(
            OcrOfferConfirmationTracker.Result.AWAITING_CONFIRMATION,
            tracker.observe(correct, nowMs = 0)
        )
        assertEquals(
            OcrOfferConfirmationTracker.Result.MISMATCH,
            tracker.observe(priorityBonus, nowMs = 250)
        )
        assertEquals(
            OcrOfferConfirmationTracker.Result.MISMATCH,
            tracker.observe(priorityBonus, nowMs = 500)
        )
        assertEquals(
            OcrOfferConfirmationTracker.Result.CONFIRMED,
            tracker.observe(correct, nowMs = 750)
        )
    }

    @Test fun `confirmation compares pickup and destination metrics as well as price`() {
        val tracker = OcrOfferConfirmationTracker(
            confirmationWindowMs = 4_000,
            duplicateWindowMs = 20_000
        )
        val first = offer(price = 5.52)
        val wrongRoute = first.copy(
            distanceKm = 11.2,
            durationMinutes = 24.0,
            pickupDistanceKm = 1.5,
            pickupDurationMinutes = 4.0,
            tripDistanceKm = 9.7,
            tripDurationMinutes = 20.0
        )

        assertEquals(
            OcrOfferConfirmationTracker.Result.AWAITING_CONFIRMATION,
            tracker.observe(first, nowMs = 0)
        )
        assertEquals(
            OcrOfferConfirmationTracker.Result.MISMATCH,
            tracker.observe(wrongRoute, nowMs = 250)
        )
        assertEquals(
            OcrOfferConfirmationTracker.Result.CONFIRMED,
            tracker.observe(first.copy(category = "Nome noutra língua"), nowMs = 500)
        )
    }

    private fun offer(price: Double) = TripOffer(
        price = price,
        distanceKm = 7.9,
        durationMinutes = 18.0,
        pickupDistanceKm = 3.6,
        pickupDurationMinutes = 8.0,
        tripDistanceKm = 4.3,
        tripDurationMinutes = 10.0,
        category = "Comfort",
        platform = OfferPlatform.UBER
    )
}
