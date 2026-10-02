package com.daniel.tvdeinsight.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncDao {
    @Query(
        "SELECT * FROM sync_outbox WHERE state = 'PENDING' AND nextAttemptAtMillis <= :nowMillis " +
            "ORDER BY createdAtMillis ASC LIMIT :limit"
    )
    suspend fun pending(nowMillis: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'PENDING' OR state = 'IN_FLIGHT'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM sync_attempt WHERE singletonId = 1")
    suspend fun activeAttempt(): SyncAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(entity: SyncAttemptEntity)

    @Query("DELETE FROM sync_attempt WHERE singletonId = 1")
    suspend fun clearAttempt()

    @Query("SELECT * FROM backend_sync_state WHERE singletonId = 1")
    suspend fun state(): BackendSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(entity: BackendSyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOwnChanges(entities: List<BackendOwnChangeEntity>)

    @Query(
        "UPDATE sync_outbox SET state = :state, attemptCount = attemptCount + 1, " +
            "nextAttemptAtMillis = :nextAttemptAtMillis, lastErrorCode = :errorCode, " +
            "updatedAtMillis = :updatedAtMillis WHERE eventId IN (:eventIds)"
    )
    suspend fun updateAttempts(
        eventIds: List<String>,
        state: String,
        nextAttemptAtMillis: Long,
        errorCode: String?,
        updatedAtMillis: Long
    )

    @Query(
        "UPDATE sync_outbox SET state = 'SENT', lastErrorCode = NULL, updatedAtMillis = :updatedAtMillis " +
            "WHERE eventId IN (:eventIds)"
    )
    suspend fun markSent(eventIds: List<String>, updatedAtMillis: Long)

    @Query(
        "UPDATE sync_outbox SET state = 'PERMANENT_FAILURE', lastErrorCode = :errorCode, " +
            "updatedAtMillis = :updatedAtMillis WHERE eventId = :eventId"
    )
    suspend fun markPermanentFailure(eventId: String, errorCode: String, updatedAtMillis: Long)

    @Query(
        "SELECT t.* FROM trip_history t INNER JOIN sync_outbox o " +
            "ON o.sourceDeviceId = t.sourceDeviceId AND o.tripId = t.id WHERE o.eventId IN (:eventIds)"
    )
    suspend fun tripsForEvents(eventIds: List<String>): List<TripEntity>

    @Transaction
    suspend fun startAttempt(attempt: SyncAttemptEntity, eventIds: List<String>, nowMillis: Long) {
        insertAttempt(attempt)
        updateAttempts(
            eventIds = eventIds,
            state = SyncOutboxState.IN_FLIGHT.name,
            nextAttemptAtMillis = nowMillis,
            errorCode = null,
            updatedAtMillis = nowMillis
        )
    }
}
