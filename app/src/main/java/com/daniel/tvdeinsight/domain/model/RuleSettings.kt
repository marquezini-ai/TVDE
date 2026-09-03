package com.daniel.tvdeinsight.domain.model

import kotlin.math.round

data class RuleSettings(
    val minEurPerKm: Double = 0.5,
    val goodEurPerKm: Double = 1.0,
    val minEurPerHour: Double = 15.0,
    val goodEurPerHour: Double = 20.0,
    val idealPickupDistanceKm: Double = 2.5,
    val acceptablePickupDistanceKm: Double = 4.0,
    val isPickupCriterionEnabled: Boolean = false,
    val isKmCriterionEnabled: Boolean = false,
    val isHourCriterionEnabled: Boolean = true,
    val areEvaluationCriteriaLocked: Boolean = false,
    val longTripMinimumKm: Double = 20.0,
    val isLongTripCriterionEnabled: Boolean = false,
    val minimumTripValue: Double = 2.5,
    val isMinimumTripValueCriterionEnabled: Boolean = false,
    val rejectTripsWithStops: Boolean = true,
    val isAppRunning: Boolean = false,
    val isUberEnabled: Boolean = true,
    val isBoltEnabled: Boolean = true,
    /** Guarda, apenas no armazenamento privado da app, a imagem da oferta avaliada. */
    val isOfferScreenshotCaptureEnabled: Boolean = false,
    /** Prazo de conservação das capturas privadas: 24 horas ou 7 dias. */
    val screenshotRetentionHours: Int = 24,
    val navigationApp: NavigationApp = NavigationApp.GOOGLE_MAPS,
    val isVehicleCostPerKmEnabled: Boolean = true,
    val vehicleType: VehicleType = VehicleType.ELECTRIC,
    val vehicleConsumptionPer100Km: Double = 0.0,
    val vehiclePricePerUnit: Double = 0.0
) {
    /** Só é aplicado quando a chave está ativa e existem os dois valores do veículo. */
    val hasVehicleCostPerKm: Boolean
        get() = isVehicleCostPerKmEnabled &&
            vehicleConsumptionPer100Km > 0.0 &&
            vehiclePricePerUnit > 0.0

    val vehicleCostPerKm: Double
        get() = (vehiclePricePerUnit * vehicleConsumptionPer100Km) / 100.0

    val activeCriteria: Set<EvaluationCriterion>
        get() = buildSet {
            if (isPickupCriterionEnabled) add(EvaluationCriterion.RECOLHA)
            if (isKmCriterionEnabled) add(EvaluationCriterion.KM)
            if (isHourCriterionEnabled) add(EvaluationCriterion.HORA)
            if (isLongTripCriterionEnabled) add(EvaluationCriterion.VIAGEM_LONGA)
            if (isMinimumTripValueCriterionEnabled) add(EvaluationCriterion.VALOR_MINIMO)
        }

    /**
     * Mantém os limites compatíveis com as barras da configuração e com os passos
     * que o utilizador consegue selecionar.
     */
    fun normalizedThresholds(): RuleSettings {
        val km = orderedPair(
            first = snap(minEurPerKm, maximum = 2.0, step = 0.05),
            second = snap(goodEurPerKm, maximum = 2.0, step = 0.05)
        )
        val hour = orderedPair(
            first = snap(minEurPerHour, maximum = 40.0, step = 0.5),
            second = snap(goodEurPerHour, maximum = 40.0, step = 0.5)
        )
        val pickup = orderedPair(
            first = snap(idealPickupDistanceKm, maximum = 10.0, step = 0.5),
            second = snap(acceptablePickupDistanceKm, maximum = 10.0, step = 0.5)
        )
        val longTrip = snap(longTripMinimumKm, minimum = 5.0, maximum = 100.0, step = 5.0)
        val normalizedMinimumTripValue = snap(minimumTripValue, minimum = 2.5, maximum = 5.0, step = 0.25)
        val consumption = snap(vehicleConsumptionPer100Km, maximum = 99.9, step = 0.1)
        val price = snap(vehiclePricePerUnit, maximum = 999.99, step = 0.01)
        val screenshotRetention = if (screenshotRetentionHours >= 7 * 24) 7 * 24 else 24

        return copy(
            minEurPerKm = km.first,
            goodEurPerKm = km.second,
            minEurPerHour = hour.first,
            goodEurPerHour = hour.second,
            idealPickupDistanceKm = pickup.first,
            acceptablePickupDistanceKm = pickup.second,
            longTripMinimumKm = longTrip,
            minimumTripValue = normalizedMinimumTripValue,
            // As rotas do histórico precisam preservar origem e destino.
            // Por isso, escolhas antigas de Waze são migradas para Google Maps.
            navigationApp = NavigationApp.GOOGLE_MAPS,
            vehicleConsumptionPer100Km = consumption,
            vehiclePricePerUnit = price,
            screenshotRetentionHours = screenshotRetention
        )
    }

    private fun orderedPair(first: Double, second: Double): Pair<Double, Double> =
        minOf(first, second) to maxOf(first, second)

    private fun snap(value: Double, minimum: Double = 0.0, maximum: Double, step: Double): Double {
        val snapped = round(value.coerceIn(minimum, maximum) / step) * step
        return round(snapped * 100.0) / 100.0
    }
}
