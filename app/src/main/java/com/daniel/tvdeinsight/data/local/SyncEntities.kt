package com.daniel.tvdeinsight.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncOutboxState { PENDING, IN_FLIGHT, SENT, PERMANENT_FAILURE }

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["sourceDeviceId", "tripId"], unique = true),
        Index(value = ["state", "nextAttemptAtMillis"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey val eventId: String,
    val sourceDeviceId: String,
    val tripId: Long,
    val state: String = SyncOutboxState.PENDING.name,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val lastErrorCode: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "sync_attempt")
data class SyncAttemptEntity(
    @PrimaryKey val singletonId: Int = 1,
    val idempotencyKey: String,
    val requestBody: String,
    val eventIdsJson: String,
    val createdAtMillis: Long
)

@Entity(tableName = "backend_sync_state")
data class BackendSyncStateEntity(
    @PrimaryKey val singletonId: Int = 1,
    val confirmedCursor: String? = null,
    val policyVersion: Int? = null,
    val aggregateVersion: Int? = null,
    val globalAggregatesJson: String? = null,
    val lastSuccessfulSyncAtMillis: Long? = null,
    val serverTimeEpochSeconds: Long? = null
)

@Entity(
    tableName = "backend_own_change",
    indices = [Index(value = ["eventId"])]
)
data class BackendOwnChangeEntity(
    @PrimaryKey val sequence: Long,
    val eventId: String,
    val payloadJson: String,
    val receivedAtMillis: Long
)
