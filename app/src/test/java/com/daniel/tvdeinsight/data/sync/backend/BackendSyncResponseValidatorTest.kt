package com.daniel.tvdeinsight.data.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSyncResponseValidatorTest {
    @Test
    fun acceptedDuplicateAndRejected_areAccountedForExactlyOnce() {
        val response = response(
            listOf(
                EventResultPayload("a", "ACCEPTED"),
                EventResultPayload("b", "DUPLICATE"),
                EventResultPayload("c", "REJECTED", "EVENT_INVALID")
            )
        )

        val validated = BackendSyncResponseValidator.validate(response, listOf("a", "b", "c"))!!

        assertEquals(listOf("a", "b"), validated.sent)
        assertEquals(mapOf("c" to "EVENT_INVALID"), validated.rejected)
    }

    @Test
    fun missingDuplicateOrUnknownStatus_isRejectedWithoutLocalAck() {
        assertNull(BackendSyncResponseValidator.validate(response(listOf(EventResultPayload("a", "ACCEPTED"))), listOf("a", "b")))
        assertNull(BackendSyncResponseValidator.validate(response(listOf(EventResultPayload("a", "ACCEPTED"), EventResultPayload("a", "DUPLICATE"))), listOf("a")))
        assertNull(BackendSyncResponseValidator.validate(response(listOf(EventResultPayload("a", "UNKNOWN"))), listOf("a")))
    }

    @Test
    fun onlyExplicitCursorErrors_resetCursor() {
        assertTrue(BackendSyncResponseValidator.invalidatesCursor("CURSOR_EXPIRED"))
        assertTrue(BackendSyncResponseValidator.invalidatesCursor("INVALID_CURSOR"))
        assertEquals(false, BackendSyncResponseValidator.invalidatesCursor("NETWORK_UNAVAILABLE"))
    }

    private fun response(results: List<EventResultPayload>) = BackendSyncResponse(
        eventResults = results,
        ownChanges = emptyList(),
        nextCursor = "cursor-next",
        hasMore = false,
        globalAggregates = GlobalAggregatesPayload(
            query = BackendAggregateQueryStore.defaultQuery(java.time.LocalDate.of(2027, 1, 30)),
            pickupMunicipalities = emptyList(),
            heatmap = emptyList(),
            dailyCalendar = emptyList(),
            recordedDates = emptyList(),
            generatedAt = 1_800_000_000
        ),
        aggregateVersion = 1,
        policyVersion = 1,
        serverTime = 1_800_000_000
    )
}
