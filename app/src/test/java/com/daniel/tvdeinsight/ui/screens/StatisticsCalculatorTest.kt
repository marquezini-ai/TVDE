package com.daniel.tvdeinsight.ui.screens

import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatisticsCalculatorTest {

    @Test fun `groups the selected metric by platform and filters the shift`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.39, 18.0, 21, zone),
            entry(OfferPlatform.BOLT, 0.45, 22.0, 23, zone),
            entry(OfferPlatform.UBER, 0.90, 30.0, 10, zone)
        )

        val state = StatisticsCalculator.calculate(
            history = history,
            filters = StatisticsFilters(
                metric = StatisticsMetric.VALUE_PER_KM,
                shift = StatisticsShift.NIGHT
            ),
            nowMillis = now
        )

        assertEquals(2, state.matchingTripCount)
        assertEquals(2, state.matchingEntries.size)
        assertEquals(setOf(OfferPlatform.UBER, OfferPlatform.BOLT), state.matchingEntries.map { it.platform }.toSet())
        assertEquals(0.39, state.results.first { it.platform == OfferPlatform.UBER }.average, 0.001)
        assertEquals(0.45, state.results.first { it.platform == OfferPlatform.BOLT }.average, 0.001)
    }

    @Test fun `uses the selected start and end dates for the day filter`() {
        val zone = ZoneId.systemDefault()
        val selectedDay = LocalDateTime.of(2026, 8, 8, 0, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 8, 9, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.50, 20.0, 14, zone),
            OfferHistoryEntry(
                id = now,
                recordedAtMillis = now,
                platform = OfferPlatform.UBER,
                valorPorKm = 0.90,
                valorPorHora = 25.0,
                pickupDistanceKm = null,
                destinationDistanceKm = null,
                tripValue = 10.0
            )
        )

        val state = StatisticsCalculator.calculate(
            history = history,
            filters = StatisticsFilters(startDateMillis = selectedDay, endDateMillis = selectedDay),
            nowMillis = now
        )

        assertEquals(1, state.matchingTripCount)
        assertEquals(0.50, state.results.single().average, 0.001)
    }

    @Test fun `filters cards by displayed color and calculates their average offer value`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.50, 20.0, 19, zone).copy(
                tripValue = 8.0,
                decisionType = DecisionType.ACEITAR
            ),
            entry(OfferPlatform.BOLT, 0.60, 22.0, 20, zone).copy(
                tripValue = 14.0,
                decisionType = DecisionType.REJEITAR
            ),
            entry(OfferPlatform.UBER, 0.70, 24.0, 21, zone).copy(
                tripValue = 12.0,
                decisionType = DecisionType.ACEITAR
            )
        )

        val state = StatisticsCalculator.calculate(
            history = history,
            filters = StatisticsFilters(
                cardColor = StatisticsCardColor.GREEN,
                metric = StatisticsMetric.TRIP_VALUE
            ),
            nowMillis = now
        )

        assertEquals(2, state.matchingTripCount)
        assertEquals(10.0, state.results.single().average, 0.001)
        assertEquals(setOf(DecisionType.ACEITAR), state.matchingEntries.map { it.decisionType }.toSet())
    }

    @Test fun `uses category and gross mode while the median resists an extreme offer`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.50, 20.0, 18, zone).copy(category = "UberX", valorPorKmBruto = 0.70),
            entry(OfferPlatform.UBER, 0.60, 22.0, 19, zone).copy(category = "UberX", valorPorKmBruto = 0.80),
            entry(OfferPlatform.UBER, 9.00, 90.0, 20, zone).copy(category = "Comfort", valorPorKmBruto = 10.0)
        )

        val state = StatisticsCalculator.calculate(
            history,
            StatisticsFilters(
                category = StatisticsCategoryOption(OfferPlatform.UBER, "uberx"),
                metric = StatisticsMetric.VALUE_PER_KM,
                valueMode = StatisticsValueMode.GROSS
            ),
            now
        )

        assertEquals(2, state.matchingTripCount)
        assertEquals(0.75, state.summary.medianPerKm, 0.001)
        assertEquals(0.75, state.results.single().median, 0.001)
        assertEquals(listOf("Comfort", "UberX"), state.availableCategories.map(StatisticsCategoryOption::name))
    }

    @Test fun `summarizes rejection reasons and identifies the better platform`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.50, 18.0, 19, zone).copy(
                decisionType = DecisionType.REJEITAR,
                activeCriteria = setOf(EvaluationCriterion.HORA),
                criterionDecisions = mapOf(EvaluationCriterion.HORA to DecisionType.REJEITAR)
            ),
            entry(OfferPlatform.BOLT, 0.80, 26.0, 20, zone).copy(decisionType = DecisionType.ACEITAR)
        )

        val state = StatisticsCalculator.calculate(history, StatisticsFilters(), now)

        assertEquals(OfferPlatform.BOLT, state.summary.bestPlatform)
        assertEquals(60.0, state.summary.bestPlatformAdvantagePercent!!, 0.001)
        assertEquals("Hora", state.rejectionReasons.single().label)
        assertEquals(1, state.rejectionReasons.single().count)
    }

    @Test fun `keeps categories separated by platform and only includes selected platforms`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.50, 18.0, 19, zone).copy(category = "Comfort"),
            entry(OfferPlatform.BOLT, 0.80, 25.0, 20, zone).copy(category = "Comfort")
        )

        val both = StatisticsCalculator.calculate(
            history,
            StatisticsFilters(category = StatisticsCategoryOption(OfferPlatform.UBER, "Comfort")),
            now
        )
        val boltOnly = StatisticsCalculator.calculate(
            history,
            StatisticsFilters(platforms = setOf(OfferPlatform.BOLT)),
            now
        )

        assertEquals(1, both.matchingTripCount)
        assertEquals(OfferPlatform.UBER, both.matchingEntries.single().platform)
        assertEquals(setOf(OfferPlatform.UBER, OfferPlatform.BOLT), both.availableCategories.map { it.platform }.toSet())
        assertEquals(1, boltOnly.matchingTripCount)
        assertEquals(listOf(OfferPlatform.BOLT), boltOnly.availableCategories.map { it.platform }.distinct())
    }

    @Test fun `ranks up to five pickup municipalities using the selected metric and ignores invalid addresses`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.70, 18.0, 17, zone).copy(pickupAddress = "Rua A, Porto 4000-123"),
            entry(OfferPlatform.BOLT, 0.90, 19.0, 18, zone).copy(pickupAddress = "Rua B, Porto 4000-456"),
            entry(OfferPlatform.UBER, 0.80, 20.0, 19, zone).copy(pickupAddress = "Rua C, Valongo 4445-001"),
            entry(OfferPlatform.BOLT, 0.60, 21.0, 20, zone).copy(pickupAddress = "Rua D, Maia 4470-999"),
            entry(OfferPlatform.UBER, 0.50, 22.0, 21, zone).copy(pickupAddress = "Rua E, Matosinhos"),
            entry(OfferPlatform.BOLT, 0.40, 23.0, 22, zone).copy(pickupAddress = "Rua sem município, "),
            entry(OfferPlatform.BOLT, 0.95, 24.0, 16, zone).copy(pickupAddress = "Rua sem vírgula")
        )

        val state = StatisticsCalculator.calculate(history, StatisticsFilters(), now)

        assertEquals(
            listOf("Porto", "Valongo", "Maia", "Matosinhos"),
            state.pickupMunicipalities.map(PickupMunicipalityStatistic::municipality)
        )
        assertEquals(0.80, state.pickupMunicipalities.first().median, 0.001)
        assertEquals(2, state.pickupMunicipalities.first().tripCount)
    }

    @Test fun `uses only the municipality after the comma and discards the postal code`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.90, 18.0, 17, zone).copy(
                pickupAddress = "Bairro do Calvario 300, Valongo"
            ),
            entry(OfferPlatform.UBER, 0.80, 18.0, 18, zone).copy(
                pickupAddress = "Rua alto de barreios 1395, Gondomar"
            ),
            entry(OfferPlatform.BOLT, 0.70, 18.0, 19, zone).copy(
                pickupAddress = "Rua da constituicao 768, Porto 4200-195"
            )
        )

        val state = StatisticsCalculator.calculate(history, StatisticsFilters(), now)

        assertEquals(
            listOf("Valongo", "Gondomar", "Porto"),
            state.pickupMunicipalities.map(PickupMunicipalityStatistic::municipality)
        )
    }

    @Test fun `never ranks a street name when the pickup municipality is missing`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.90, 18.0, 17, zone).copy(
                pickupAddress = "Rua de França Júnior, Matosinhos"
            ),
            entry(OfferPlatform.BOLT, 0.80, 18.0, 18, zone).copy(
                pickupAddress = "Estrada da Circunvalação, Porto 4200-195"
            ),
            entry(OfferPlatform.UBER, 0.99, 18.0, 19, zone).copy(
                pickupAddress = "Rua de Cândido dos Reis"
            )
        )

        val state = StatisticsCalculator.calculate(history, StatisticsFilters(), now)

        assertEquals(
            listOf("Matosinhos", "Porto"),
            state.pickupMunicipalities.map(PickupMunicipalityStatistic::municipality)
        )
    }

    @Test fun `builds a thirty day calendar using the selected metric average`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val history = listOf(
            entry(OfferPlatform.UBER, 0.40, 18.0, 20, zone).copy(tripValue = 8.0),
            entry(OfferPlatform.UBER, 0.80, 18.0, 21, zone).copy(tripValue = 12.0)
        )

        val state = StatisticsCalculator.calculate(
            history,
            StatisticsFilters(metric = StatisticsMetric.VALUE_PER_KM),
            now
        )

        assertEquals(30, state.dailyCalendar.size)
        val today = state.dailyCalendar.single { it.date == LocalDate.of(2026, 8, 8) }
        assertEquals(0.60, today.average!!, 0.001)
        assertEquals(2, today.tripCount)
        assertEquals(29, state.dailyCalendar.count { it.average == null })
    }

    @Test fun `keeps personal indicators local and calculates collective charts globally`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 8, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val personal = listOf(
            entry(OfferPlatform.UBER, 0.40, 18.0, 20, zone).copy(pickupAddress = "Rua A, Porto")
        )
        val global = personal + entry(OfferPlatform.BOLT, 0.90, 30.0, 21, zone).copy(
            id = now + 1,
            pickupAddress = "Rua B, Matosinhos"
        )

        val state = StatisticsCalculator.calculate(personal, global, StatisticsFilters(), now)

        assertEquals(1, state.matchingTripCount)
        assertEquals(listOf(OfferPlatform.UBER), state.results.map(PlatformStatistic::platform))
        assertEquals(2, state.pickupMunicipalities.sumOf(PickupMunicipalityStatistic::tripCount))
        assertEquals(2, state.dailyCalendar.single { it.date == LocalDate.of(2026, 8, 8) }.tripCount)
    }

    private fun entry(
        platform: OfferPlatform,
        perKm: Double,
        perHour: Double,
        hour: Int,
        zone: ZoneId
    ): OfferHistoryEntry {
        val timestamp = LocalDateTime.of(2026, 8, 8, hour, 0).atZone(zone).toInstant().toEpochMilli()
        return OfferHistoryEntry(
            id = timestamp,
            recordedAtMillis = timestamp,
            platform = platform,
            valorPorKm = perKm,
            valorPorHora = perHour,
            pickupDistanceKm = null,
            destinationDistanceKm = null,
            tripValue = 10.0
        )
    }
}
