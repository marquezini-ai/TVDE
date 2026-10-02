package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.identity.DeviceIdentity
import com.daniel.tvdeinsight.data.local.AppDatabase
import com.daniel.tvdeinsight.data.local.BackendOwnChangeEntity
import com.daniel.tvdeinsight.data.local.BackendSyncStateEntity
import com.daniel.tvdeinsight.data.local.SyncAttemptEntity
import com.daniel.tvdeinsight.data.sync.SyncGateway
import com.daniel.tvdeinsight.data.sync.SyncGatewayResult
import com.daniel.tvdeinsight.data.sync.crypto.DeviceKeyStore
import com.daniel.tvdeinsight.data.sync.crypto.DeviceKeyStoreException
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Singleton
class BackendSyncGateway @Inject constructor(
    private val database: AppDatabase,
    private val deviceIdentity: DeviceIdentity,
    private val registrationStore: BackendRegistrationStore,
    private val keyStore: DeviceKeyStore,
    private val api: BackendSyncApi
) : SyncGateway {
    private val mutex = Mutex()
    private val json = BackendHttpClient.json

    override suspend fun synchronize(): SyncGatewayResult = mutex.withLock {
        val registration = registrationStore.state()
        val installationId = registration.installationId ?: return SyncGatewayResult.NotConfigured
        if (registration.status != BackendRegistrationStatus.ACTIVE) return SyncGatewayResult.NotConfigured
        val keyPair = try {
            keyStore.loadOrCreate(allowCreate = false)
        } catch (_: DeviceKeyStoreException) {
            registrationStore.markFailure(
                BackendRegistrationStatus.KEY_RECOVERY_REQUIRED,
                "DEVICE_KEY_UNAVAILABLE",
                discardActivationKey = false
            )
            return SyncGatewayResult.PermanentFailure("DEVICE_KEY_UNAVAILABLE")
        }
        database.tripDao().backfillOwnOutbox(deviceIdentity.sourceId)
        val attempt = database.syncDao().activeAttempt() ?: createAttempt()
        val eventIds = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), attempt.eventIdsJson)
        }.getOrElse { return SyncGatewayResult.PermanentFailure("LOCAL_ATTEMPT_CORRUPT") }
        return when (val result = api.sync(
            installationId = installationId,
            keyPair = keyPair,
            requestBody = attempt.requestBody,
            idempotencyKey = attempt.idempotencyKey
        )) {
            is BackendSyncApiResult.Success -> applySuccess(result.response, eventIds)
            is BackendSyncApiResult.Failure -> handleFailure(result, eventIds)
        }
    }

    private suspend fun createAttempt(): SyncAttemptEntity {
        val syncDao = database.syncDao()
        val now = System.currentTimeMillis()
        val pending = syncDao.pending(now, MAX_EVENTS_PER_REQUEST)
        val trips = syncDao.tripsForEvents(pending.map { it.eventId })
            .associateBy { it.sourceDeviceId to it.id }
        val validEvents = mutableListOf<OfferEventPayload>()
        val validIds = mutableListOf<String>()
        pending.forEach { outbox ->
            val trip = trips[outbox.sourceDeviceId to outbox.tripId]
            val event = trip?.let { OfferEventMapper.fromTrip(outbox.eventId, it) }
            if (event == null) {
                syncDao.markPermanentFailure(outbox.eventId, "LOCAL_EVENT_INVALID", now)
            } else {
                validEvents += event
                validIds += outbox.eventId
            }
        }
        val state = syncDao.state()
        val request = BackendSyncRequest(
            cursor = state?.confirmedCursor,
            events = validEvents,
            aggregateQuery = defaultAggregateQuery()
        )
        val attempt = SyncAttemptEntity(
            idempotencyKey = UUID.randomUUID().toString(),
            requestBody = json.encodeToString(request),
            eventIdsJson = json.encodeToString(ListSerializer(String.serializer()), validIds),
            createdAtMillis = now
        )
        syncDao.startAttempt(attempt, validIds, now)
        return attempt
    }

    private suspend fun applySuccess(
        response: BackendSyncResponse,
        expectedEventIds: List<String>
    ): SyncGatewayResult {
        val resultsById = response.eventResults.associateBy { it.eventId }
        if (resultsById.size != response.eventResults.size || resultsById.keys != expectedEventIds.toSet()) {
            return SyncGatewayResult.RetryLater("INVALID_EVENT_RESULTS")
        }
        val sent = response.eventResults
            .filter { it.status == "ACCEPTED" || it.status == "DUPLICATE" }
            .map { it.eventId }
        val rejected = response.eventResults
            .filter { it.status == "REJECTED" }
            .associate { it.eventId to (it.code ?: "EVENT_REJECTED") }
        if (sent.size + rejected.size != expectedEventIds.size) {
            return SyncGatewayResult.RetryLater("INVALID_EVENT_STATUS")
        }
        val now = System.currentTimeMillis()
        val ownChanges = response.ownChanges.map { change ->
            BackendOwnChangeEntity(
                sequence = change.sequence,
                eventId = change.event.eventId,
                payloadJson = json.encodeToString(change.event),
                receivedAtMillis = now
            )
        }
        val state = BackendSyncStateEntity(
            confirmedCursor = response.nextCursor,
            policyVersion = response.policyVersion,
            aggregateVersion = response.aggregateVersion,
            globalAggregatesJson = json.encodeToString(response.globalAggregates),
            lastSuccessfulSyncAtMillis = now,
            serverTimeEpochSeconds = response.serverTime
        )
        database.syncDao().completeAttempt(sent, rejected, ownChanges, state, now)
        val pendingCount = database.syncDao().pendingCount()
        return SyncGatewayResult.Completed(
            hasMore = response.hasMore || pendingCount > 0,
            pendingEvents = pendingCount
        )
    }

    private suspend fun handleFailure(
        failure: BackendSyncApiResult.Failure,
        eventIds: List<String>
    ): SyncGatewayResult {
        if (failure.code == "CURSOR_EXPIRED" || failure.code == "INVALID_CURSOR") {
            database.syncDao().resetExpiredCursor(eventIds, System.currentTimeMillis())
            return SyncGatewayResult.RetryLater("CURSOR_RESET")
        }
        if (failure.retryable) {
            return SyncGatewayResult.RetryLater(failure.code, failure.retryAfterSeconds)
        }
        if (failure.code == "INSTALLATION_REVOKED") {
            registrationStore.markFailure(
                BackendRegistrationStatus.REVOKED,
                failure.code,
                discardActivationKey = true
            )
        }
        return SyncGatewayResult.PermanentFailure(failure.code)
    }

    private fun defaultAggregateQuery(): AggregateQueryPayload {
        val end = LocalDate.now()
        return AggregateQueryPayload(
            platforms = listOf("UBER", "BOLT"),
            metric = "VALUE_PER_KM",
            valueMode = "FREE",
            shift = "ALL",
            cardColor = "ALL",
            startDate = end.minusDays(29).toString(),
            endDate = end.toString()
        )
    }

    private companion object {
        const val MAX_EVENTS_PER_REQUEST = 100
    }
}
