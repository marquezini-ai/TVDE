package com.daniel.tvdeinsight.data.repository

import com.daniel.tvdeinsight.data.local.AppDatabase
import com.daniel.tvdeinsight.data.local.TripEntityMapper
import com.daniel.tvdeinsight.data.location.DeviceLocationProvider
import com.daniel.tvdeinsight.data.identity.DeviceIdentity
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

@Singleton
class RoomOfferAnalysisStore @Inject constructor(
    private val database: AppDatabase,
    private val deviceLocationProvider: DeviceLocationProvider,
    private val deviceIdentity: DeviceIdentity,
    private val legacyStore: Lazy<DataStoreOfferAnalysisStore>
) : OfferAnalysisStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val lastGeneratedEntryId = AtomicLong(0L)
    private val pendingScreenshotFiles = mutableMapOf<Long, String>()
    private val mutableLatestDecision = MutableStateFlow<RuleResult?>(null)

    override val latestDecision: StateFlow<RuleResult?> = mutableLatestDecision
    override val history: StateFlow<List<OfferHistoryEntry>> = database.tripDao()
        .observeBySourceDeviceId(deviceIdentity.sourceId)
        .map { rows -> rows.map(TripEntityMapper::toDomain) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val globalHistory: StateFlow<List<OfferHistoryEntry>> = database.tripDao()
        .observeAll()
        .map { rows -> rows.map(TripEntityMapper::toDomain) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val legacyEntries = legacyStore.get().readPersistedHistory()
            if (legacyEntries.isNotEmpty()) {
                database.tripDao().upsertAll(
                    legacyEntries.map { it.copy(sourceDeviceId = deviceIdentity.sourceId) }
                        .map(TripEntityMapper::fromDomain)
                )
                AppLogger.info("Migração concluída: ${legacyEntries.size} ofertas do DataStore para Room")
            }
        }
    }

    override fun publish(offer: TripOffer, decision: RuleResult): Long {
        mutableLatestDecision.value = decision
        val entryId = nextEntryId()
        scope.launch {
            val location = deviceLocationProvider.captureRecentLocation()
            val entry = OfferHistoryEntry.from(
                offer = offer,
                decision = decision,
                recordedAtMillis = entryId,
                currentLocationAddress = location?.address,
                currentLocationLatitude = location?.latitude,
                currentLocationLongitude = location?.longitude
            ).copy(sourceDeviceId = deviceIdentity.sourceId)
            writeMutex.withLock {
                val entryWithScreenshot = entry.copy(screenshotFileName = pendingScreenshotFiles.remove(entryId))
                val inserted = database.tripDao().insertIgnore(TripEntityMapper.fromDomain(entryWithScreenshot))
                if (inserted == -1L) {
                    AppLogger.debug("Oferta duplicada ignorada no Room: plataforma=${entryWithScreenshot.platform}")
                }
            }
        }
        return entryId
    }

    override fun attachScreenshot(entryId: Long, fileName: String) {
        scope.launch {
            writeMutex.withLock {
                val changed = database.tripDao().updateScreenshotFileName(
                    sourceDeviceId = deviceIdentity.sourceId,
                    id = entryId,
                    fileName = fileName
                )
                if (changed == 0) pendingScreenshotFiles[entryId] = fileName
            }
        }
    }

    private fun nextEntryId(): Long {
        while (true) {
            val current = lastGeneratedEntryId.get()
            val next = maxOf(System.currentTimeMillis(), current + 1L)
            if (lastGeneratedEntryId.compareAndSet(current, next)) return next
        }
    }
}
