package com.daniel.tvdeinsight.data.repository

import com.daniel.tvdeinsight.data.local.AppDatabase
import com.daniel.tvdeinsight.data.local.TripEntityMapper
import com.daniel.tvdeinsight.data.identity.DeviceIdentity
import com.daniel.tvdeinsight.data.sheets.GoogleSheetsClient
import com.daniel.tvdeinsight.data.sheets.TripSheetCodec
import com.daniel.tvdeinsight.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripSyncRepository @Inject constructor(
    private val database: AppDatabase,
    private val sheetsClient: GoogleSheetsClient,
    private val deviceIdentity: DeviceIdentity
) {
    private val syncMutex = Mutex()

    suspend fun uploadPending(): Boolean = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            AppLogger.info("Upload Sheets iniciado")
            if (!sheetsClient.isConfigured) {
                AppLogger.warn("Upload Sheets ignorado: configuração incompleta")
                return@withContext false
            }
            val remoteRows = sheetsClient.getRows()
            AppLogger.info("Upload Sheets: leitura remota concluída, linhas=${remoteRows.size}")
            sheetsClient.ensureHeader(remoteRows)
            val remoteEntries = remoteRows.drop(1).mapNotNull(TripSheetCodec::fromRow)
            val remoteKeys = remoteEntries.mapTo(mutableSetOf(), ::syncKey)
            val legacyRemoteIds = remoteEntries.filter { it.sourceDeviceId.isBlank() }.mapTo(mutableSetOf()) { it.id }
            // Nunca reenviamos viagens que vieram de outros motoristas pela Sheet.
            // Cada aparelho é a única fonte das linhas com o seu próprio ANDROID_ID.
            val localEntries = database.tripDao()
                .getBySourceDeviceId(deviceIdentity.sourceId)
                .map(TripEntityMapper::toDomain)
            AppLogger.info(
                "Upload Sheets: base local=${localEntries.size}, remota=${remoteEntries.size}, " +
                    "origem=${deviceIdentity.sourceId.take(8)}"
            )
            val toAppend = localEntries.filter { entry ->
                syncKey(entry) !in remoteKeys && entry.id !in legacyRemoteIds
            }
            AppLogger.info("Upload Sheets: ofertas novas a enviar=${toAppend.size}")
            if (toAppend.isNotEmpty()) sheetsClient.appendRows(toAppend.map(TripSheetCodec::toRow))
            AppLogger.info(
                "Upload Sheets concluído: enviadas=${toAppend.size}, " +
                    "totalLocal=${localEntries.size}, totalRemotoAntes=${remoteEntries.size}"
            )
            true
        }
    }

    suspend fun downloadAll(): Boolean = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!sheetsClient.isConfigured) {
                AppLogger.warn("Download Sheets ignorado: configuração incompleta")
                return@withContext false
            }
            val remoteRows = sheetsClient.getRows()
            sheetsClient.ensureHeader(remoteRows)
            val remoteEntries = remoteRows.drop(1).mapNotNull(TripSheetCodec::fromRow)
            if (remoteEntries.isNotEmpty()) {
                database.tripDao().upsertAll(remoteEntries.map(TripEntityMapper::fromDomain))
            }
            AppLogger.info("Download Sheets concluído: recebidas=${remoteEntries.size}")
            true
        }
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        uploadPending()
        downloadAll()
        true
    }

    private fun syncKey(entry: com.daniel.tvdeinsight.domain.model.OfferHistoryEntry): String =
        "${entry.sourceDeviceId.ifBlank { "legacy" }}:${entry.id}"
}
