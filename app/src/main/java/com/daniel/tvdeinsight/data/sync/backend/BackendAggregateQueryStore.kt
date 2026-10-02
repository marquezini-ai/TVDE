package com.daniel.tvdeinsight.data.sync.backend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Singleton
class BackendAggregateQueryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _query = MutableStateFlow(load())
    val query: StateFlow<AggregateQueryPayload> = _query

    @Synchronized
    fun current(): AggregateQueryPayload = _query.value

    @Synchronized
    fun save(value: AggregateQueryPayload): Boolean {
        if (_query.value == value) return false
        preferences.edit().putString(QUERY_JSON, BackendHttpClient.json.encodeToString(value)).apply()
        _query.value = value
        return true
    }

    private fun load(): AggregateQueryPayload = preferences.getString(QUERY_JSON, null)
        ?.let { runCatching { BackendHttpClient.json.decodeFromString<AggregateQueryPayload>(it) }.getOrNull() }
        ?: defaultQuery()

    companion object {
        private const val PREFERENCES_NAME = "backend_aggregate_query"
        private const val QUERY_JSON = "query_json"

        fun defaultQuery(today: LocalDate = LocalDate.now()): AggregateQueryPayload = AggregateQueryPayload(
            platforms = listOf("UBER", "BOLT"),
            metric = "VALUE_PER_KM",
            valueMode = "FREE",
            shift = "ALL",
            cardColor = "ALL",
            startDate = today.minusDays(29).toString(),
            endDate = today.toString()
        )
    }
}
