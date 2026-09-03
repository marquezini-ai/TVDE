package com.daniel.tvdeinsight.data.repository

import com.daniel.tvdeinsight.domain.model.RuleSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<RuleSettings>
    suspend fun update(settings: RuleSettings)
}