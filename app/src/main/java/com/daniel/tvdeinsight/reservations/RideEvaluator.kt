package com.daniel.tvdeinsight.reservations

data class RideEvaluation(
    val accepted: Boolean,
    val reasons: List<String>,
    val categoryPassed: Boolean,
    val tripValuePassed: Boolean,
    val perKmPassed: Boolean,
    val tripDistancePassed: Boolean,
    val availabilityPassed: Boolean,
    val pickupDistancePassed: Boolean?
)

object RideEvaluator {
    fun evaluate(ride: RideCandidate, settings: ReservationSettings): RideEvaluation =
        evaluate(ride, settings, pickupDistanceKm = null)

    /**
     * Avalia os critérios atuais. O valor/hora não participa mais da decisão.
     * Quando existe morada de casa, pickupDistanceKm é a distância em linha
     * reta entre a casa e a recolha, usada como raio máximo.
     */
    fun evaluate(
        ride: RideCandidate,
        settings: ReservationSettings,
        pickupDistanceKm: Double?
    ): RideEvaluation {
        val categoryPassed = settings.categories.any { it.equals(ride.category, ignoreCase = true) }
        val tripValuePassed = ride.payout >= settings.minimumTripValue
        val perKmPassed = ride.valuePerKm >= settings.minimumPerKm
        val tripDistancePassed = ride.distanceKm in 0.000001..settings.maxTripDistanceKm
        val fallbackSchedule = DailyAvailability(settings.startMinutes, settings.endMinutes)
        val tripDate = TripDateResolver.parseResolvedDate(ride.tripDate)
            ?: TripDateResolver.resolveDate(ride.sourceText)
        val availabilityPassed = WeeklyAvailability.contains(
            settings.weeklyAvailability,
            tripDate,
            ride.startMinutes,
            fallbackSchedule,
            settings.enabledDays
        )
        val pickupDistancePassed = if (settings.homeAddress.isBlank()) null
        else pickupDistanceKm != null && pickupDistanceKm <= settings.maxPickupDistanceKm
        val reasons = buildList {
            if (!categoryPassed) add("categoria não selecionada")
            if (!perKmPassed) add("valor/km abaixo do mínimo")
            if (!tripValuePassed) add("valor total abaixo do mínimo")
            when {
                ride.distanceKm <= 0.0 -> add("distância da viagem inválida")
                !tripDistancePassed -> add("distância da viagem acima do limite de ${TripDistanceScale.format(settings.maxTripDistanceKm)} km")
            }
            if (!availabilityPassed) add("hora fora da disponibilidade")
            if (settings.homeAddress.isNotBlank()) {
                when {
                    pickupDistanceKm == null -> add("distância de recolha não calculada")
                    pickupDistancePassed == false -> add("distância de recolha acima do raio de ${settings.maxPickupDistanceKm} km")
                }
            }
        }
        return RideEvaluation(
            accepted = reasons.isEmpty(),
            reasons = reasons,
            categoryPassed = categoryPassed,
            tripValuePassed = tripValuePassed,
            perKmPassed = perKmPassed,
            tripDistancePassed = tripDistancePassed,
            availabilityPassed = availabilityPassed,
            pickupDistancePassed = pickupDistancePassed
        )
    }
}
