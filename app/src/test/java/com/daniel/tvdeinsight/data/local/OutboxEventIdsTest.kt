package com.daniel.tvdeinsight.data.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OutboxEventIdsTest {
    @Test
    fun migratedId_isStableAndValidUuid() {
        val first = OutboxEventIds.migratedId("device-a", 123L)
        val repeated = OutboxEventIds.migratedId("device-a", 123L)

        assertEquals(first, repeated)
        assertEquals(first, UUID.fromString(first).toString())
        assertNotEquals(first, OutboxEventIds.migratedId("device-a", 124L))
        assertNotEquals(first, OutboxEventIds.migratedId("device-b", 123L))
    }
}
