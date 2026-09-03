package com.daniel.tvdeinsight.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daniel.tvdeinsight.data.repository.SettingsRepository
import com.daniel.tvdeinsight.data.repository.ThemePreferencesRepository
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val themePreferencesRepository: ThemePreferencesRepository
) : AndroidViewModel(application) {

    private val _settings = MutableStateFlow(RuleSettings(isAppRunning = false))
    val settings: StateFlow<RuleSettings> = _settings.asStateFlow()
    val themeMode = themePreferencesRepository.themeMode
    private val settingsWriteMutex = Mutex()
    private var pendingSettingsWrites = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { latestFromDisk ->
                // Durante gravações locais, o DataStore ainda pode emitir o valor anterior.
                // Ignorá-lo evita que a interface volte temporariamente a um limite antigo.
                if (pendingSettingsWrites == 0) {
                    _settings.value = latestFromDisk
                }
            }
        }
    }

    fun saveSettings(newSettings: RuleSettings) {
        val normalizedSettings = newSettings.normalizedThresholds()
        pendingSettingsWrites += 1
        _settings.value = normalizedSettings
        viewModelScope.launch {
            settingsWriteMutex.withLock {
                try {
                    settingsRepository.update(normalizedSettings)
                    AppLogger.info(
                        "Configurações atualizadas: critérios=${normalizedSettings.activeCriteria.joinToString { it.name }}, " +
                            "km=[${normalizedSettings.minEurPerKm},${normalizedSettings.goodEurPerKm}], " +
                            "hora=[${normalizedSettings.minEurPerHour},${normalizedSettings.goodEurPerHour}], " +
                            "recolha=[${normalizedSettings.idealPickupDistanceKm},${normalizedSettings.acceptablePickupDistanceKm}], " +
                            "barrasBloqueadas=${normalizedSettings.areEvaluationCriteriaLocked}, " +
                            "viagemLonga=[${normalizedSettings.longTripMinimumKm},ativa=${normalizedSettings.isLongTripCriterionEnabled}], " +
                            "paradas=${normalizedSettings.rejectTripsWithStops}, " +
                            "veiculo=[ativo=${normalizedSettings.isVehicleCostPerKmEnabled},${normalizedSettings.vehicleType},${normalizedSettings.vehicleConsumptionPer100Km},${normalizedSettings.vehiclePricePerUnit}]"
                    )
                } finally {
                    pendingSettingsWrites -= 1
                    if (pendingSettingsWrites == 0) {
                        _settings.value = normalizedSettings
                    }
                }
            }
        }
    }

    fun toggleAppRunning(isRunning: Boolean) {
        saveSettings(_settings.value.copy(isAppRunning = isRunning))
        AppLogger.info("Monitorização alterada: ativo=$isRunning")
    }

    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferencesRepository.saveThemeMode(mode)
        }
    }

    /** Cálculo do custo operacional do veículo, atualizado enquanto os campos são editados. */
    fun calculateVehicleCostPerKm(consumptionPer100Km: Double, pricePerUnit: Double): Double =
        (pricePerUnit.coerceAtLeast(0.0) * consumptionPer100Km.coerceAtLeast(0.0)) / 100.0
}
