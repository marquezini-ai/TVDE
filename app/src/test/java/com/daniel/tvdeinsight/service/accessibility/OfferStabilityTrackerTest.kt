package com.daniel.tvdeinsight.service.accessibility

import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.TripOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferStabilityTrackerTest {
    @Test fun `keeps Uber confirmation when Bolt events arrive between reads`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 8_000)

        assertFalse(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 0))
        assertFalse(tracker.shouldPublish(offer(OfferPlatform.BOLT), nowMs = 100))
        assertTrue(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 200))
    }

    @Test fun `normalizes insignificant floating point noise`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 8_000)

        assertFalse(tracker.shouldPublish(offer(OfferPlatform.UBER, 28.4), nowMs = 0))
        assertTrue(tracker.shouldPublish(offer(OfferPlatform.UBER, 28.400000000000002), nowMs = 200))
    }

    @Test fun `suppresses a repeated decision until the duplicate window expires`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 20_000)

        assertFalse(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 0))
        assertTrue(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 200))
        assertFalse(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 10_000))
        assertTrue(tracker.shouldPublish(offer(OfferPlatform.UBER), nowMs = 20_201))
    }

    @Test fun `requests a fast confirmation only for the first stable reading`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 20_000)
        val offer = offer(OfferPlatform.UBER)

        assertFalse(tracker.shouldPublish(offer, nowMs = 0))
        assertTrue(tracker.isAwaitingConfirmation(offer))
        assertTrue(tracker.shouldPublish(offer, nowMs = 200))
        assertFalse(tracker.isAwaitingConfirmation(offer))
    }

    @Test fun `publishes accessibility offer immediately but blocks duplicate events`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 20_000)
        val offer = offer(OfferPlatform.BOLT)

        assertTrue(tracker.shouldPublishImmediately(offer, nowMs = 0))
        assertFalse(tracker.shouldPublishImmediately(offer, nowMs = 300))
        assertTrue(tracker.shouldPublishImmediately(offer, nowMs = 20_001))
    }

    @Test fun `does not publish when the second reading has different route data`() {
        val tracker = OfferStabilityTracker(requiredConsecutiveReadings = 2, duplicateWindowMs = 20_000)
        val firstReading = offer(OfferPlatform.UBER)
        val secondReading = firstReading.copy(
            pickupDistanceKm = 7.3,
            tripDistanceKm = 2.4,
            distanceKm = 9.7,
            pickupDurationMinutes = 11.0,
            tripDurationMinutes = 5.0,
            durationMinutes = 16.0
        )

        assertFalse(tracker.shouldPublish(firstReading, nowMs = 0))
        assertFalse(tracker.shouldPublish(secondReading, nowMs = 250))
        assertTrue(tracker.shouldPublish(secondReading, nowMs = 500))
    }

    private fun offer(platform: OfferPlatform, distanceKm: Double = 8.6) = TripOffer(
        price = 4.41,
        distanceKm = distanceKm,
        durationMinutes = 18.0,
        pickupDistanceKm = distanceKm / 2,
        pickupDurationMinutes = 6.0,
        tripDistanceKm = distanceKm / 2,
        tripDurationMinutes = 12.0,
        platform = platform
    )
}
