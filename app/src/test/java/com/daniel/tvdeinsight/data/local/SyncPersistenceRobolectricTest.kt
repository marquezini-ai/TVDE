package com.daniel.tvdeinsight.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SyncPersistenceRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "sync-persistence-test.db"

    @Before fun prepare() { context.deleteDatabase(databaseName) }
    @After fun cleanup() { context.deleteDatabase(databaseName) }

    @Test
    fun activeAttemptSurvivesReopen_andCompletionCommitsCursorDeltaAndAggregatesAtomically() = runBlocking {
        val attempt = SyncAttemptEntity(
            idempotencyKey = "stable-idempotency-key",
            requestBody = "{\"stable\":true}",
            eventIdsJson = "[\"event-1\"]",
            createdAtMillis = 100
        )
        val firstDatabase = open()
        try {
            val database = firstDatabase
            database.syncDao().insertOutbox(
                SyncOutboxEntity(
                    eventId = "event-1",
                    sourceDeviceId = "device-1",
                    tripId = 1,
                    createdAtMillis = 100,
                    updatedAtMillis = 100
                )
            )
            database.syncDao().startAttempt(attempt, listOf("event-1"), 100)
        } finally { firstDatabase.close() }

        val reopenedDatabase = open()
        try {
            val database = reopenedDatabase
            assertEquals(attempt, database.syncDao().activeAttempt())
            assertEquals(SyncOutboxState.IN_FLIGHT.name, database.syncDao().outboxEvent("event-1")?.state)
            database.syncDao().completeAttempt(
                sentEventIds = listOf("event-1"),
                rejectedEvents = emptyMap(),
                ownChanges = listOf(BackendOwnChangeEntity(7, "event-1", "{}", 200)),
                newState = BackendSyncStateEntity(
                    confirmedCursor = "cursor-7",
                    policyVersion = 2,
                    aggregateVersion = 3,
                    globalAggregatesJson = "{\"aggregate\":true}",
                    lastSuccessfulSyncAtMillis = 200,
                    serverTimeEpochSeconds = 1800000000
                ),
                nowMillis = 200
            )
            assertNull(database.syncDao().activeAttempt())
            assertEquals(SyncOutboxState.SENT.name, database.syncDao().outboxEvent("event-1")?.state)
            assertEquals("cursor-7", database.syncDao().state()?.confirmedCursor)
            assertEquals(7, database.syncDao().ownChanges().single().sequence)
        } finally { reopenedDatabase.close() }
    }

    @Test
    fun cursorResetKeepsEventAndReturnsItToPending() = runBlocking {
        val database = open()
        try {
            database.syncDao().insertOutbox(
                SyncOutboxEntity("event-1", "device-1", 1, createdAtMillis = 100, updatedAtMillis = 100)
            )
            database.syncDao().saveState(BackendSyncStateEntity(confirmedCursor = "expired"))
            database.syncDao().startAttempt(
                SyncAttemptEntity(idempotencyKey = "same-key", requestBody = "{}", eventIdsJson = "[\"event-1\"]", createdAtMillis = 100),
                listOf("event-1"),
                100
            )
            database.syncDao().resetExpiredCursor(listOf("event-1"), 200)
            assertNull(database.syncDao().activeAttempt())
            assertNull(database.syncDao().state()?.confirmedCursor)
            assertEquals(SyncOutboxState.PENDING.name, database.syncDao().outboxEvent("event-1")?.state)
            assertNotNull(database.syncDao().pending(200, 10).singleOrNull())
        } finally { database.close() }
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5
        )
        .allowMainThreadQueries()
        .build()
}
