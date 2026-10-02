package com.daniel.tvdeinsight.data.sync.backend

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackendSyncContractTest {
    @Test
    fun requestSerialization_usesOpenApiNamesAndNeverContainsScreenshot() {
        val event = OfferEventPayload(
            eventId = "93b11ab0-d92e-4a5a-b20a-25116eae6dd7",
            recordedAtEpochMs = 1_800_000_000_000,
            platform = "UBER",
            category = "UberX",
            decision = "ACEITAR",
            tripValueCents = 750,
            valuePerKmCents = 55,
            grossValuePerKmCents = 65,
            valuePerHourCents = 2_200
        )
        val request = BackendSyncRequest(
            cursor = null,
            events = listOf(event),
            aggregateQuery = AggregateQueryPayload(
                platforms = listOf("UBER", "BOLT"),
                metric = "VALUE_PER_KM",
                valueMode = "FREE",
                shift = "ALL",
                cardColor = "ALL",
                startDate = "2027-01-01",
                endDate = "2027-01-30"
            )
        )

        val encoded = BackendHttpClient.json.encodeToString(request)

        assertEquals("93b11ab0-d92e-4a5a-b20a-25116eae6dd7", BackendHttpClient.json.decodeFromString<BackendSyncRequest>(encoded).events.single().eventId)
        assertFalse(encoded.contains("screenshot", ignoreCase = true))
        assertFalse(encoded.contains("sourceDeviceId"))
        assertEquals(true, encoded.contains("\"aggregate_query\""))
        assertEquals(true, encoded.contains("\"trip_value_cents\":750"))
    }

    @Test
    fun responseDeserialization_preservesCursorDeltaAndAggregates() {
        val response = BackendHttpClient.json.decodeFromString<BackendSyncResponse>(
            """
            {
              "event_results":[],
              "own_changes":[],
              "next_cursor":"cursor-1",
              "has_more":false,
              "global_aggregates":{
                "query":{"platforms":["UBER","BOLT"],"metric":"VALUE_PER_KM","value_mode":"FREE","shift":"ALL","card_color":"ALL","category":null,"start_date":"2027-01-01","end_date":"2027-01-30"},
                "pickup_municipalities":[],"heatmap":[],"daily_calendar":[],"recorded_dates":[],"generated_at":1800000000
              },
              "aggregate_version":1,"policy_version":1,"server_time":1800000000
            }
            """.trimIndent()
        )

        assertEquals("cursor-1", response.nextCursor)
        assertEquals(1, response.aggregateVersion)
        assertEquals(1_800_000_000, response.globalAggregates.generatedAt)
    }
}
