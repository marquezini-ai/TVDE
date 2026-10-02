package com.daniel.tvdeinsight.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "room-migration-4-5-test.db"

    @Before
    fun prepare() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration4To5_preservesTripsAndCreatesEmptyOutbox() {
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(V4_TRIP_TABLE)
            database.execSQL(
                "CREATE UNIQUE INDEX index_trip_history_sourceDeviceId_deduplicationKey " +
                    "ON trip_history(sourceDeviceId, deduplicationKey)"
            )
            database.execSQL(
                "CREATE INDEX index_trip_history_sourceDeviceId_recordedAtMillis " +
                    "ON trip_history(sourceDeviceId, recordedAtMillis)"
            )
            database.execSQL("CREATE INDEX index_trip_history_recordedAtMillis ON trip_history(recordedAtMillis)")
            database.execSQL("CREATE INDEX index_trip_history_platform ON trip_history(platform)")
            database.execSQL(
                """
                INSERT INTO trip_history (
                    id, recordedAtMillis, platform, valorPorKm, valorPorHora, valorPorKmBruto,
                    netTripValue, tollAmount, isVehicleCostPerKmApplied, pickupDistanceKm,
                    destinationDistanceKm, tripValue, pickupDurationMinutes, destinationDurationMinutes,
                    currentLocationAddress, currentLocationLatitude, currentLocationLongitude,
                    pickupAddress, destinationAddress, category, decisionType, activeCriteria,
                    criterionDecisions, isStopRejection, screenshotFileName, sourceDeviceId,
                    deduplicationKey
                ) VALUES (
                    123, 123, 'UBER', 1.0, 20.0, 1.0, NULL, 0.0, 0, 1.0, 5.0, 7.0,
                    2.0, 10.0, NULL, NULL, NULL, NULL, NULL, 'UberX', 'ACEITAR', '', '', 0,
                    NULL, 'device-a', 'dedupe-a'
                )
                """.trimIndent()
            )
            database.version = 4
        } finally {
            database.close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals(1, migrated.tripDao().getAll().size)
                assertEquals(0, migrated.syncDao().pendingCount())
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        val V4_TRIP_TABLE =
            """
            CREATE TABLE trip_history (
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
                screenshotFileName TEXT,
                sourceDeviceId TEXT NOT NULL,
                deduplicationKey TEXT NOT NULL,
                PRIMARY KEY(sourceDeviceId, id)
            )
            """.trimIndent()
    }
}
