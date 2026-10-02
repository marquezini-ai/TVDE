package com.daniel.tvdeinsight.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class,
        SyncOutboxEntity::class,
        SyncAttemptEntity::class,
        BackendSyncStateEntity::class,
        BackendOwnChangeEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun syncDao(): SyncDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_history_new (
                        id INTEGER NOT NULL,
                        recordedAtMillis INTEGER NOT NULL,
                        platform TEXT NOT NULL,
                        valorPorKm REAL NOT NULL,
                        valorPorHora REAL NOT NULL,
                        valorPorKmBruto REAL NOT NULL,
                        netTripValue REAL,
                        tollAmount REAL NOT NULL,
                        isVehicleCostPerKmApplied INTEGER NOT NULL,
                        pickupDistanceKm REAL,
                        destinationDistanceKm REAL,
                        tripValue REAL NOT NULL,
                        pickupDurationMinutes REAL,
                        destinationDurationMinutes REAL,
                        currentLocationAddress TEXT,
                        currentLocationLatitude REAL,
                        currentLocationLongitude REAL,
                        pickupAddress TEXT,
                        destinationAddress TEXT,
                        category TEXT,
                        decisionType TEXT NOT NULL,
                        activeCriteria TEXT NOT NULL,
                        criterionDecisions TEXT NOT NULL,
                        isStopRejection INTEGER NOT NULL,
                        sourceDeviceId TEXT NOT NULL DEFAULT '',
                        deduplicationKey TEXT NOT NULL,
                        PRIMARY KEY(sourceDeviceId, id)
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO trip_history_new (
                        id, recordedAtMillis, platform, valorPorKm, valorPorHora,
                        valorPorKmBruto, netTripValue, tollAmount, isVehicleCostPerKmApplied,
                        pickupDistanceKm, destinationDistanceKm, tripValue, pickupDurationMinutes,
                        destinationDurationMinutes, currentLocationAddress, currentLocationLatitude,
                        currentLocationLongitude, pickupAddress, destinationAddress, category,
                        decisionType, activeCriteria, criterionDecisions, isStopRejection,
                        sourceDeviceId, deduplicationKey
                    )
                    SELECT id, recordedAtMillis, platform, valorPorKm, valorPorHora,
                        valorPorKmBruto, netTripValue, tollAmount, isVehicleCostPerKmApplied,
                        pickupDistanceKm, destinationDistanceKm, tripValue, pickupDurationMinutes,
                        destinationDurationMinutes, currentLocationAddress, currentLocationLatitude,
                        currentLocationLongitude, pickupAddress, destinationAddress, category,
                        decisionType, activeCriteria, criterionDecisions, isStopRejection,
                        '', deduplicationKey
                    FROM trip_history
                """.trimIndent())
                database.execSQL("DROP TABLE trip_history")
                database.execSQL("ALTER TABLE trip_history_new RENAME TO trip_history")
                database.execSQL("CREATE UNIQUE INDEX index_trip_history_sourceDeviceId_deduplicationKey ON trip_history(sourceDeviceId, deduplicationKey)")
                database.execSQL("CREATE INDEX index_trip_history_recordedAtMillis ON trip_history(recordedAtMillis)")
                database.execSQL("CREATE INDEX index_trip_history_platform ON trip_history(platform)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trip_history ADD COLUMN screenshotFileName TEXT")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_trip_history_sourceDeviceId_recordedAtMillis " +
                        "ON trip_history(sourceDeviceId, recordedAtMillis)"
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        sourceDeviceId TEXT NOT NULL,
                        tripId INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextAttemptAtMillis INTEGER NOT NULL,
                        lastErrorCode TEXT,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_sourceDeviceId_tripId " +
                        "ON sync_outbox(sourceDeviceId, tripId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_outbox_state_nextAttemptAtMillis " +
                        "ON sync_outbox(state, nextAttemptAtMillis)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_attempt (
                        singletonId INTEGER NOT NULL PRIMARY KEY,
                        idempotencyKey TEXT NOT NULL,
                        requestBody TEXT NOT NULL,
                        eventIdsJson TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backend_sync_state (
                        singletonId INTEGER NOT NULL PRIMARY KEY,
                        confirmedCursor TEXT,
                        policyVersion INTEGER,
                        aggregateVersion INTEGER,
                        globalAggregatesJson TEXT,
                        lastSuccessfulSyncAtMillis INTEGER,
                        serverTimeEpochSeconds INTEGER
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backend_own_change (
                        sequence INTEGER NOT NULL PRIMARY KEY,
                        eventId TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        receivedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_backend_own_change_eventId " +
                        "ON backend_own_change(eventId)"
                )
            }
        }
    }
}
