package com.daniel.tvdeinsight.ui.screens

import androidx.lifecycle.ViewModel
import com.daniel.tvdeinsight.data.repository.OfferAnalysisStore
import com.daniel.tvdeinsight.data.repository.SettingsRepository
import com.daniel.tvdeinsight.data.screenshot.OfferScreenshotStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    analysisStore: OfferAnalysisStore,
    settingsRepository: SettingsRepository,
    val screenshotStore: OfferScreenshotStore
) : ViewModel() {
    val history = analysisStore.history
    val settings = settingsRepository.settings
}
