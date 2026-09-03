package com.daniel.tvdeinsight.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.NavigationApp
import com.daniel.tvdeinsight.domain.model.VehicleType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rule_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object PreferencesKeys {
        val MIN_EUR_PER_KM = doublePreferencesKey("min_eur_per_km")
        val GOOD_EUR_PER_KM = doublePreferencesKey("good_eur_per_km")
        val MIN_EUR_PER_HOUR = doublePreferencesKey("min_eur_per_hour")
        val GOOD_EUR_PER_HOUR = doublePreferencesKey("good_eur_per_hour")
        val IDEAL_PICKUP_DISTANCE_KM = doublePreferencesKey("ideal_pickup_distance_km")
        val ACCEPTABLE_PICKUP_DISTANCE_KM = doublePreferencesKey("acceptable_pickup_distance_km")
        val IS_PICKUP_CRITERION_ENABLED = booleanPreferencesKey("is_pickup_criterion_enabled")
        val IS_KM_CRITERION_ENABLED = booleanPreferencesKey("is_km_criterion_enabled")
        val IS_HOUR_CRITERION_ENABLED = booleanPreferencesKey("is_hour_criterion_enabled")
        val ARE_EVALUATION_CRITERIA_LOCKED = booleanPreferencesKey("are_evaluation_criteria_locked")
        val LONG_TRIP_MINIMUM_KM = doublePreferencesKey("long_trip_minimum_km")
        val IS_LONG_TRIP_CRITERION_ENABLED = booleanPreferencesKey("is_long_trip_criterion_enabled")
        val MINIMUM_TRIP_VALUE = doublePreferencesKey("minimum_trip_value")
        val IS_MINIMUM_TRIP_VALUE_CRITERION_ENABLED = booleanPreferencesKey("is_minimum_trip_value_criterion_enabled")
        // Mantida apenas para migrar as escolhas das versões anteriores.
        val PRIORITY_MODE = stringPreferencesKey("priority_mode")
        val REJECT_TRIPS_WITH_STOPS = booleanPreferencesKey("reject_trips_with_stops")
        val IS_APP_RUNNING = booleanPreferencesKey("is_app_running")
        val IS_UBER_ENABLED = booleanPreferencesKey("is_uber_enabled")
        val IS_BOLT_ENABLED = booleanPreferencesKey("is_bolt_enabled")
        val IS_OFFER_SCREENSHOT_CAPTURE_ENABLED = booleanPreferencesKey("is_offer_screenshot_capture_enabled")
        val SCREENSHOT_RETENTION_HOURS = intPreferencesKey("screenshot_retention_hours")
        val NAVIGATION_APP = stringPreferencesKey("navigation_app")
        val IS_VEHICLE_COST_PER_KM_ENABLED = booleanPreferencesKey("is_vehicle_cost_per_km_enabled")
        val VEHICLE_TYPE = stringPreferencesKey("vehicle_type")
        val VEHICLE_CONSUMPTION_PER_100_KM = doublePreferencesKey("vehicle_consumption_per_100_km")
        val VEHICLE_PRICE_PER_UNIT = doublePreferencesKey("vehicle_price_per_unit")
    }

    override val settings: Flow<RuleSettings> = context.dataStore.data
        .map { preferences ->
            val legacyCriteria = legacyCriteria(preferences[PreferencesKeys.PRIORITY_MODE])

            RuleSettings(
                minEurPerKm = preferences[PreferencesKeys.MIN_EUR_PER_KM] ?: 0.5,
                goodEurPerKm = preferences[PreferencesKeys.GOOD_EUR_PER_KM] ?: 1.0,
                minEurPerHour = preferences[PreferencesKeys.MIN_EUR_PER_HOUR] ?: 15.0,
                goodEurPerHour = preferences[PreferencesKeys.GOOD_EUR_PER_HOUR] ?: 20.0,
                idealPickupDistanceKm = preferences[PreferencesKeys.IDEAL_PICKUP_DISTANCE_KM] ?: 2.5,
                acceptablePickupDistanceKm = preferences[PreferencesKeys.ACCEPTABLE_PICKUP_DISTANCE_KM] ?: 4.0,
                isPickupCriterionEnabled = preferences[PreferencesKeys.IS_PICKUP_CRITERION_ENABLED]
                    ?: legacyCriteria.pickup,
                isKmCriterionEnabled = preferences[PreferencesKeys.IS_KM_CRITERION_ENABLED]
                    ?: legacyCriteria.km,
                isHourCriterionEnabled = preferences[PreferencesKeys.IS_HOUR_CRITERION_ENABLED]
                    ?: legacyCriteria.hour,
                areEvaluationCriteriaLocked = preferences[PreferencesKeys.ARE_EVALUATION_CRITERIA_LOCKED] ?: false,
                longTripMinimumKm = preferences[PreferencesKeys.LONG_TRIP_MINIMUM_KM] ?: 20.0,
                isLongTripCriterionEnabled = preferences[PreferencesKeys.IS_LONG_TRIP_CRITERION_ENABLED] ?: false,
                minimumTripValue = preferences[PreferencesKeys.MINIMUM_TRIP_VALUE] ?: 2.5,
                isMinimumTripValueCriterionEnabled = preferences[PreferencesKeys.IS_MINIMUM_TRIP_VALUE_CRITERION_ENABLED]
                    ?: false,
                rejectTripsWithStops = preferences[PreferencesKeys.REJECT_TRIPS_WITH_STOPS] ?: true,
                isAppRunning = preferences[PreferencesKeys.IS_APP_RUNNING] ?: false,
                isUberEnabled = preferences[PreferencesKeys.IS_UBER_ENABLED] ?: true,
                isBoltEnabled = preferences[PreferencesKeys.IS_BOLT_ENABLED] ?: true,
                isOfferScreenshotCaptureEnabled = preferences[PreferencesKeys.IS_OFFER_SCREENSHOT_CAPTURE_ENABLED] ?: false,
                screenshotRetentionHours = preferences[PreferencesKeys.SCREENSHOT_RETENTION_HOURS] ?: 24,
                navigationApp = NavigationApp.entries.firstOrNull {
                    it.name == preferences[PreferencesKeys.NAVIGATION_APP]
                } ?: NavigationApp.GOOGLE_MAPS,
                isVehicleCostPerKmEnabled = preferences[PreferencesKeys.IS_VEHICLE_COST_PER_KM_ENABLED] ?: true,
                vehicleType = VehicleType.entries.firstOrNull {
                    it.name == preferences[PreferencesKeys.VEHICLE_TYPE]
                } ?: VehicleType.ELECTRIC,
                vehicleConsumptionPer100Km = preferences[PreferencesKeys.VEHICLE_CONSUMPTION_PER_100_KM] ?: 0.0,
                vehiclePricePerUnit = preferences[PreferencesKeys.VEHICLE_PRICE_PER_UNIT] ?: 0.0
            ).normalizedThresholds()
        }

    override suspend fun update(settings: RuleSettings) {
        val normalizedSettings = settings.normalizedThresholds()
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_EUR_PER_KM] = normalizedSettings.minEurPerKm
            preferences[PreferencesKeys.GOOD_EUR_PER_KM] = normalizedSettings.goodEurPerKm
            preferences[PreferencesKeys.MIN_EUR_PER_HOUR] = normalizedSettings.minEurPerHour
            preferences[PreferencesKeys.GOOD_EUR_PER_HOUR] = normalizedSettings.goodEurPerHour
            preferences[PreferencesKeys.IDEAL_PICKUP_DISTANCE_KM] = normalizedSettings.idealPickupDistanceKm
            preferences[PreferencesKeys.ACCEPTABLE_PICKUP_DISTANCE_KM] = normalizedSettings.acceptablePickupDistanceKm
            preferences[PreferencesKeys.IS_PICKUP_CRITERION_ENABLED] = normalizedSettings.isPickupCriterionEnabled
            preferences[PreferencesKeys.IS_KM_CRITERION_ENABLED] = normalizedSettings.isKmCriterionEnabled
            preferences[PreferencesKeys.IS_HOUR_CRITERION_ENABLED] = normalizedSettings.isHourCriterionEnabled
            preferences[PreferencesKeys.ARE_EVALUATION_CRITERIA_LOCKED] = normalizedSettings.areEvaluationCriteriaLocked
            preferences[PreferencesKeys.LONG_TRIP_MINIMUM_KM] = normalizedSettings.longTripMinimumKm
            preferences[PreferencesKeys.IS_LONG_TRIP_CRITERION_ENABLED] = normalizedSettings.isLongTripCriterionEnabled
            preferences[PreferencesKeys.MINIMUM_TRIP_VALUE] = normalizedSettings.minimumTripValue
            preferences[PreferencesKeys.IS_MINIMUM_TRIP_VALUE_CRITERION_ENABLED] =
                normalizedSettings.isMinimumTripValueCriterionEnabled
            preferences.remove(PreferencesKeys.PRIORITY_MODE)
            preferences[PreferencesKeys.REJECT_TRIPS_WITH_STOPS] = normalizedSettings.rejectTripsWithStops
            preferences[PreferencesKeys.IS_APP_RUNNING] = normalizedSettings.isAppRunning
            preferences[PreferencesKeys.IS_UBER_ENABLED] = normalizedSettings.isUberEnabled
            preferences[PreferencesKeys.IS_BOLT_ENABLED] = normalizedSettings.isBoltEnabled
            preferences[PreferencesKeys.IS_OFFER_SCREENSHOT_CAPTURE_ENABLED] = normalizedSettings.isOfferScreenshotCaptureEnabled
            preferences[PreferencesKeys.SCREENSHOT_RETENTION_HOURS] = normalizedSettings.screenshotRetentionHours
            preferences[PreferencesKeys.NAVIGATION_APP] = normalizedSettings.navigationApp.name
            preferences[PreferencesKeys.IS_VEHICLE_COST_PER_KM_ENABLED] = normalizedSettings.isVehicleCostPerKmEnabled
            preferences[PreferencesKeys.VEHICLE_TYPE] = normalizedSettings.vehicleType.name
            preferences[PreferencesKeys.VEHICLE_CONSUMPTION_PER_100_KM] = normalizedSettings.vehicleConsumptionPer100Km
            preferences[PreferencesKeys.VEHICLE_PRICE_PER_UNIT] = normalizedSettings.vehiclePricePerUnit
        }
    }

    private fun legacyCriteria(value: String?): LegacyCriteria = when (value) {
        "RECOLHA" -> LegacyCriteria(pickup = true, km = false, hour = false)
        "KM" -> LegacyCriteria(pickup = false, km = true, hour = false)
        "TODOS", "AMBOS" -> LegacyCriteria(pickup = true, km = true, hour = true)
        else -> LegacyCriteria(pickup = false, km = false, hour = true)
    }

    private data class LegacyCriteria(val pickup: Boolean, val km: Boolean, val hour: Boolean)
}
