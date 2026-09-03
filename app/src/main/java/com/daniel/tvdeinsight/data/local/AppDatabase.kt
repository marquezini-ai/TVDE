package com.daniel.tvdeinsight.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TripEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

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
    }
}
