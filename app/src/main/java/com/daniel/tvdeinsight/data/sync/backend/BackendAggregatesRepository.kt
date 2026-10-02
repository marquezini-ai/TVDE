package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString

@Singleton
class BackendAggregatesRepository @Inject constructor(
    database: AppDatabase
) {
    val aggregates: Flow<GlobalAggregatesPayload?> = database.syncDao().observeState().map { state ->
        state?.globalAggregatesJson?.let { encoded ->
            runCatching { BackendHttpClient.json.decodeFromString<GlobalAggregatesPayload>(encoded) }.getOrNull()
        }
    }
}
