package com.daniel.tvdeinsight.data.local

import java.nio.charset.StandardCharsets
import java.util.UUID

object OutboxEventIds {
    fun newId(): String = UUID.randomUUID().toString()

    fun migratedId(sourceDeviceId: String, tripId: Long): String = UUID.nameUUIDFromBytes(
        "tvde-trip-v1:$sourceDeviceId:$tripId".toByteArray(StandardCharsets.UTF_8)
    ).toString()
}

fun TripEntity.toPendingOutbox(eventId: String, nowMillis: Long = System.currentTimeMillis()) =
    SyncOutboxEntity(
        eventId = eventId,
        sourceDeviceId = sourceDeviceId,
        tripId = id,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis
    )
